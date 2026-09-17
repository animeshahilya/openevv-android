# EloQuick Android: Better Implementation Plan

Based on thorough analysis of evvdroid and current openevv-android codebase.

---

## Executive Summary

Current openevv-android has solid foundations but is missing key features from evvdroid that make it production-ready for TalkBack/screen-reader use. This plan addresses the gaps.

---

## Current State Analysis

### What Works Well ✅
- Core JNI bridge with streaming (`eq_stream` with ring buffer + worker thread)
- Two-phase end detection (started → eciSynchronize)
- Abort flag instead of `eciStop` (prevents ~80-utterance crash)
- Per-instance dictionary management with refcounting
- Heteronym filter (creation-time via EVV_HETERO)
- Multi-ABI build system (Python + CMake)
- Kotlin bindings with coroutines/Flow
- 10 languages, 8 voices, 16KB page alignment

### Missing from evvdroid ❌

| Feature | Current | evvdroid | Priority |
|---------|---------|----------|----------|
| **Prosody annotations** (rate/pitch per utterance) | ❌ | ✅ `vs`/`vb` | P0 |
| **Pause shortening** (3 modes) | ❌ | ✅ `` `p` `` | P0 |
| **Phrase prediction toggle** | ❌ | ✅ `` `pp1` `` | P1 |
| **Dictionary file loading** (text → word-by-word) | ❌ | ✅ Tab-separated | P0 |
| **Sentence chunking** (for interruptibility) | ❌ | ✅ | P1 |
| **Paced synthesis** (300ms lead) | ❌ | ✅ Pace class | P1 |
| **Speech rate curve** (non-linear) | ❌ | ✅ SpeechRate class | P1 |
| **Per-voice parameter persistence** | ❌ | ✅ baseSpeed/basePitch | P1 |
| **Abbreviations expansion toggle** | ❌ | ✅ `eciDictionary` | P1 |
| **Dictionary file parsing** | ❌ | ✅ ISO-8859-1 tab-separated | P1 |
| **Preview language in settings** | ❌ | ✅ | P2 |
| **Wear OS support** | ❌ | ✅ armeabi-v7a | P2 |

---

## Implementation Phases

---

## Phase 1: Core Streaming Improvements (Week 1-2)

### 1.1 Fix Streaming JNI: Prosody Annotations

**Problem**: Current streaming uses voice parameters (`setVoiceParam`) which the engine refuses while speaking (`vc_reentered = -1`).

**Solution**: Send rate/pitch/phrase-prediction as annotations in the text stream.

**JNI Changes** (`eloquick_jni.c`):
```c
// Add to eq_stream struct:
int phrase_prediction;

// In nativeStreamCreate, enable annotations by default:
eciSetParam(h, 1 /* P_INPUT_TYPE */, 1);  // annotations on

// In nativeStreamSpeak, prepend prosody annotations:
static void eq_stream_prepend_prosody(eq_stream *s, const char *text) {
    char *buf = malloc(strlen(text) + 128);
    // rate: `vs{speed} `, pitch: `vb{pitch} `
    // phrase prediction: `pp1` or `pp0`
    sprintf(buf, "`vs%d `vb%d `pp%d %s", 
            s->speed, s->pitch, s->phrase_prediction, text);
    // use buf for eciAddText
}
```

### 1.2 Add Pause Shortening (3 Modes)

```c
// Add to eq_stream struct:
int pause_mode;  // 0=keep, 1=end_only, 2=all

// In nativeStreamSpeak, prepend pause annotation:
static void eq_stream_prepend_pauses(eq_stream *s, char *buf) {
    if (s->pause_mode == 1) strcat(buf, "`p100 ");      // trim end only
    else if (s->pause_mode == 2) strcat(buf, "`p1 ");   // shorten all
}
```

### 1.3 Add Phrase Prediction Toggle

```c
// Add to eq_stream struct and nativeStreamCreate:
s->phrase_prediction = 0;  // default off

// JNI method:
JNIEXPORT void JNICALL
Java_com_eloquick_tts_EloQuickEngine_nativeStreamSetPhrasePrediction(JNIEnv *env, jclass cls,
                                                                      jlong shandle, jboolean on)
{
    eq_stream *s = (eq_stream *)(intptr_t)shandle;
    if (s) s->phrase_prediction = on;
}
```

---

## Phase 2: Dictionary File Loading (Week 2-3)

### 2.1 Add Dictionary File Parsing (JNI)

```c
// In eloquick_jni.c - parse ISO-8859-1 tab-separated files
static int eq_dict_load_file(ECIHand h, ECIDictHand dict, int volume, const char *path) {
    FILE *f = fopen(path, "r");
    if (!f) return 6; // eciDictAccessError
    
    char *line = NULL;
    size_t len = 0;
    ssize_t read;
    int taught = 0, refused = 0;
    
    while ((read = getline(&line, &len, f)) != -1) {
        char *tab = strchr(line, '\t');
        if (!tab) continue;
        *tab = '\0';
        char *key = line;
        char *say = tab + 1;
        
        // Trim trailing newline/whitespace
        char *end = say + strlen(say) - 1;
        while (end > say && (*end == '\n' || *end == '\r' || *end == '\r')) *end-- = '\0';
        
        if (strlen(key) == 0 || strlen(say) == 0) continue;
        
        char *pair = malloc(strlen(key) + strlen(say) + 2);
        memcpy(pair, key, strlen(key) + 1);
        memcpy(pair + strlen(key) + 1, say, strlen(say) + 1);
        
        int rc = eciUpdateDict(h, dict, volume, pair, pair + strlen(key) + 1);
        free(pair);
        if (rc == 0) taught++; else refused++;
    }
    free(line);
    fclose(f);
    if (refused) LOGE("%d of %d entries refused", refused, taught + refused);
    return taught > 0 ? 0 : 6;
}
```

### 2.2 Add JNI Methods for File Loading

```c
// JNI methods for both regular and stream handles:
JNIEXPORT jint JNICALL Java_com_eloquick_tts_EloQuickEngine_nativeDictLoadFile
JNIEXPORT jint JNICALL Java_com_eloquick_tts_EloQuickEngine_nativeStreamDictLoadFile
```

---

## Phase 3: Text Encoding & Prosody (Week 3-4)

### 3.1 Add WesternText Flattening (for 8 Latin languages)

```kotlin
// EngineText.kt - port from evvdroid
object EngineText {
    fun encode(language: Int, text: String): ByteArray = when (baseLanguage(language)) {
        JAPANESE -> japanese(text)
        POLISH -> WesternText.flatten(text) { it in POLISH_LETTERS }.toByteArray(Charsets.UTF_8)
        else -> WesternText.encode(text)
    }
}

object WesternText {
    private val PLAIN = mapOf(
        '‘' to "'", '’' to "'", '“' to "\"", '”' to "\"",
        '–' to "-", '—' to "-", '…' to "...", '•' to ".",
        ' ' to " ", '€' to " euro ", '£' to " pounds ", // etc.
    )
    
    fun encode(text: String): ByteArray { /* flatten + Latin-1 */ }
    
    fun flatten(text: String, own: (Char) -> Boolean): String {
        // Normalize NFD, strip combining marks, replace unknown with space
    }
}
```

### 3.2 Add Prosody Class (Annotations)

```kotlin
object Prosody {
    private const val ON = "`pp1 "
    private const val OFF = "`pp0 "
    
    fun prefix(phrasePrediction: Boolean) = if (phrasePrediction) ON else OFF
    
    fun voice(speed: Int, pitch: Int): String =
        "`vs${clampVoice(VOICE_SPEED, speed)} `vb${clampVoice(VOICE_PITCH_BASELINE, pitch)} "
}
```

### 3.2 Add SpeechRate Curve (Non-linear)

```kotlin
object SpeechRate {
    // Engine speed is non-linear: 50→1.0x, 200→16.6x
    // Use measured curve from evvdroid
    fun speedForPercent(baseSpeed: Int, percent: Int): Int {
        val speed = baseSpeed * percent / 100.0
        // Apply inverse curve: engine speed → perceived rate
        return (speed * (0.8 + 0.004 * speed)).toInt().coerceIn(0, 250)
    }
}
```

---

## Phase 4: Dictionary File UI & Settings (Week 4-5)

### 4.1 Add Dictionary File Parsing (Kotlin)

```kotlin
// Dictionaries.kt
object Dictionaries {
    fun load(engine: EloQuickEngine, volume: Int, file: File): Int {
        var taught = 0
        file.forEachLine(Charsets.ISO_8859_1) { line ->
            val split = line.indexOf('\t')
            if (split > 0) {
                val key = line.substring(0, split).trim()
                val say = line.substring(split + 1).trim()
                if (key.isNotEmpty() && say.isNotEmpty() && 
                    engine.teachWord(volume, key, say) == 0) taught++
            }
        }
        return taught
    }
    
    fun count(file: File): Int = try {
        file.useLines(Charsets.ISO_8859_1) { lines -> lines.count { it.indexOf('\t') > 0 } }
    } catch (e: Exception) { 0 }
}
```

### 4.2 Add Settings Data Class

```kotlin
data class Settings(
    val voice: Int = 0,
    val shape: Map<Int, Int> = emptyMap(),
    val sampleRateHz: Int = 22050,
    val abbreviations: Boolean = false,
    val dictionaryPaths: Map<Int, String> = emptyMap(),
    val pauses: Pauses = Pauses.ALL,
    val phrasePrediction: Boolean = false,
    val sampleRate: Int = 22050,
    val abbreviations: Boolean = false,
    val phrasePrediction: Boolean = false,
    val pauses: Pauses = Pauses.ALL,
    val digitGrouping: String = "off",
    val readingMode: String = "normal",
    val punctuationPreset: String = "none",
    val customPunctuation: String = "",
    val forceRate: Boolean = false,
    val forcePitch: Boolean = false,
    val phrasePrediction: Boolean = false,
    val quietPunct: Boolean = true,
    val optimizer: Boolean = false,
    val optimizerProfile: String = "balanced",
    val unicodeNorm: Boolean = true,
    val emojiMode: String = "announce",
    val digitGrouping: String = "off",
    val currency: Boolean = false,
    val timeDate: Boolean = false,
    val readingMode: String = "normal",
    val progSymbols: Boolean = false,
    val punctuationPreset: String = "none",
    val customPunctuation: String = "",
    val forceRate: Boolean = false,
    val forcePitch: Boolean = false,
) {
    fun dictionaryPaths(): Map<Int, String> = dictionaryPaths.filterValues { File(it).exists() }
    fun revision(): Int = dictionaryPaths.hashCode() + pauses.hashCode() + ...
}
```

---

## Phase 5: Paced Synthesis & Sentence Chunking (Week 5-6)

### 5.1 Add Paced Synthesis (300ms Lead)

```kotlin
class Pace(private val bytesPerSecond: Int) {
    private val from = System.nanoTime()
    private var bytes = 0L
    
    fun handed(more: Int) { bytes += more }
    
    fun handedMs(): Long = if (bytesPerSecond <= 0) 0 else bytes * 1000L / bytesPerSecond
    
    fun ahead(): Long = handedMs() - (System.nanoTime() - from) / 1_000_000L
    
    fun hold() {
        while (true) {
            val ahead = ahead() - LEAD_MS
            if (ahead <= 0) return
            Thread.sleep(minOf(ahead, SLICE_MS))
        }
    }
    
    companion object {
        const val LEAD_MS = 300L
        const val SLICE_MS = 50L
    }
}
```

### 5.2 Add Sentence Chunking

```kotlin
object TextPieces {
    fun split(text: String): List<String> {
        // Split at sentence boundaries (., !, ?) 
        // Preserve annotations (`` ` ``) at piece boundaries
        // Keep pieces small enough for interruptibility (~1 sentence)
    }
}
```

### 5.3 Update Streaming Speak to Use Chunking

```kotlin
// In EloQuickTtsService.onSynthesizeText:
val pieces = TextPieces.split(text)
for ((at, raw) in pieces.withIndex()) {
    val piece = prosody + Pauses.apply(raw, pauses, at == pieces.lastIndex)
    if (!target.speak(piece)) return error
    if (!pump(target, callback, pace, opening)) return
}
```

---

## Phase 5: Dictionary File UI & TTS Service (Week 6-7)

### 6.1 Add Dictionary File Picker (Settings)

```kotlin
// Settings UI: Three file pickers (main, root, abbreviation)
// - File picker intent → copy to app storage → store path
// - "Remove dictionaries" button clears all three
// - Show entry count per file (from Dictionaries.count())
```

### 6.2 Add Pauses Enum & UI

```kotlin
enum class Pauses(val key: String, val label: String) {
    KEEP("keep", "As written"),
    END_ONLY("end", "Trim end only"),
    ALL("all", "Shorten all"),
}
```

### 6.3 Update TTS Service (EloQuickTtsService)

```kotlin
// Key improvements from evvdroid:
// 1. Opening callback (don't call start() until first audio)
// 2. Sentence chunking with TextPieces
// 3. Pauses.apply() per piece
// 3. Prosody prefix (phrase prediction)
// 4. Pace.hold() for 300ms lead limiting
// 5. Dictionary reload on file change
// 5. Applied settings revision tracking
```

---

## Phase 6: Build System & Testing (Week 7-8)

### 7.1 Build System Improvements

```python
# tools/build_android.py improvements:
# 1. Per-language rule generation (not per-ABI)
# 2. Patch system (native/patches/*.patch applied to openevv submodule)
# 4. -fsigned-char flag (ARM char signedness fix)
# 5. -fPIC -fvisibility=hidden (shared object requirements)
# 6. 16KB page alignment: -Wl,-z,max-page-size=16384
# 7. Strip after build, keep unstripped for debugging
```

### 7.2 CI/CD Matrix Testing

```yaml
# .github/workflows/android.yml
strategy:
  matrix:
    abi: [arm64-v8a, armeabi-v7a, x86_64, x86]
    build_type: [release, debug]
    include:
      - abi: armeabi-v7a
        wear_os_test: true  # Only for armeabi-v7a
```

---

## Phase 7: Wear OS Support (Week 8)

### 8.1 Add armeabi-v7a to CI

```bash
# Build for Wear OS (armeabi-v7a)
python tools/build_android.py --abi armeabi-v7a --install
```

### 8.2 Wear OS Testing

```bash
# On watch:
adb pair 192.168.x.x:PAIRPORT
adb connect 192.168.x.x:PORT
./gradlew installDebug -Pevvdroid.abis=armeabi-v7a
```

---

## JNI API Additions Needed

### New JNI Methods (eloquick_jni.c)

```c
// Prosody & pauses
nativeStreamSetPhrasePrediction(handle, boolean)
nativeStreamSetPauseMode(handle, int)  // 0=keep, 1=end, 2=all

// Dictionary file loading
nativeDictLoadFile(handle, volume, path)
nativeStreamDictLoadFile(handle, volume, path)

// Abbreviations
nativeSetAbbreviations(handle, boolean)

// Preview language
nativeSetPreviewLanguage(handle, language)

// Prosody
nativeStreamSetSpeed(handle, int)
nativeStreamSetPitch(handle, int)
nativeStreamSetPhrasePrediction(handle, boolean)
```

---

## Testing Checklist

### Functional Tests
- [ ] All 10 languages synthesize correctly
- [ ] Streaming: speak → read → stop → speak works
- [ ] Prosody annotations work (rate/pitch/pause/phrase)
- [ ] Dictionary file loading (tab-separated ISO-8859-1)
- [ ] Pause shortening (3 modes)
- [ ] Phrase prediction toggle
- [ ] Abbreviations expansion toggle
- [ ] Stop mid-utterance works
- [ ] Streaming doesn't leak memory
- [ ] 20 rapid interrupt cycles work

### Performance Tests
- [ ] 300ms lead limit respected
- [ ] No audio queue buildup on rapid swipes
- [ ] 20 rapid interrupt cycles complete
- [ ] Memory stable over 100 utterances

### Device Tests
- [ ] arm64-v8a (Pixel 8)
- [ ] armeabi-v7a (Wear OS)
- [ ] x86_64 (emulator)
- [ ] x86 (emulator)

---

## Estimated Timeline

| Phase | Duration | Deliverable |
|-------|----------|-------------|
| 1: Streaming + Prosody | 2 weeks | Prosody, pauses, phrase prediction |
| 2: Dictionary files | 2 weeks | Tab-separated file loading |
| 3: Text encoding + Prosody | 2 weeks | WesternText, Polish, Japanese, SpeechRate |
| 4: Settings + Dictionary UI | 2 weeks | Settings data class, file pickers |
| 5: Paced synthesis | 2 weeks | Pace, sentence chunking, Opening callback |
| 6: TTS Service + UI | 2 weeks | EloQuickTtsService, settings UI |
| 7: Build + CI | 1 week | Patches, matrix testing |
| 8: Wear OS | 1 week | armeabi-v7a, watch testing |
| **Total** | **~14 weeks** | Production-ready TTS engine |

---

## Risk Mitigation

| Risk | Mitigation |
|------|------------|
| Engine refuses parameter changes while speaking | Use annotations (evvdroid proven) |
| Dictionary file format variations | Strict ISO-8859-1 + tab separation |
| Polish/Japanese encoding | Port EngineText/WesternText exactly |
| Stop mid-utterance crash | Abort flag pattern (evvdroid proven) |
| ARM char signedness | `-fsigned-char` flag |
| 16KB page alignment | `-Wl,-z,max-page-size=16384` |
| Wear OS testing | CI on armeabi-v7a + physical watch |

---

## Dependencies

- **openevv upstream** - engine source (submodule)
- **Android NDK r26+** - Clang, libc++
- **Python 3.8+** - rule generation, build script
- **Kotlin 1.9+** - coroutines, Flow
- **Android SDK 34** - compilation target

---

## Success Criteria

1. **All 10 languages** synthesize correctly on device
2. **Streaming works** with prosody, pauses, phrase prediction
3. **Dictionary files** load from user storage
4. **Paced synthesis** respects 300ms lead limit
5. **20 rapid interrupts** complete without crash
5. **Wear OS** builds and runs on armeabi-v7a
6. **Self-test** passes 27/27 on device
7. **TalkBack integration** works via EloQuickTtsService

---

## Next Steps

1. **Immediate**: Implement Phase 1 (Prosody annotations + pause shortening in JNI)
2. **Week 2**: Dictionary file loading (JNI + Kotlin)
3. **Week 3**: Text encoding (WesternText, Polish, Japanese)
4. **Week 4**: Settings + Dictionary file UI
5. **Week 5**: Paced synthesis + sentence chunking
6. **Week 6**: TTS Service + Settings UI
6. **Week 7**: Build system + CI matrix
7. **Week 8**: Wear OS support

---

*This plan is based on evvdroid's production-proven architecture. Each phase builds incrementally on the previous, maintaining working builds throughout.*