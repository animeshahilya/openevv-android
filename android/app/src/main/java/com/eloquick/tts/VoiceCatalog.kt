package com.eloquick.tts

import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt


/**
 * The 9 languages actually compiled into this build's native library
 * (verified against a 979-case regression matrix on 2026-09-07 - see the
 * openevv repo's docs/status.md). Language ids follow eci.h's packing: top
 * 16 bits = family, low byte = dialect.
 *
 * Polish (`lang/plpl`) is explicitly experimental upstream: the module was
 * copied from Italian's text forms and renamed, so it is IBM's Italian
 * rules/tables until something in openevv has actually replaced them.
 */
data class LanguageInfo(
    val langId: Int,
    val bcp47: String,
    val displayName: String,
    val experimental: Boolean = false,
)

val SUPPORTED_LANGUAGES: List<LanguageInfo> = listOf(
    LanguageInfo(0x10000, "en-US", "English (US)"),
    LanguageInfo(0x10001, "en-GB", "English (UK)"),
    LanguageInfo(0x20000, "es-ES", "Spanish (Spain)"),
    LanguageInfo(0x20001, "es-US", "Spanish (Latin America)"),
    LanguageInfo(0x30000, "fr-FR", "French (France)"),
    LanguageInfo(0x30001, "fr-CA", "French (Canada)"),
    LanguageInfo(0x40000, "de-DE", "German"),
    LanguageInfo(0x50000, "it-IT", "Italian"),
    LanguageInfo(0x110000, "pl-PL", "Polish", experimental = true),
)

/**
 * The engine's own 8 built-in voice presets per language, in preset-number
 * order. The names are the ones the Eloquence/ViaVoice community has called
 * these voices for decades (ported from davidacm/NVDA-IBMTTS-Driver's
 * `variants` table) - "Reed" means far more to a longtime user than "Adult
 * Male 1" does. [PRESET_CATEGORIES] carries openevv's own generic
 * descriptions (docs/api.md's own preset order, which this list's order was
 * previously wrong against - openevv's actual order is Adult Male 1, Adult
 * Female 1, Child 1, Adult Male 2, Adult Male 3, Adult Female 2, Elderly
 * Female 1, Elderly Male 1, not the Male/Male/Male/Female/Female/Child order
 * this file had before) for a subtitle, since not everyone recognizes the
 * classic names on sight.
 *
 * Cross-checked 2026-09-08 against a discrepancy in
 * Mudb0y/Apple-Eloquence-ELF's `sd_eloquence/src/eci/voices.c` (transcribed
 * from Apple's real `KonaVoicePresets.plist`), which has preset 5 as a
 * female "Flo" rather than this list's male "Glen"/"Adult Male 3". Not a bug
 * here: openevv's own docs/api.md states this exact preset order in prose,
 * and its `src/eci/lang/eci_voicetable.c` hardcodes the same eight
 * `voice_names[]` in the same order as a literal C array - both agree with
 * [PRESET_CATEGORIES] independently of NVDA's naming. And NVDA's driver
 * itself confirms "Glen" is genuinely preset 5, not just a name: its
 * `_ibmeci.setVariant()` passes the variant number straight into
 * `eciCopyVoice(handle, v, 0)` with no remapping. Apple's Kona build is
 * measurably a separate implementation of this engine (openevv's own
 * `docs/notes/apple-eloquence.md`: floating-point Klatt vs. openevv's
 * fixed-point, a different vendor's codebase) - its plist most likely
 * renumbers the eight presets for its own product rather than sharing the
 * classic ECI/ViaVoice variant numbering this app, openevv, and NVDA's
 * driver all agree on.
 *
 * Presets 3 and 6 corrected 2026-09-09 against CodeFactory's ETI-Eloquence
 * TTS (es.codefactory.eloquencetts, the actual Nuance-licensed product,
 * v1.3.3) - dexdump of its
 * `SettingPresetVoiceRadioButtonDialogPreference.onBindDialogView` shows the
 * voice-profile picker's ListView populated in this exact literal order:
 * string/preset_voice_reed, _shelly, _bobby, _rocko, _glen, _sandy,
 * _grandma, _grandpa - i.e. preset 3 is "Bobby" and preset 6 is "Sandy",
 * not the "Sandy"/"FastFlo" this list guessed at before a first-party
 * source for those two slots existed ("Shelley" also loses the extra e to
 * match). "Bobby" at preset 3 fits [PRESET_CATEGORIES]' "Child 1" far
 * better than "Sandy" did, and "Sandy" fits "Adult Female 2" (preset 6)
 * the same way "FastFlo" never confirmed it did.
 */
val PRESET_NAMES: List<String> = listOf(
    "Reed",
    "Shelly",
    "Bobby",
    "Rocko",
    "Glen",
    "Sandy",
    "Grandma",
    "Grandpa",
)

/** openevv's own generic description for each preset, same order as [PRESET_NAMES]. */
val PRESET_CATEGORIES: List<String> = listOf(
    "Adult Male 1",
    "Adult Female 1",
    "Child 1",
    "Adult Male 2",
    "Adult Male 3",
    "Adult Female 2",
    "Elderly Female 1",
    "Elderly Male 1",
)

fun presetName(preset: Int): String = PRESET_NAMES.getOrNull(preset - 1) ?: "Voice $preset"

fun presetCategory(preset: Int): String = PRESET_CATEGORIES.getOrNull(preset - 1) ?: ""

/** The engine's built-in preset count - the single range every preset
 * validation/clamp goes through, instead of each call site re-stating
 * `1..PRESET_NAMES.size` (or the native side's own `1..8`) separately. */
val PRESET_COUNT: Int = PRESET_NAMES.size

fun isValidPreset(preset: Int): Boolean = preset in 1..PRESET_COUNT

/** Clamps to a loadable preset: the native side silently skips
 * `eciCopyVoice` for an out-of-range preset, which would leave a reused
 * engine slot speaking with whatever voice the previous utterance left
 * behind - never hand it one. */
fun clampPreset(preset: Int): Int = preset.coerceIn(1, PRESET_COUNT)

fun languageInfo(langId: Int): LanguageInfo? = SUPPORTED_LANGUAGES.firstOrNull { it.langId == langId }

/** Stable persisted key for a (language, preset) pair, e.g. "65536:1". */
fun voiceKey(langId: Int, preset: Int): String = "$langId:$preset"

fun localeFor(bcp47: String): Locale = Locale.forLanguageTag(bcp47)

/**
 * Coarse language family for gating per-language text fixes (see
 * EngineTextFixes EXTRA_FIXES) - deliberately coarser than [LanguageInfo]:
 * the fixes only care which rule set applies, not which dialect.
 */
enum class LanguageFamily { ENGLISH, SPANISH, FRENCH, GERMAN, OTHER }

fun familyForLangId(langId: Int): LanguageFamily = when (langId ushr 16) {
    0x1 -> LanguageFamily.ENGLISH
    0x2 -> LanguageFamily.SPANISH
    0x3 -> LanguageFamily.FRENCH
    0x4 -> LanguageFamily.GERMAN
    else -> LanguageFamily.OTHER
}

fun familyForBcp47(bcp47: String): LanguageFamily = when (bcp47.substringBefore('-').lowercase(Locale.ROOT)) {
    "en" -> LanguageFamily.ENGLISH
    "es" -> LanguageFamily.SPANISH
    "fr" -> LanguageFamily.FRENCH
    "de" -> LanguageFamily.GERMAN
    else -> LanguageFamily.OTHER
}

/**
 * Validates a persisted/requested [langId] the way NVDA's driver learned to
 * (its issue #147: a stale or corrupt language value must never stop the
 * engine from loading). Falls back to the device locale's match, then
 * US English, then whatever language is first - in that order, so an
 * invalid value degrades to a nearby voice instead of a load failure.
 */
fun validLangIdOrFallback(langId: Int, deviceLanguage: String?, deviceCountry: String?): Int {
    if (SUPPORTED_LANGUAGES.any { it.langId == langId }) return langId
    return matchLanguage(deviceLanguage, deviceCountry)?.langId
        ?: SUPPORTED_LANGUAGES.firstOrNull { it.bcp47 == "en-US" }?.langId
        ?: SUPPORTED_LANGUAGES.first().langId
}

/**
 * True if [loc]'s language is [code] - checked against both its ISO 639-1
 * 2-letter form ("en") and its ISO 639-2 3-letter form ("eng"). Every
 * caller inside this app (the device locale, langId validation) passes the
 * 2-letter form, but the Android TTS framework itself does not: per
 * TextToSpeechService's own documented contract, onIsLanguageAvailable/
 * onLoadLanguage and legacy SynthesisRequest.getLanguage() calls always
 * arrive as ISO 639-2 (String.getISO3Language()) - confirmed by this same
 * service's own mCurrentLang/mCurrentCountry defaults ("eng"/"USA"), and by
 * TalkBack's own client failing to "restore TTS locale to en_US" against a
 * build that only ever compared 2-letter codes. Locale.getISO3Language()
 * can throw for a locale with no defined 3-letter form, hence the guard -
 * none of this app's own languages hit that, but a locale is a locale.
 */
private fun languageMatches(loc: Locale, code: String): Boolean =
    loc.language.equals(code, ignoreCase = true) ||
        runCatching { loc.isO3Language }.getOrNull()?.equals(code, ignoreCase = true) == true

/** As [languageMatches], but for the country/region part - ISO 3166-1
 * alpha-2 ("US") vs. alpha-3 ("USA"). Internal (not private) so
 * [com.eloquick.service.EloquenceTtsService.onIsLanguageAvailable] can
 * apply the same ISO-3-aware rule instead of a raw equals that would
 * downgrade every framework "USA" query to LANG_AVAILABLE. */
internal fun countryMatches(loc: Locale, code: String): Boolean =
    loc.country.equals(code, ignoreCase = true) ||
        runCatching { loc.isO3Country }.getOrNull()?.equals(code, ignoreCase = true) == true

/**
 * Best-effort match from an Android [Locale] (as requested by
 * [android.speech.tts.TextToSpeechService]/framework TTS clients) to one of
 * our [SUPPORTED_LANGUAGES]. Matches language+country first, then falls back
 * to a language-only match (e.g. "en-IN" resolves to en-US).
 */
fun matchLanguage(language: String?, country: String?): LanguageInfo? {
    if (language.isNullOrBlank()) return null
    val lang = language.lowercase(Locale.US)
    val ctry = country?.uppercase(Locale.US).orEmpty()
    val exact = SUPPORTED_LANGUAGES.firstOrNull {
        val loc = localeFor(it.bcp47)
        languageMatches(loc, lang) && ctry.isNotEmpty() && countryMatches(loc, ctry)
    }
    if (exact != null) return exact
    return SUPPORTED_LANGUAGES.firstOrNull { languageMatches(localeFor(it.bcp47), lang) }
}

/** The engine's own real-world-unit ranges for speed (words per minute) and
 * pitch (hertz) - the single source of truth for both the Tuning screen's
 * sliders and [AppPreferences.setRealWorldUnits]'s conversion when the
 * real-world-units toggle flips, so the two can never quietly disagree
 * about what "real-world" means. */
val SPEED_REAL_WORLD_RANGE = 70f..450f
val PITCH_REAL_WORLD_RANGE = 40f..422f
val RAW_TUNING_RANGE = 0f..100f

/** Maps [value] from one linear range to another, clamping to [from] first
 * so an out-of-range input can't produce an out-of-range output - used to
 * carry the *same perceived* speed/pitch across the real-world-units
 * toggle (a raw 50 becomes roughly the WPM/Hz at the middle of the
 * real-world range, not a jarring jump to a differently-scaled 50). */
fun convertTuningRange(value: Int, from: ClosedFloatingPointRange<Float>, to: ClosedFloatingPointRange<Float>): Int {
    val t = ((value - from.start) / (from.endInclusive - from.start)).coerceIn(0f, 1f)
    return (to.start + t * (to.endInclusive - to.start)).roundToInt()
}

/**
 * NVDA-IBMTTS-Driver's "Rate boost": multiplies the actual engine speed
 * value by 1.6x beyond whatever the UI shows, because Eloquence's top speed
 * at raw settings sounds slow to longtime users of this engine family - one
 * of the most requested things about it. Applies to whichever units [speed]
 * is already in (raw ~0-250 engine units, or real-world words-per-minute
 * under [TtsConfig.realWorldUnits]) - the caller decides which clamp applies.
 *
 * The -1 sentinel ("use the preset's own value") is left untouched: boosting
 * "use the default" would be nonsensical. [multiplier] is that same idea
 * generalized from its original fixed 1.6x into a user-adjustable range: 1x
 * is "off" (this app's own choice, not upstream's - upstream only ever had
 * on/off), up to 3x for listeners who want to go well past the engine's own
 * normal ceiling. The clamp bounds are the engine's actual hard limit in
 * each unit system, not anything specific to 1.6x - they apply the same way
 * whatever the multiplier is.
 */
/** The engine's actual hard speed ceilings per unit system - applied to
 * whatever the multiplier produces, not specific to any one multiplier. */
const val ENGINE_SPEED_MAX_RAW = 250
const val ENGINE_SPEED_MAX_WPM = 900

fun applyRateBoost(speed: Int, multiplier: Float, realWorldUnits: Boolean): Int {
    if (multiplier <= 1f || speed < 0) return speed
    val boosted = (speed * multiplier).roundToInt()
    val max = if (realWorldUnits) ENGINE_SPEED_MAX_WPM else ENGINE_SPEED_MAX_RAW
    return boosted.coerceIn(0, max)
}

/**
 * Scales the app's own configured speed/pitch by the multiplier a *calling
 * app* requested through the platform's own TTS API
 * (`SynthesisRequest.getSpeechRate()`/`getPitch()`, 100 = "no change asked
 * for") - e.g. TalkBack's own speech-rate slider, which every other engine
 * on the platform honors and this one used to never even read. A caller
 * asking for nothing in particular (100, or an invalid value some callers
 * send as 0) is a no-op, same as today.
 *
 * [value] of -1 ("voice default", never touched by the user in this app's
 * own Tuning screen) is left at -1 rather than multiplying an unknown
 * baseline - there is no numeric "normal" to scale from until the user has
 * actually set one. This mirrors [applyRateBoost]'s own "never boosts the
 * default" rule, and composes with it the same way: this scales the user's
 * own slider value first, in the slider's own range, and [applyRateBoost]'s
 * separate multiplier (and wider ceiling) still applies on top of the
 * result exactly as it did before this existed.
 */
fun applyCallerRate(value: Int, requestSpeechRate: Int, realWorldUnits: Boolean): Int {
    if (value < 0 || requestSpeechRate <= 0 || requestSpeechRate == 100) return value
    val range = if (realWorldUnits) SPEED_REAL_WORLD_RANGE else RAW_TUNING_RANGE
    val scaled = (value * (requestSpeechRate / 100f)).roundToInt()
    return scaled.coerceIn(range.start.roundToInt(), range.endInclusive.roundToInt())
}

/** Pitch's counterpart to [applyCallerRate] - same reasoning, same -1 rule,
 * just the pitch slider's own range instead of speed's. */
fun applyCallerPitch(value: Int, requestPitch: Int, realWorldUnits: Boolean): Int {
    if (value < 0 || requestPitch <= 0 || requestPitch == 100) return value
    val range = if (realWorldUnits) PITCH_REAL_WORLD_RANGE else RAW_TUNING_RANGE
    val scaled = (value * (requestPitch / 100f)).roundToInt()
    return scaled.coerceIn(range.start.roundToInt(), range.endInclusive.roundToInt())
}

/**
 * Best-fit fallback for characters windows-1252 can't represent directly,
 * for the letters that don't decompose into a plain base letter via Unicode
 * NFD at all (a distinct base letter, not an accent - Ł isn't "L plus a
 * mark"). Small and hand-picked rather than an exhaustive transliteration
 * table: covers the common Central/European cases most likely to actually
 * appear - Polish is one of this app's nine languages and needs several of
 * these (though [encodeForEngine]'s NFD pass below already handles most of
 * Polish's own diacritics - ą, ć, ę, ń, ó, ś, ź, ż all decompose to a plain
 * base letter cleanly; only ł/Ł land here).
  *
  * Only single letters Windows-1252 genuinely can't encode: Æ/Œ (and their
  * lowercase forms) encode directly in Windows-1252 (0xC6/0xE6/0x8C/0x9C),
  * so they never reach this map - entries for them here would be dead.
  * Every replacement below is exactly one character, which is what
  * [engineByteToCharOffsets]'s one-byte-per-entry accounting assumes.
  *
  * Ported from a lesson in davidacm/NVDA-IBMTTS-Driver's changelog: it once
 * switched from Windows' own best-fit MBCS conversion to plain cp1252 and
 * had to "restore" best-fit mapping afterward, because plain cp1252 alone
 * silently turns e.g. "Đ" into "?" instead of a readable "D". Android has no
 * equivalent to Win32's WideCharToMultiByte best-fit flag, so this
 * approximates it instead.
 */
private val BEST_FIT_FALLBACK: Map<Char, String> = mapOf(
    'Ł' to "L", 'ł' to "l",
    'Đ' to "D", 'đ' to "d",
    'Ø' to "O", 'ø' to "o",
    'Þ' to "T", 'þ' to "t",
    'ʼ' to "'", 'ʻ' to "'", '′' to "'",
    '‐' to "-", '‑' to "-",
)

/**
 * Encodes [text] to the raw bytes the native engine expects - windows-1252,
 * see [WINDOWS_1252]. A character not directly representable there is
 * approximated rather than dropped to '?': first by NFD-decomposing it and
 * keeping only the base letter (handles ordinary accents - é, ñ, ü, and most
 * of Polish's own diacritics), then by [BEST_FIT_FALLBACK] for the letters
 * that aren't "a base letter plus an accent" at all. Only a character that
 * survives neither step still falls through to windows-1252's own default
 * ('?') - the rare last resort this used to be the *only* behavior for.
 */
fun encodeForEngine(text: String, langId: Int): ByteArray = encodeForEngineInternal(text, langId).bytes

/**
 * The byte-offset -> char-offset map for what [encodeForEngine] would
 * return for the same [text] - one entry per output byte, plus a final
 * sentinel entry ([text].length) for a byte offset landing exactly at the
 * end. Exists for [com.eloquick.service.EloquenceTtsService.onIndexMark],
 * the one caller that turns a byte offset back into a position in a Kotlin
 * `String`: the native engine only ever reports an index mark as a byte
 * offset into the buffer [encodeForEngine] produced, and that is *not* the
 * same number as a char offset into [text] the moment a supplementary-plane
 * code point (two input `Char`s collapse to *one* output '?' byte) is
 * involved - or, in principle, any future [BEST_FIT_FALLBACK] entry whose
 * replacement is more than one character (none of the current ones are:
 * windows-1252 already encodes Æ/Œ directly, one byte each, before that map
 * is even consulted, and every entry that *is* reached - Ł, Đ, Ø, Þ and
 * their lowercase forms - happens to replace one character with exactly
 * one). Treating the native offset as a char offset directly, as this app
 * used to, silently drifts
 * TalkBack's highlight range onto the wrong characters for any utterance
 * containing one of these, without ever throwing (see
 * [com.eloquick.service.clampHighlightEnd]'s own doc
 * comment for the *other*, already-handled source of imprecision this
 * doesn't fix - the text-rewriting pipeline changing length before this
 * function ever sees it).
 */
fun engineByteToCharOffsets(text: String, langId: Int): IntArray = encodeForEngineInternal(text, langId).byteToChar

/**
 * Both [encodeForEngine] and [engineByteToCharOffsets] for the same [text]/[langId], from a single
 * pass - for a caller (just [com.eloquick.service.EloquenceTtsService], on its
 * per-chunk synthesis path) that needs both and would otherwise redo the same NFD/best-fit encoding
 * work twice per chunk by calling the two single-purpose functions back to back.
 */
fun encodeForEngineWithOffsets(text: String, langId: Int): EncodedForEngine = encodeForEngineInternal(text, langId)

class EncodedForEngine(val bytes: ByteArray, val byteToChar: IntArray)

private val WIN1252_EXTRA_MAP: Map<Char, Byte> = mapOf(
    '€' to 0x80.toByte(),
    '‚' to 0x82.toByte(),
    'ƒ' to 0x83.toByte(),
    '„' to 0x84.toByte(),
    '…' to 0x85.toByte(),
    '†' to 0x86.toByte(),
    '‡' to 0x87.toByte(),
    'ˆ' to 0x88.toByte(),
    '‰' to 0x89.toByte(),
    'Š' to 0x8A.toByte(),
    '‹' to 0x8B.toByte(),
    'Œ' to 0x8C.toByte(),
    'Ž' to 0x8E.toByte(),
    '‘' to 0x91.toByte(),
    '’' to 0x92.toByte(),
    '“' to 0x93.toByte(),
    '”' to 0x94.toByte(),
    '•' to 0x95.toByte(),
    '–' to 0x96.toByte(),
    '—' to 0x97.toByte(),
    '˜' to 0x98.toByte(),
    '™' to 0x99.toByte(),
    'š' to 0x9A.toByte(),
    '›' to 0x9B.toByte(),
    'œ' to 0x9C.toByte(),
    'ž' to 0x9E.toByte(),
    'Ÿ' to 0x9F.toByte(),
)

private fun windows1252DirectByte(ch: Char): Byte? {
    val code = ch.code
    if (code in 32..126) return code.toByte()
    if (code in 0xA0..0xFF) return code.toByte()
    return WIN1252_EXTRA_MAP[ch]
}

private fun encodeForEngineInternal(text: String, langId: Int): EncodedForEngine {
    var bytes = ByteArray(text.length + 16)
    var byteToChar = IntArray(text.length + 17)
    var byteCount = 0

    fun ensureCapacity(extra: Int) {
        if (byteCount + extra >= bytes.size) {
            val newCap = maxOf(bytes.size * 2, byteCount + extra + 16)
            bytes = bytes.copyOf(newCap)
            byteToChar = byteToChar.copyOf(newCap + 1)
        }
    }

    fun appendByte(b: Byte, charIndex: Int) {
        ensureCapacity(1)
        bytes[byteCount] = b
        byteToChar[byteCount] = charIndex
        byteCount++
    }

    var i = 0
    val textLen = text.length
    while (i < textLen) {
        val cp = text.codePointAt(i)
        // Fast-path for printable ASCII and whitespace (guaranteed valid in Windows-1252)
        if (cp in 32..126 || cp == 9 || cp == 10 || cp == 13) {
            appendByte(cp.toByte(), i)
            i++
            continue
        }
        // Null characters and unprintable ASCII control codes (0..31 except \t, \n, \r).
        // A null character in the native C buffer prematurely truncates eciAddText,
        // misaligning eciInsertIndex sentence marks and triggering the "Null byte
        // corruption in eciInsertIndex" crash reported in NVDA-IBMTTS-Driver.
        if (cp < 32) {
            appendByte(' '.code.toByte(), i)
            i++
            continue
        }
        val charCount = Character.charCount(cp)
        // Zero-width formatting characters (ZWSP, ZWNJ, ZWJ, word joiner, BOM, soft hyphen).
        // Transparently skip them so Eloquence never vocalizes "question mark" in the middle
        // of words on modern web pages, Wikipedia, or markdown documents.
        if (cp in 0x200B..0x200D || cp == 0x2060 || cp == 0xFEFF || cp == 0x00AD) {
            i += charCount
            continue
        }
        if (charCount > 1) {
            // A supplementary-plane code point (most emoji, among plenty
            // else) is never representable in Windows-1252 and never
            // NFD-decomposes to one that is. One '?' for the whole code point.
            appendByte('?'.code.toByte(), i)
            i += charCount
            continue
        }
        val ch = cp.toChar()
        val direct = windows1252DirectByte(ch)
        if (direct != null) {
            appendByte(direct, i)
            i += charCount
            continue
        }
        val stripped = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
            .filter { Character.getType(it).toByte() != Character.NON_SPACING_MARK }
        if (stripped.isNotEmpty() && stripped.all { windows1252DirectByte(it) != null }) {
            ensureCapacity(stripped.length)
            for (sc in stripped) {
                bytes[byteCount] = windows1252DirectByte(sc)!!
                byteToChar[byteCount] = i
                byteCount++
            }
            i += charCount
            continue
        }
        val fallback = BEST_FIT_FALLBACK[ch]
        if (fallback != null) {
            ensureCapacity(fallback.length)
            for (fc in fallback) {
                bytes[byteCount] = (windows1252DirectByte(fc) ?: '?'.code.toByte())
                byteToChar[byteCount] = i
                byteCount++
            }
        } else {
            appendByte('?'.code.toByte(), i)
        }
        i += charCount
    }
    byteToChar[byteCount] = textLen // sentinel: a byte offset exactly at the end
    return EncodedForEngine(bytes.copyOf(byteCount), byteToChar.copyOf(byteCount + 1))
}

/** Short spoken sample per language, used for the voice-preview button. */
fun sampleTextFor(bcp47: String): String = when {
    bcp47.startsWith("en") -> "Hello, this is a preview of this voice."
    bcp47.startsWith("es") -> "Hola, esta es una vista previa de esta voz."
    bcp47.startsWith("fr") -> "Bonjour, ceci est un aperçu de cette voix."
    bcp47.startsWith("de") -> "Hallo, dies ist eine Vorschau dieser Stimme."
    bcp47.startsWith("it") -> "Ciao, questa è un'anteprima di questa voce."
    bcp47.startsWith("pl") -> "Cześć, to jest podgląd tego głosu."
    else -> "Hello, this is a preview of this voice."
}

/**
 * Resolves an `ACTION_GET_SAMPLE_TEXT` request to a sample string. The
 * platform sends the engine's own `onGetLanguage()` output back as plain
 * `language`/`country`/`variant` extras (3-letter codes like "eng"/"USA"),
 * so this goes through the same [matchLanguage] every synthesis request
 * uses - including its language-only fallback ("en-IN" still gets the
 * English sample). Unknown or missing locale returns the English default
 * rather than nothing: a CANCELED result leaves system TTS settings with
 * no "listen to example" at all. Pure function for unit tests; the
 * activity itself is just intent plumbing.
 */
fun sampleTextForRequest(language: String?, country: String?): String =
    matchLanguage(language, country)?.let { sampleTextFor(it.bcp47) } ?: sampleTextFor("en")
