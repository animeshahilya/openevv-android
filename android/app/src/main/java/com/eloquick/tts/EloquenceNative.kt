package com.eloquick.tts

import android.util.Log

/**
 * Thin JNI surface over the OpenEVV native engine (`libopenevv_jni.so`,
 * transitively pulling in `libopenevv.so`). The native side (evv_tts_jni.c)
 * caches engine instances internally, so there is no instance lifecycle to
 * manage from Kotlin - just call [nativeSynthesize] per utterance.
 *
 * [nativeInit] must be called once (idempotent - safe to call again) before
 * [nativeGetLanguages] or [nativeSynthesize]. Done eagerly in
 * [com.eloquick.EloquenceRevivedApp.onCreate] and again
 * defensively in the TTS service's onCreate, since either can be the first
 * process entry point depending on how Android starts this app.
 */
object EloquenceNative {
    private const val TAG = "EloquenceNative"

    /**
     * What "Standard" audio quality (a persisted [sampleRateHz] of 0) actually
     * resolves to. Not the engine's own bare native rate (11025) any more -
     * that raw a rate, handed straight to [android.speech.tts.SynthesisCallback.start]
     * with no resampling done on this app's own side, leaves the *entire*
     * ~4.4x jump up to a typical 48kHz output mix rate to whatever resampler
     * the caller's audio pipeline happens to have. That's out of this app's
     * control (see [AudioOptimizer]'s own doc comment on why device-specific
     * behavior has to be handled in software here) and was traced to a real
     * bug report: one mid-range Samsung phone producing an audibly breathy/
     * airy voice at every-value-default settings, every other device fine -
     * consistent with a weak platform resampler struggling with that large a
     * ratio, since the DSP path itself (AudioOptimizer, the engine's own
     * synthesis) is bit-for-bit identical across devices. Passing this value
     * into [nativeSynthesize]'s own sampleRateHz instead of a bare 0 makes
     * the engine do the upsample itself, with its own known-quality "sinc"
     * method, to a rate a platform resampler only has to scale by ~2.2x
     * rather than ~4.4x - shrinking the room for a bad resampler to be heard
     * without changing perceived latency (Audio quality already claims, and
     * this doesn't change, that every tier starts speech equally fast).
     */
    const val DEFAULT_SAMPLE_RATE_HZ = 22050

    /**
     * Resolves a persisted [sampleRateHz] (0 = "Standard" quality) to the
     * rate actually handed to the engine - the single source of truth for a
     * rule previously copied in three places (the TTS service, the in-app
     * preview player, and the native side's own `> 0 else 11025` fallback,
     * which stays as the last-resort contract for any caller that still
     * passes 0 straight through JNI).
     */
    fun resolveSampleRateHz(requested: Int): Int =
        if (requested > 0) requested else DEFAULT_SAMPLE_RATE_HZ

    @Volatile
    var isLoaded: Boolean = false
        private set

    init {
        try {
            // Only the JNI wrapper is loaded explicitly - libopenevv.so is a
            // transitive dependency the dynamic linker resolves on its own
            // since both .so files live in the same jniLibs directory.
            System.loadLibrary("openevv_jni")
            isLoaded = true
            Log.i(TAG, "libopenevv_jni.so loaded")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load libopenevv_jni.so", t)
        }
    }

    interface AudioConsumer {
        /** Return false to cancel synthesis mid-utterance. */
        fun onAudioChunk(data: ByteArray, length: Int): Boolean

        /** Fires once per sentence boundary reached (approximate, not per-word). */
        fun onIndexMark(charOffset: Int)
    }

    external fun nativeInit(): Boolean

    /** e.g. 0x10000 = US English, per eci.h's family/dialect packing. */
    external fun nativeGetLanguages(): IntArray

    /**
     * Asks the engine what it would say for [text] on its own - phonemes and
     * stress marks in the engine's own annotation syntax (openevv's
     * docs/api.md: `"Hello there."` becomes `` `2 `[.2hE.1lo]`0 `[.1Der]. ``),
     * not sound. Null on failure (empty [text], a language the engine
     * refuses, or the underlying `eciGeneratePhonemes` call itself refusing).
     * [text] is already-encoded bytes, same contract as [nativeSynthesize]'s
     * own - use [encodeForEngine].
     *
     * Reflects the engine's un-overridden default even for a word that
     * already has a Pronunciation dictionary entry - see the native side's
     * own doc comment for why that's deliberate, not an oversight.
     */
    external fun nativeGeneratePhonemes(langId: Int, text: ByteArray): String?

    /**
     * Loads a user dictionary file into the engine's dictionary volume.
     *
     * The file must be a tab-separated text file (key<TAB>translation per line)
     * encoded in Windows-1252. The engine expects this format per IBM's
     * dictionary specification.
     *
     * @param langId the engine language ID (e.g., 0x10000 for US English)
     * @param volume the dictionary volume: 0=Main, 1=Root, 2=Abbreviation
     * @param path absolute path to the dictionary file
     * @return 0 on success, non-zero error code (see eci.h ECIError codes)
     */
    external fun nativeLoadDictFile(langId: Int, volume: Int, path: String): Int

    /**
     * -1 for any Int voice param means "leave it at the preset's own value"
     * (only [voicePreset]'s copy applies).
     *
     * @param voicePreset 1-8, the engine's 8 built-in presets per language.
     * @param realWorldUnits when true, pitchBaseline is 40-422 (Hz) and speed
     *   is words-per-minute; everything else stays in the engine's own units.
     * @param sampleRateHz the actual rate to synthesize at - callers resolve
     *   a persisted 0 ("Standard" quality) to [DEFAULT_SAMPLE_RATE_HZ] before
     *   calling this, rather than passing 0 through (see that constant's own
     *   doc comment for why bare-0/engine-native-11025 is no longer used).
     * @param sentencePauseMs extra silence inserted between sentences, in
     *   milliseconds (0 = none, natural engine pacing only). Computed against
     *   [sampleRateHz] (or 11025 if that's 0) and delivered as real silent
     *   PCM through the same [AudioConsumer.onAudioChunk] callback - nothing
     *   extra to do on this side to receive it.
     * @param abbreviationExpansion the engine's own built-in abbreviation
     *   dictionary (e.g. "Dr." -> "Doctor", via a single `eciSetParam`
     *   call) - a separate mechanism from [dictPath]'s user dictionary, and
     *   confirmed (openevv's own source, see the native side's comment) not
     *   to disable it.
     * @param upsampleMethod null (engine default "sinc"), or
     *   "cubic"/"linear"/"hold"/"zeros"/"none".
     * @param dictPath absolute path to a user dictionary file loaded into
     *   the engine's `eciMainDict` volume via `eciLoadDict`/`eciSetDict`
     *   (openevv's own format: one entry a line, key-tab-translation,
     *   Windows-1252 - see [PronunciationDictionary.dictionaryFileFor]), or
     *   null/empty for no entries in that volume. Reloaded only when this
     *   path (or [abbvDictPath]) differs from what's already loaded for this
     *   (language, heteronym, ssml) slot, so passing the same paths every
     *   call is the expected, cheap common case - see the native side's
     *   `apply_dictionary`.
     * @param abbvDictPath same shape as [dictPath], loaded into the
     *   engine's separate `eciAbbvDict` volume instead - a user's own
     *   abbreviation entries (e.g. "kg" -> "kilogram"), kept in their own
     *   dictionary and screen rather than mixed into the Pronunciation
     *   dictionary's `eciMainDict` entries, though both volumes end up on
     *   the same active dictionary set. Null/empty for no entries in that
     *   volume. Not the same thing as [abbreviationExpansion] - that flag
     *   is the engine's own *built-in* abbreviation table (a single
     *   `eciSetParam`, confirmed not to consult this volume either); this
     *   is a user's own entries, exactly like [dictPath] but for
     *   abbreviations instead of whole words.
     * @param text raw bytes already encoded in the target language's own
     *   code set - Windows-1252 for every language this build ships (per
     *   openevv's docs/api.md: "Text is bytes in the language's own code
     *   set... It is not UTF-8"). This function does no encoding itself; use
     *   [encodeForEngine] to produce these bytes from a Kotlin [String]
     *   before calling. Passing raw UTF-8/modified-UTF-8 bytes here corrupts
     *   any non-ASCII character and misaligns the char offsets reported back
     *   through [AudioConsumer.onIndexMark].
     */
    /**
     * Synthesizes audio using encapsulated, type-safe [SynthesizeParams].
     * Obsolete engine parameters (gender, heteronymFilter, ssmlInput, upsampleMethod)
     * are supplied with appropriate defaults to ensure full JNI binary compatibility.
     */
    fun synthesize(params: SynthesizeParams): Boolean = nativeSynthesize(
        langId = params.langId,
        text = params.text,
        voicePreset = params.voicePreset,
        gender = params.gender,
        headSize = params.headSize,
        pitchBaseline = params.pitchBaseline,
        pitchFluctuation = params.pitchFluctuation,
        roughness = params.roughness,
        breathiness = params.breathiness,
        speed = params.speed,
        volume = params.volume,
        realWorldUnits = params.realWorldUnits,
        sampleRateHz = params.sampleRateHz,
        sentencePauseMs = params.sentencePauseMs,
        heteronymFilter = params.heteronymFilter,
        ssmlInput = params.ssmlInput,
        abbreviationExpansion = params.abbreviationExpansion,
        upsampleMethod = params.upsampleMethod,
        dictPath = params.dictPath,
        abbvDictPath = params.abbvDictPath,
        callback = params.callback,
    )

    external fun nativeSynthesize(
        langId: Int,
        text: ByteArray,
        voicePreset: Int,
        gender: Int,
        headSize: Int,
        pitchBaseline: Int,
        pitchFluctuation: Int,
        roughness: Int,
        breathiness: Int,
        speed: Int,
        volume: Int,
        realWorldUnits: Boolean,
        sampleRateHz: Int,
        sentencePauseMs: Int,
        heteronymFilter: Boolean,
        ssmlInput: Boolean,
        abbreviationExpansion: Boolean,
        upsampleMethod: String?,
        dictPath: String?,
        abbvDictPath: String?,
        callback: AudioConsumer,
    ): Boolean
}

/**
 * Parameters for speech synthesis via [EloquenceNative.synthesize].
 */
data class SynthesizeParams(
    val langId: Int,
    val text: ByteArray,
    val voicePreset: Int,
    val headSize: Int = -1,
    val pitchBaseline: Int = -1,
    val pitchFluctuation: Int = -1,
    val roughness: Int = -1,
    val breathiness: Int = -1,
    val speed: Int = -1,
    val volume: Int = -1,
    val realWorldUnits: Boolean = false,
    val sampleRateHz: Int = EloquenceNative.DEFAULT_SAMPLE_RATE_HZ,
    val sentencePauseMs: Int = 0,
    val abbreviationExpansion: Boolean = true,
    val dictPath: String? = null,
    val abbvDictPath: String? = null,
    val callback: EloquenceNative.AudioConsumer,
    val gender: Int = -1,
    val heteronymFilter: Boolean = false,
    val ssmlInput: Boolean = false,
    val upsampleMethod: String? = null,
)
