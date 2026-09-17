# EloQuick for Android

[![Android Build](https://github.com/animeshahilya/openevv-android/actions/workflows/android.yml/badge.svg)](https://github.com/animeshahilya/openevv-android/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android 15+ Ready](https://img.shields.io/badge/Android-15%2B-green.svg)](https://developer.android.com/about/versions/15)

**IBM Eloquence / Embedded ViaVoice TTS as portable C, built for Android.**
NDK + CMake, 16KB-page clean (Android 15+), `arm64-v8a` / `armeabi-v7a` / `x86_64` / `x86`.
Upstream engine: [Mudb0y/openevv](https://github.com/Mudb0y/openevv).

---

## Scope

This fork is **Android-only**. The blessed build paths are:
- `tools/build_android.py` — Python multi-ABI builder (primary)
- `CMakeLists.txt` — Modern CMake for Gradle/CMake integration
- `android/` — JNI bridge, Kotlin bindings, and example app

The inherited desktop tree (`Makefile`, `win/`, `speechd/`, `nvda/`, `reference/`, desktop docs) stays for provenance and upstream merges; it is **not built, tested, or supported** here.

---

## Quick Start

### Prerequisites
- **Android NDK r26+** (r27b recommended) — set `ANDROID_NDK_HOME` or `ANDROID_NDK_ROOT`
- **Python 3.8+**

### Build with Python (Recommended)

```bash
# Default: arm64-v8a release build with all 10 languages
python tools/build_android.py

# Specific ABI
python tools/build_android.py --abi arm64-v8a
python tools/build_android.py --abi armeabi-v7a
python tools/build_android.py --abi x86_64
python tools/build_android.py --abi x86

# All ABIs at once
python tools/build_android.py --abi all

# Trim languages for smaller APK (e.g., English + German only)
python tools/build_android.py --langs enus,dede

# Debug build (unstripped, -O0 -g, no LTO)
python tools/build_android.py --debug --abi arm64-v8a

# Force clean rebuild
python tools/build_android.py --clean --abi arm64-v8a
```

### Build with CMake (for Gradle Integration)

```bash
# Configure
cmake -B build/cmake \
  -DCMAKE_BUILD_TYPE=Release \
  -DOPENEVV_LANGS="enus;dede" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake

# Build
cmake --build build/cmake -j$(nproc)
```

---

## Outputs

Per ABI under `build/android/<abi>/`:

| File | Description |
|------|-------------|
| `libeloquick.so` | **Primary** shared library with JNI bridge + ECI API. Load this one. |
| `libopenevv.so` | Compatibility mirror (same engine, **no JNI**, own SONAME). |
| `eloquick` | Standalone CLI executable (PIE). |
| `evv` | Copy of `eloquick` (argv[0]-aware for backward compat). |

**The engine compiles once per ABI; all four binaries link the same objects.**

---

## Integration

### CMake + Gradle (Modern)

```cmake
# In your app's CMakeLists.txt
add_subdirectory(path/to/openevv-android ${CMAKE_CURRENT_BINARY_DIR}/openevv)

# Link against the PRIMARY library (has JNI bridge)
target_link_libraries(your_tts_jni_lib PRIVATE eloquick)
```

```kotlin
// build.gradle.kts
android {
    defaultConfig {
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }
    externalNativeBuild {
        cmake {
            path "src/main/cpp/CMakeLists.txt"
            version "3.22.1"
        }
    }
}
```

### Language Trimming (APK Size)

```cmake
# In your CMakeLists.txt BEFORE add_subdirectory
set(OPENEVV_LANGS "enus;dede" CACHE STRING "" FORCE)
// or via command line:
// cmake -DOPENEVV_LANGS="enus;dede" ...
```

| Configuration | Approx. libeloquick.so (arm64) |
|---------------|--------------------------------|
| 10 languages (default) | ~2.8 MB |
| 2 languages (enus, dede) | ~1.2 MB |
| 1 language (enus only) | ~0.8 MB |

---

## Kotlin / JNI API

```kotlin
// Minimal usage
class EloQuickEngine {
    init { System.loadLibrary("eloquick") }

    @JvmStatic external fun nativeCreate(language: Int): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeGetLanguages(): IntArray
    @JvmStatic external fun nativeSynth(handle: Long, text: String): ShortArray?
    // ... 50+ more methods (see android/app/src/main/java/com/eloquick/tts/EloQuickEngine.kt)
}
```

### Quick Example

```kotlin
val engine = EloQuickEngine()
val handle = engine.create(EloQuickEngine.Language.EN_US)  // or 0 for default
if (handle != 0L) {
    try {
        // Synchronous synthesis (blocking, returns 11025 Hz mono PCM)
        val pcm = engine.synth(handle, "Hello from EloQuick!")
        playPcm(pcm)  // Your AudioTrack code here

        // Or streaming (non-blocking, stoppable)
        val stream = engine.createStream(EloQuickEngine.Language.EN_US)
        engine.streamSpeak(stream, "Long text that can be interrupted...")
        engine.streamReadFlow(stream).collect { chunk ->
            audioTrack.write(chunk, 0, chunk.size)
        }
        engine.destroyStream(stream)
    } finally {
        engine.destroy(handle)
    }
}
```

### Full Kotlin API (with Coroutines/Flow)

See [`android/app/src/main/java/com/eloquick/tts/EloQuickEngine.kt`](android/app/src/main/java/com/eloquick/tts/EloQuickEngine.kt) for:
- **Blocking API**: `synth()`, `setParam()`, `copyVoice()`, `dictTeach()`, etc.
- **Streaming API**: `createStream()`, `streamSpeak()`, `streamRead()`, `streamStop()`
- **Flow API**: `streamReadFlow()` — Kotlin `ReceiveChannel` of PCM chunks
- **High-level helpers**: `speakAndPlay()`, `streamSpeakAndPlay()` — auto AudioTrack
- **TextToSpeechService**: `EloQuickTtsService` — drop-in system TTS service
- **Voice presets**: 8 voices (Reed, Bobby, Grandma, Grandpa, Kathy, Princess, Huge, Tiny)

---

## Contracts (Must Follow)

1. **Load exactly ONE `.so` per process**: `System.loadLibrary("eloquick")`.
   `libopenevv.so` is a pure-ECI compat mirror with **no JNI** (avoids duplicate symbols + RAM waste).

2. **Resolve languages first**: Call `nativeGetLanguages()` → pick an ID → `nativeCreate(id)`.
   Returns `0` on unknown language; always check and call `nativeDestroy()` when done.

3. **`nativeSynth` is bounded**:
   - Max 64 KB input text
   - Max ~2 min PCM output
   - 30 s drain timeout
   - **Long or stoppable speech → use streaming API** (`nativeStreamSpeak/Read/Stop`)

4. **Encoding contract** (Java strings are UTF-8):
   - `plpl` (Polish): UTF-8 natively
   - `jajp` (Japanese): Romanized input only (via `rom/jajp`)
   - All others: Single-byte Latin-1. Convert non-ASCII before `eciAddText`.

---

## Languages

Ten pre-linked languages (trim with `--langs` / `-DOPENEVV_LANGS`):

| Code | Language | Region | Notes |
|------|----------|--------|-------|
| `enus` | English | US | Default |
| `engb` | English | UK | |
| `dede` | German | Germany | |
| `eses` | Spanish | Spain | |
| `esus` | Spanish | US | |
| `frca` | French | Canada | |
| `frfr` | French | France | |
| `itit` | Italian | Italy | |
| `plpl` | Polish | Poland | UTF-8 native |
| `jajp` | Japanese | Japan | Romanized input via `rom/jajp` |

---

## On-Device Testing

```bash
# 1. Build debug (unstripped)
python tools/build_android.py --abi arm64-v8a --debug

# 2. Run device test gate (pushes binaries, runs full validation)
python tools/test_device.py --abi arm64-v8a

# 3. Build & install debug APK with self-test
python tools/build_debug_apk.py --abi arm64-v8a --install
adb shell am start -n com.eloquick.debug/com.eloquick.debug.MainActivity --ez selftest true
adb logcat -s EQTEST
```

**Self-test covers**: all 10 languages, bad-language refusal, voices 1–8, streaming drain/stop, dictionary teach/lookup/forget/load, heteronym on/off, sample rates 22050/fallback. Proven on Pixel 8: 26/0 pass.

---

## ADB / Termux Usage

```bash
# Push CLI to device
adb push build/android/arm64-v8a/evv /data/local/tmp/evv
adb shell chmod +x /data/local/tmp/evv

# Synthesize to WAV
adb shell "/data/local/tmp/evv -o /data/local/tmp/hello.wav 'Hello from OpenEVV on Android.'"

# Pull audio
adb pull /data/local/tmp/hello.wav .

# List languages & pick one
adb shell "/data/local/tmp/evv -L list"
adb shell "/data/local/tmp/evv -L 0x20001 -o /data/local/tmp/de.wav 'Guten Tag.'"
```

**Termux**:
```bash
cp build/android/arm64-v8a/evv $PREFIX/bin/evv
cp cli/openevv-say $PREFIX/bin/openevv-say
OPENEVV_EVV=$PREFIX/bin/evv openevv-say -s 60 "Hello from OpenEVV."
```

---

## Architecture Highlights

| Feature | Implementation |
|---------|----------------|
| **Formant Synthesis** | Register-cached resonators, `-O3` auto-vectorized DSP loops |
| **Memory Arena** | `-DEVV_ARENA=1` — zero runtime allocs during synthesis |
| **16KB Page Alignment** | `-Wl,-z,max-page-size=16384` — Android 15+ compliant |
| **ThinLTO** | Probed at configure/build time, enabled when supported |
| **ICF** | `--icf=all` — identical code folding (NDK r26+) |
| **Symbol Visibility** | `-fvisibility=hidden` — only ECI + JNI exported |
| **Section GC** | `-ffunction-sections -fdata-sections -Wl,--gc-sections` |
| **Hardening** | `-fstack-protector-strong -D_FORTIFY_SOURCE=2 -Wl,-z,relro,-z,now` |
| **Single Compile** | Object library pattern — engine compiles **once**, links **4×** |

---

## Provenance & Licensing

- **Engine, tools, docs**: MIT (see `LICENSE`)
- **`src/klatt_tables.c`, `src/eci_xmltok_tables.c`**: IBM data (not MIT)
- **All `lang/`**: Transcribed IBM language data (not MIT-licensable)
- See `NOTICE` for full attribution

**No SDK, DLL, or Wine needed to build.** Community fixes folded in from upstream family:
`stormdragon2976`, `Eagalon`, `beyondsighttech`, `evvdroid`, `eloquence-revived` — credited in `docs/android.md`.

---

## Upstream Desktop Reference (Not Supported Here)

Desktop build, Speech Dispatcher module, Windows `eci.dll`/`evvspeak.exe`, SSML, and the 979-case `test/matrix.sh` gate live upstream. See:
- `docs/building.md` — Build system details
- `docs/testing.md` — Test suite (979 cases, 20k words)
- `docs/windows.md` — Windows build
- `docs/speech-dispatcher.md` — Linux Speech Dispatcher module

---

## Contributing

1. Fork & create a feature branch
2. Run `python tools/build_android.py --abi all --clean` to verify builds
3. Run `python tools/test_device.py --abi arm64-v8a` if you have a device
4. Ensure `make matrix` passes upstream (if changing engine code)
5. Submit PR with clear description of changes

---

## Support

- **Issues**: [GitHub Issues](https://github.com/animeshahilya/openevv-android/issues)
- **Discussions**: [GitHub Discussions](https://github.com/animeshahilya/openevv-android/discussions)
- **Upstream**: [Mudb0y/openevv](https://github.com/Mudb0y/openevv)