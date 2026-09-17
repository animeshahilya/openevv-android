package com.eloquick.tts

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * EloQuickEngine - Kotlin wrapper for the EloQuick TTS engine.
 *
 * Provides both synchronous and asynchronous (coroutine/Flow) APIs for:
 * - Whole-utterance synthesis (blocking, returns PCM array)
 * - Streaming synthesis (non-blocking, returns Flow of PCM chunks)
 * - Voice management (8 preset voices)
 * - Dictionary support (teach, lookup, load, save)
 * - Heteronym filter (creation-time)
 * - Sample rate and upsampling control
 *
 * Usage:
 * ```kotlin
 * val engine = EloQuickEngine()
 * val handle = engine.create(Language.EN_US)
 * try {
 *     // Synchronous synthesis
 *     val pcm = engine.synth(handle, "Hello world")
 *     playPcm(pcm)
 *
 *     // Streaming synthesis with coroutines
 *     val streamHandle = engine.createStream(Language.EN_US)
 *     engine.streamSpeak(streamHandle, "Long text that can be stopped...")
 *     engine.streamReadFlow(streamHandle).collect { chunk ->
 *         audioTrack.write(chunk, 0, chunk.size)
 *     }
 *     engine.destroyStream(streamHandle)
 * } finally {
 *     engine.destroy(handle)
 * }
 * ```
 */
class EloQuickEngine {

    companion object {
        private const val TAG = "EloQuickEngine"
        private var sLoaded = false
        private val sLoadLock = Any()

        @JvmStatic
        fun loadLibrary() {
            synchronized(sLoadLock) {
                if (!sLoaded) {
                    System.loadLibrary("eloquick")
                    sLoaded = true
                    Log.i(TAG, "Loaded libeloquick.so")
                }
            }
        }

        @JvmStatic
        fun isLoaded(): Boolean = sLoaded
    }

    init {
        loadLibrary()
    }

    // ========================================================================
    // Native Method Declarations
    // ========================================================================

    @JvmStatic
    external fun nativeCreate(language: Int): Long

    @JvmStatic
    external fun nativeDestroy(handle: Long)

    @JvmStatic
    external fun nativeGetLanguages(): IntArray

    @JvmStatic
    external fun nativeSynth(handle: Long, text: String): ShortArray?

    @JvmStatic
    external fun nativeSetParam(handle: Long, param: Int, value: Int): Int

    @JvmStatic
    external fun nativeGetParam(handle: Long, param: Int): Int

    @JvmStatic
    external fun nativeCopyVoice(handle: Long, from: Int, to: Int): Int

    @JvmStatic
    external fun nativeSetVoiceParam(handle: Long, voice: Int, param: Int, value: Int): Int

    @JvmStatic
    external fun nativeGetVoiceParam(handle: Long, voice: Int, param: Int): Int

    @JvmStatic
    external fun nativeSetSampleRateHz(handle: Long, hz: Int): Int

    @JvmStatic
    external fun nativeGetSampleRateHz(handle: Long): Int

    @JvmStatic
    external fun nativeSetUpsampleMethod(handle: Long, method: Int): Int

    @JvmStatic
    external fun nativeVersion(): String

    @JvmStatic
    external fun nativeSpeaking(handle: Long): Boolean

    @JvmStatic
    external fun nativeStop(handle: Long)

    @JvmStatic
    external fun nativeReset(handle: Long)

    @JvmStatic
    external fun nativeSynchronize(handle: Long)

    // Dictionary
    @JvmStatic
    external fun nativeDictTeach(handle: Long, volume: Int, key: String, say: String): Int

    @JvmStatic
    external fun nativeDictLookup(handle: Long, volume: Int, key: String): String?

    @JvmStatic
    external fun nativeDictForget(handle: Long)

    @JvmStatic
    external fun nativeDictLoad(handle: Long, volume: Int, path: String): Int

    @JvmStatic
    external fun nativeDictSave(handle: Long, volume: Int, path: String): Int

    // Heteronym
    @JvmStatic
    external fun nativeSetHeteroDefault(on: Boolean)

    @JvmStatic
    external fun nativeGetHeteroDefault(): Boolean

    // Streaming
    @JvmStatic
    external fun nativeStreamCreate(language: Int): Long

    @JvmStatic
    external fun nativeStreamSpeak(shandle: Long, text: String): Boolean

    @JvmStatic
    external fun nativeStreamRead(shandle: Long, dst: ByteArray, max: Int): Int

    @JvmStatic
    external fun nativeStreamStop(shandle: Long)

    @JvmStatic
    external fun nativeStreamDestroy(shandle: Long)

    @JvmStatic
    external fun nativeStreamCopyVoice(shandle: Long, from: Int, to: Int): Int

    @JvmStatic
    external fun nativeStreamSetVoiceParam(shandle: Long, voice: Int, param: Int, value: Int): Int

    @JvmStatic
    external fun nativeStreamGetVoiceParam(shandle: Long, voice: Int, param: Int): Int

    @JvmStatic
    external fun nativeStreamSetSampleRateHz(shandle: Long, hz: Int): Int

    @JvmStatic
    external fun nativeStreamGetSampleRateHz(shandle: Long): Int

    @JvmStatic
    external fun nativeStreamSetUpsampleMethod(shandle: Long, method: Int): Int

    @JvmStatic
    external fun nativeStreamDictLoad(shandle: Long, volume: Int, path: String): Int

    @JvmStatic
    external fun nativeStreamDictSave(shandle: Long, volume: Int, path: String): Int

    @JvmStatic
    external fun nativeStreamDictTeach(shandle: Long, volume: Int, key: String, say: String): Int

    @JvmStatic
    external fun nativeStreamDictLookup(shandle: Long, volume: Int, key: String): String?

    @JvmStatic
    external fun nativeStreamDictForget(shandle: Long)

    // Phonemes
    @JvmStatic
    external fun nativeGeneratePhonemes(handle: Long, text: String): ByteArray?

    // Audio format
    @JvmStatic
    external fun nativeGetAudioFormat(handle: Long): IntArray?

    // Voice info
    @JvmStatic
    external fun nativeGetVoiceName(handle: Long, voice: Int): String?

    @JvmStatic
    external fun nativeGetVoiceGender(handle: Long, voice: Int): Int

    @JvmStatic
    external fun nativeGetVoiceAge(handle: Long, voice: Int): Int

    // ========================================================================
    // Constants (matching eci.h)
    // ========================================================================

    /** Engine parameters */
    object Param {
        const val INPUT_TYPE = 1
        const val SAMPLE_RATE = 5
        const val REAL_WORLD_UNITS = 8
        const val LANGUAGE_DIALECT = 9
        const val UPSAMPLE_METHOD = 10
    }

    /** Voice parameters */
    object VoiceParam {
        const val GENDER = 0
        const val HEAD_SIZE = 1
        const val PITCH = 2
        const val FLUCTUATION = 3
        const val ROUGHNESS = 4
        const val BREATHINESS = 5
        const val SPEED = 6
        const val VOLUME = 7
    }

    /** Input types */
    object InputType {
        const val TEXT = 0
        const val ANNOTATIONS = 1
    }

    /** Dictionary volumes */
    object DictVolume {
        const val MAIN = 0
        const val ROOT = 1
        const val ABBREVIATION = 2
    }

    /** Upsampling methods */
    object UpsampleMethod {
        const val SINC = 0      // Best quality (default)
        const val CUBIC = 1
        const val LINEAR = 2
        const val HOLD = 3
        const val ZEROS = 4
        const val NONE = 5      // Synthesize at target rate
    }

    /** Dictionary error codes */
    object DictError {
        const val NO_ERROR = 0
        const val OUT_OF_MEMORY = 2
        const val ACCESS_ERROR = 6
    }

    /** Language IDs (matching engine's language codes) */
    enum class Language(val id: Int) {
        EN_US(0x1001),
        DE_DE(0x2001),
        EN_GB(0x1002),
        ES_ES(0x3001),
        ES_US(0x3002),
        FR_CA(0x4001),
        FR_FR(0x4002),
        IT_IT(0x5001),
        PL_PL(0x6001),
        JA_JP(0x7001);

        companion object {
            fun fromId(id: Int): Language? = values().firstOrNull { it.id == id }
        }
    }

    /** Voice presets (1-8) */
    enum class VoicePreset(val id: Int, val name: String, val gender: Int, val age: Int) {
        REED(1, "Reed", 1, 30),
        BOBBY(2, "Bobby", 1, 10),
        GRANDMA(3, "Grandma", 2, 70),
        GRANDPA(4, "Grandpa", 1, 75),
        KATHY(5, "Kathy", 2, 25),
        PRINCESS(6, "Princess", 2, 8),
        HUGE(7, "Huge", 1, 40),
        TINY(8, "Tiny", 1, 5);

        companion object {
            fun fromId(id: Int): VoicePreset? = values().firstOrNull { it.id == id }
        }
    }

    /** Voice gender */
    enum class Gender(val value: Int) {
        NEUTRAL(0), MALE(1), FEMALE(2)
    }

    // ========================================================================
    // Instance Management
    // ========================================================================

    /**
     * Creates a new TTS instance for the given language.
     * @param language Language to use, or 0 for default (first available)
     * @return Handle to the engine instance, or 0 if language not available
     */
    fun create(language: Language = Language.EN_US): Long {
        return nativeCreate(language.id)
    }

    /**
     * Creates a new TTS instance for the given language ID.
     * @param languageId Numeric language ID (from nativeGetLanguages)
     * @return Handle to the engine instance, or 0 if language not available
     */
    fun create(languageId: Int): Long {
        return nativeCreate(languageId)
    }

    /** Destroys an engine instance and releases all resources. */
    fun destroy(handle: Long) {
        if (handle != 0L) {
            nativeDestroy(handle)
        }
    }

    /** Returns array of available language IDs. */
    fun getAvailableLanguages(): IntArray = nativeGetLanguages()

    /** Returns available languages as enum values. */
    fun getAvailableLanguageEnums(): List<Language> =
        nativeGetLanguages().mapNotNull { Language.fromId(it) }

    /** Returns the engine version string. */
    fun getVersion(): String = nativeVersion()

    // ========================================================================
    // Synthesis (Blocking)
    // ========================================================================

    /**
     * Synthesizes text to PCM audio (blocking).
     * @param handle Engine instance handle
     * @param text Text to synthesize (UTF-8, max 64KB)
     * @return ShortArray of 11025 Hz mono PCM samples, or null on failure
     */
    @SuppressLint("DefaultLocale")
    fun synth(handle: Long, text: String): ShortArray? {
        if (handle == 0L) return null
        if (text.isEmpty() || text.length > 64 * 1024) {
            Log.w(TAG, "Text length out of bounds: ${text.length}")
            return null
        }
        return nativeSynth(handle, text)
    }

    /**
     * Synthesizes text to PCM audio and writes directly to a WAV file.
     * @param handle Engine instance handle
     * @param text Text to synthesize
     * @param outputFile Output WAV file
     * @param sampleRate Sample rate (default 11025)
     * @return true on success
     */
    fun synthToFile(
        handle: Long,
        text: String,
        outputFile: File,
        sampleRate: Int = 11025
    ): Boolean {
        val pcm = synth(handle, text) ?: return false
        return writeWavFile(outputFile, pcm, sampleRate)
    }

    /** Checks if the engine is currently speaking. */
    fun isSpeaking(handle: Long): Boolean = nativeSpeaking(handle)

    /** Stops current synthesis immediately. */
    fun stop(handle: Long) {
        if (handle != 0L) nativeStop(handle)
    }

    /** Resets the engine instance (clears text queue). */
    fun reset(handle: Long) {
        if (handle != 0L) nativeReset(handle)
    }

    /** Waits for all pending synthesis to complete. */
    fun synchronize(handle: Long) {
        if (handle != 0L) nativeSynchronize(handle)
    }

    // ========================================================================
    // Parameter Control
    // ========================================================================

    /** Sets an engine parameter. Returns new value on success, 0 on failure. */
    fun setParam(handle: Long, param: Int, value: Int): Int {
        return nativeSetParam(handle, param, value)
    }

    /** Gets an engine parameter value. */
    fun getParam(handle: Long, param: Int): Int {
        return nativeGetParam(handle, param)
    }

    /** Sets a voice parameter (for voices 0-8, where 0 is the speaking voice). */
    fun setVoiceParam(handle: Long, voice: Int, param: Int, value: Int): Int {
        return nativeSetVoiceParam(handle, voice, param, value)
    }

    /** Gets a voice parameter value. */
    fun getVoiceParam(handle: Long, voice: Int, param: Int): Int {
        return nativeGetVoiceParam(handle, voice, param)
    }

    /** Copies a preset voice (1-8) into the speaking voice (0). */
    fun copyVoice(handle: Long, from: VoicePreset): Int = copyVoice(handle, from.id)

    /** Copies a preset voice (1-8) into the speaking voice (0). */
    fun copyVoice(handle: Long, from: Int, to: Int = 0): Int {
        return nativeCopyVoice(handle, from, to)
    }

    /** Sets the sample rate in Hz. Returns the actual rate set. */
    fun setSampleRateHz(handle: Long, hz: Int): Int {
        return nativeSetSampleRateHz(handle, hz)
    }

    /** Gets the current sample rate in Hz. */
    fun getSampleRateHz(handle: Long): Int = nativeGetSampleRateHz(handle)

    /** Sets the upsampling method for rates above 11025 Hz. */
    fun setUpsampleMethod(handle: Long, method: Int): Int {
        return nativeSetUpsampleMethod(handle, method)
    }

    /** Enables/disables annotations in input text. */
    fun setAnnotationsEnabled(handle: Long, enabled: Boolean): Int {
        return setParam(handle, Param.INPUT_TYPE, if (enabled) InputType.ANNOTATIONS else InputType.TEXT)
    }

    /** Enables/disables real-world units (WPM for speed, Hz for pitch). */
    fun setRealWorldUnits(handle: Long, enabled: Boolean): Int {
        return setParam(handle, Param.REAL_WORLD_UNITS, if (enabled) 1 else 0)
    }

    // ========================================================================
    // Dictionary Support
    // ========================================================================

    /** Teaches a single word pronunciation. */
    fun dictTeach(handle: Long, volume: Int, key: String, say: String): Int {
        return nativeDictTeach(handle, volume, key, say)
    }

    /** Looks up a taught word. */
    fun dictLookup(handle: Long, volume: Int, key: String): String? {
        return nativeDictLookup(handle, volume, key)
    }

    /** Forgets all taught words (reverts to language's built-in dictionary). */
    fun dictForget(handle: Long) {
        nativeDictForget(handle)
    }

    /** Loads a dictionary from a text file (key<TAB>say per line). */
    fun dictLoad(handle: Long, volume: Int, file: File): Int {
        return nativeDictLoad(handle, volume, file.absolutePath)
    }

    /** Saves the current dictionary to a binary file. */
    fun dictSave(handle: Long, volume: Int, file: File): Int {
        return nativeDictSave(handle, volume, file.absolutePath)
    }

    // ========================================================================
    // Heteronym Filter
    // ========================================================================

    /** Sets the default heteronym filter state for future instances. */
    fun setHeteroDefault(enabled: Boolean) {
        nativeSetHeteroDefault(enabled)
    }

    /** Gets the default heteronym filter state. */
    fun getHeteroDefault(): Boolean = nativeGetHeteroDefault()

    // ========================================================================
    // Streaming API (Coroutine/Flow-based)
    // ========================================================================

    /**
     * Creates a streaming session for stoppable, low-latency synthesis.
     * @param language Language to use (0 = default)
     * @return Stream handle, or 0 on failure
     */
    fun createStream(language: Language = Language.EN_US): Long {
        return nativeStreamCreate(language.id)
    }

    /** Creates a streaming session with numeric language ID. */
    fun createStream(languageId: Int): Long = nativeStreamCreate(languageId)

    /** Destroys a streaming session. */
    fun destroyStream(shandle: Long) {
        if (shandle != 0L) nativeStreamDestroy(shandle)
    }

    /**
     * Queues text for streaming synthesis. Non-blocking.
     * Call streamRead() or streamReadFlow() to consume audio.
     */
    fun streamSpeak(shandle: Long, text: String): Boolean {
        return nativeStreamSpeak(shandle, text)
    }

    /**
     * Reads PCM audio from the stream (blocking).
     * @param shandle Stream handle
     * @param buffer Destination buffer
     * @return Number of bytes read, 0 at end of utterance, -1 on error/stop
     */
    fun streamRead(shandle: Long, buffer: ByteArray): Int {
        return nativeStreamRead(shandle, buffer, buffer.size)
    }

    /**
     * Stops streaming synthesis mid-utterance.
     * The engine remains valid for the next streamSpeak() call.
     */
    fun streamStop(shandle: Long) {
        nativeStreamStop(shandle)
    }

    // Stream parameter control
    fun streamCopyVoice(shandle: Long, from: VoicePreset): Int = streamCopyVoice(shandle, from.id)
    fun streamCopyVoice(shandle: Long, from: Int, to: Int = 0): Int = nativeStreamCopyVoice(shandle, from, to)
    fun streamSetVoiceParam(shandle: Long, voice: Int, param: Int, value: Int): Int = nativeStreamSetVoiceParam(shandle, voice, param, value)
    fun streamGetVoiceParam(shandle: Long, voice: Int, param: Int): Int = nativeStreamGetVoiceParam(shandle, voice, param)
    fun streamSetSampleRateHz(shandle: Long, hz: Int): Int = nativeStreamSetSampleRateHz(shandle, hz)
    fun streamGetSampleRateHz(shandle: Long): Int = nativeStreamGetSampleRateHz(shandle)
    fun streamSetUpsampleMethod(shandle: Long, method: Int): Int = nativeStreamSetUpsampleMethod(shandle, method)

    // Stream dictionary
    fun streamDictLoad(shandle: Long, volume: Int, file: File): Int = nativeStreamDictLoad(shandle, volume, file.absolutePath)
    fun streamDictSave(shandle: Long, volume: Int, file: File): Int = nativeStreamDictSave(shandle, volume, file.absolutePath)
    fun streamDictTeach(shandle: Long, volume: Int, key: String, say: String): Int = nativeStreamDictTeach(shandle, volume, key, say)
    fun streamDictLookup(shandle: Long, volume: Int, key: String): String? = nativeStreamDictLookup(shandle, volume, key)
    fun streamDictForget(shandle: Long) = nativeStreamDictForget(shandle)

    // ========================================================================
    // Flow-based Streaming API (Kotlin Coroutines)
    // ========================================================================

    /**
     * Returns a Flow of PCM chunks for streaming synthesis.
     * Each chunk is a ByteArray of 16-bit PCM samples.
     * The flow completes when the utterance ends, or emits an error if stopped.
     *
     * Usage:
     * ```kotlin
     * val streamHandle = engine.createStream()
     * engine.streamSpeak(streamHandle, "Hello world")
     * engine.streamReadFlow(streamHandle, chunkSize = 4096)
     *     .onEach { chunk -> audioTrack.write(chunk, 0, chunk.size) }
     *     .launchIn(scope)
     * ```
     */
    fun streamReadFlow(
        shandle: Long,
        chunkSize: Int = 4096,
        context: CoroutineContext = EmptyCoroutineContext
    ): ReceiveChannel<ByteArray> = Channel(Channel.UNLIMITED).apply {
        CoroutineScope(Dispatchers.IO + context).launch {
            try {
                val buffer = ByteArray(chunkSize)
                while (true) {
                    val read = nativeStreamRead(shandle, buffer, buffer.size)
                    when {
                        read > 0 -> {
                            val chunk = buffer.copyOf(read)
                            send(chunk)
                        }
                        read == 0 -> break // End of utterance
                        else -> break // Error or stopped
                    }
                }
            } finally {
                close()
            }
        }
    }

    /**
     * High-level coroutine function that speaks text and plays it via AudioTrack.
     * Handles AudioTrack lifecycle automatically.
     *
     * @param handle Engine instance (for non-streaming)
     * @param text Text to speak
     * @param audioManager AudioManager for focus handling
     * @param onProgress Callback with (bytesWritten, totalBytes) - optional
     * @return Job that completes when playback finishes
     */
    fun speakAndPlay(
        handle: Long,
        text: String,
        audioManager: AudioManager? = null,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Job = CoroutineScope(Dispatchers.IO).launch {
        val pcm = synth(handle, text) ?: return@launch
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(11025)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val minBufferSize = AudioTrack.getMinBufferSize(11025, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = maxOf(minBufferSize, pcm.size * 2)

        val audioTrack = AudioTrack.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        audioTrack.playState = AudioTrack.PLAYSTATE_PLAYING

        var written = 0
        val chunkSize = 4096
        while (written < pcm.size) {
            val remaining = pcm.size - written
            val toWrite = minOf(chunkSize, remaining)
            val byteBuffer = ByteBuffer.allocate(toWrite * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until toWrite) {
                byteBuffer.putShort(pcm[written + i])
            }
            audioTrack.write(byteBuffer.array(), 0, byteBuffer.capacity())
            written += toWrite
            onProgress?.invoke(written * 2, pcm.size * 2)
            yield()
        }

        audioTrack.playState = AudioTrack.PLAYSTATE_STOPPED
        audioTrack.release()
    }

    /**
     * Streaming version of speakAndPlay - plays audio as it's generated.
     * Supports stopping mid-playback via the returned Job.
     */
    fun streamSpeakAndPlay(
        shandle: Long,
        text: String,
        audioManager: AudioManager? = null,
        onProgress: ((Int) -> Unit)? = null
    ): Job = CoroutineScope(Dispatchers.IO).launch {
        if (!streamSpeak(shandle, text)) return@launch

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(getSampleRateHz(shandle))
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val minBufferSize = AudioTrack.getMinBufferSize(
            getSampleRateHz(shandle), AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufferSize, 8192)

        val audioTrack = AudioTrack.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        audioTrack.playState = AudioTrack.PLAYSTATE_PLAYING

        var totalBytes = 0
        val buffer = ByteArray(4096)
        while (true) {
            val read = streamRead(shandle, buffer)
            when {
                read > 0 -> {
                    audioTrack.write(buffer, 0, read)
                    totalBytes += read
                    onProgress?.invoke(totalBytes)
                    yield()
                }
                read == 0 -> break // End of utterance
                else -> break // Error or stopped
            }
        }

        audioTrack.playState = AudioTrack.PLAYSTATE_STOPPED
        audioTrack.release()
    }

    // ========================================================================
    // Phoneme Generation
    // ========================================================================

    /** Generates phoneme codes for the given text. */
    fun generatePhonemes(handle: Long, text: String): ByteArray? {
        return nativeGeneratePhonemes(handle, text)
    }

    /** Generates phonemes as a string (using engine's phoneme alphabet). */
    fun generatePhonemesString(handle: Long, text: String): String? {
        val bytes = generatePhonemes(handle, text)
        return bytes?.let { String(it) }
    }

    // ========================================================================
    // Audio Format & Voice Info
    // ========================================================================

    /** Returns audio format as [sampleRate, channels, bitsPerSample]. */
    fun getAudioFormat(handle: Long): AudioFormatInfo? {
        val arr = nativeGetAudioFormat(handle)
        return arr?.let { AudioFormatInfo(it[0], it[1], it[2]) }
    }

    data class AudioFormatInfo(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int
    )

    /** Gets voice name for a preset (1-8). */
    fun getVoiceName(handle: Long, preset: VoicePreset): String? = getVoiceName(handle, preset.id)

    fun getVoiceName(handle: Long, voice: Int): String? = nativeGetVoiceName(handle, voice)

    /** Gets voice gender for a preset. */
    fun getVoiceGender(handle: Long, preset: VoicePreset): Gender = getVoiceGender(handle, preset.id)

    fun getVoiceGender(handle: Long, voice: Int): Gender {
        return Gender.values().firstOrNull { it.value == nativeGetVoiceGender(handle, voice) } ?: Gender.NEUTRAL
    }

    /** Gets voice age for a preset. */
    fun getVoiceAge(handle: Long, preset: VoicePreset): Int = getVoiceAge(handle, preset.id)

    fun getVoiceAge(handle: Long, voice: Int): Int = nativeGetVoiceAge(handle, voice)

    // ========================================================================
    // Utility: WAV File Writing
    // ========================================================================

    /**
     * Writes PCM data to a WAV file.
     * @param file Output file
     * @param pcm PCM samples (16-bit signed)
     * @param sampleRate Sample rate in Hz
     */
    fun writeWavFile(file: File, pcm: ShortArray, sampleRate: Int = 11025): Boolean {
        return try {
            val byteCount = pcm.size * 2
            val fos = FileOutputStream(file)
            try {
                // RIFF header
                fos.write("RIFF".toByteArray())
                writeInt(fos, 36 + byteCount)
                fos.write("WAVE".toByteArray())
                // fmt chunk
                fos.write("fmt ".toByteArray())
                writeInt(fos, 16) // PCM format chunk size
                writeShort(fos, 1) // Audio format (PCM)
                writeShort(fos, 1) // Channels (mono)
                writeInt(fos, sampleRate)
                writeInt(fos, sampleRate * 2) // Byte rate
                writeShort(fos, 2) // Block align
                writeShort(fos, 16) // Bits per sample
                // data chunk
                fos.write("data".toByteArray())
                writeInt(fos, byteCount)
                // PCM data
                val buffer = ByteBuffer.allocate(byteCount).order(ByteOrder.LITTLE_ENDIAN)
                buffer.asShortBuffer().put(pcm)
                fos.write(buffer.array())
            } finally {
                fos.close()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write WAV file", e)
            false
        }
    }

    private fun OutputStream.writeInt(value: Int) {
        write((value & 0xFF).toByte())
        write((value >> 8 & 0xFF).toByte())
        write((value >> 16 & 0xFF).toByte())
        write((value >> 24 & 0xFF).toByte())
    }

    private fun OutputStream.writeShort(value: Int) {
        write((value & 0xFF).toByte())
        write((value >> 8 & 0xFF).toByte())
    }

    // ========================================================================
    // Resource Management Helpers
    // ========================================================================

    /** Executes a block with an engine instance, ensuring cleanup. */
    inline fun <R> withInstance(language: Language = Language.EN_US, crossinline block: (Long) -> R): R {
        val handle = create(language)
        if (handle == 0L) throw IllegalStateException("Failed to create engine for $language")
        try {
            return block(handle)
        } finally {
            destroy(handle)
        }
    }

    /** Executes a block with a streaming instance, ensuring cleanup. */
    inline fun <R> withStream(language: Language = Language.EN_US, crossinline block: (Long) -> R): R {
        val handle = createStream(language)
        if (handle == 0L) throw IllegalStateException("Failed to create stream for $language")
        try {
            return block(handle)
        } finally {
            destroyStream(handle)
        }
    }
}

/**
 * Extension functions for easier voice management.
 */
fun EloQuickEngine.setVoice(handle: Long, preset: EloQuickEngine.VoicePreset): Int = copyVoice(handle, preset)

fun EloQuickEngine.setSpeed(handle: Long, speed: Int): Int =
    setVoiceParam(handle, 0, EloQuickEngine.VoiceParam.SPEED, speed.coerceIn(0, 250))

fun EloQuickEngine.setPitch(handle: Long, pitch: Int): Int =
    setVoiceParam(handle, 0, EloQuickEngine.VoiceParam.PITCH, pitch.coerceIn(0, 250))

fun EloQuickEngine.setVolume(handle: Long, volume: Int): Int =
    setVoiceParam(handle, 0, EloQuickEngine.VoiceParam.VOLUME, volume.coerceIn(0, 100))

fun EloQuickEngine.getSpeed(handle: Long): Int = getVoiceParam(handle, 0, EloQuickEngine.VoiceParam.SPEED)

fun EloQuickEngine.getPitch(handle: Long): Int = getVoiceParam(handle, 0, EloQuickEngine.VoiceParam.PITCH)

fun EloQuickEngine.getVolume(handle: Long): Int = getVoiceParam(handle, 0, EloQuickEngine.VoiceParam.VOLUME)

/**
 * Text-to-Speech Service integration helper.
 * Implements Android's TextToSpeechService interface using EloQuick.
 */
abstract class EloQuickTtsService : android.speech.tts.TextToSpeechService() {

    private val engine = EloQuickEngine()
    private var handle: Long = 0
    private var currentLanguage: EloQuickEngine.Language = EloQuickEngine.Language.EN_US
    private var currentVoice: EloQuickEngine.VoicePreset = EloQuickEngine.VoicePreset.REED
    private var speechRate: Float = 1.0f
    private var speechPitch: Float = 1.0f

    override fun onInit(status: Int) {
        if (status == SUCCESS) {
            handle = engine.create(currentLanguage)
            if (handle == 0L) {
                Log.e(EloQuickEngine.TAG, "Failed to create EloQuick engine")
            } else {
                engine.copyVoice(handle, currentVoice)
                setLanguage(currentLanguage)
            }
        }
    }

    override fun onDestroy() {
        if (handle != 0L) {
            engine.destroy(handle)
            handle = 0
        }
        super.onDestroy()
    }

    override fun onGetLanguage(): Locale {
        return when (currentLanguage) {
            EloQuickEngine.Language.EN_US -> Locale.US
            EloQuickEngine.Language.DE_DE -> Locale.GERMANY
            EloQuickEngine.Language.EN_GB -> Locale.UK
            EloQuickEngine.Language.ES_ES -> Locale("es", "ES")
            EloQuickEngine.Language.ES_US -> Locale("es", "US")
            EloQuickEngine.Language.FR_CA -> Locale("fr", "CA")
            EloQuickEngine.Language.FR_FR -> Locale.FRANCE
            EloQuickEngine.Language.IT_IT -> Locale.ITALY
            EloQuickEngine.Language.PL_PL -> Locale("pl", "PL")
            EloQuickEngine.Language.JA_JP -> Locale.JAPAN
            else -> Locale.getDefault()
        }
    }

    override fun onLoadLanguage(lang: Locale): Int {
        val language = when (lang) {
            Locale.US -> EloQuickEngine.Language.EN_US
            Locale.GERMANY -> EloQuickEngine.Language.DE_DE
            Locale.UK -> EloQuickEngine.Language.EN_GB
            Locale("es", "ES") -> EloQuickEngine.Language.ES_ES
            Locale("es", "US") -> EloQuickEngine.Language.ES_US
            Locale("fr", "CA") -> EloQuickEngine.Language.FR_CA
            Locale.FRANCE -> EloQuickEngine.Language.FR_FR
            Locale.ITALY -> EloQuickEngine.Language.IT_IT
            Locale("pl", "PL") -> EloQuickEngine.Language.PL_PL
            Locale.JAPAN -> EloQuickEngine.Language.JA_JP
            else -> return LANG_NOT_SUPPORTED
        }
        currentLanguage = language
        handle = engine.create(language)
        if (handle == 0L) return LANG_NOT_SUPPORTED
        engine.copyVoice(handle, currentVoice)
        return LANG_AVAILABLE
    }

    override fun onSynthesizeText(
        text: CharSequence,
        params: Bundle,
        callback: android.speech.tts.TextToSpeechService.UtteranceProgressListener
    ): Int {
        if (handle == 0L) return ERROR

        val pcm = engine.synth(handle, text.toString()) ?: return ERROR

        // Apply rate/pitch from params
        val rate = params.getFloat("rate", speechRate)
        val pitch = params.getFloat("pitch", speechPitch)
        // Note: EloQuick uses discrete voice params, not continuous rate/pitch
        // This is a simplified mapping

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(11025)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val audioTrack = AudioTrack.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack.write(pcm, 0, pcm.size)
        audioTrack.play()

        // Notify callback
        callback.onStart()
        callback.onDone(null)

        return SUCCESS
    }

    override fun onStop(): Boolean {
        if (handle != 0L) {
            engine.stop(handle)
            return true
        }
        return false
    }

    override fun onSetLanguage(lang: Locale): Int = onLoadLanguage(lang)

    override fun onSetPitch(pitch: Float) {
        speechPitch = pitch
    }

    override fun onSetSpeechRate(rate: Float) {
        speechRate = rate
    }

    override fun onSetVoice(voice: android.speech.tts.Voice): Boolean {
        // Map voice to preset
        currentVoice = when (voice.name) {
            "Reed" -> EloQuickEngine.VoicePreset.REED
            "Bobby" -> EloQuickEngine.VoicePreset.BOBBY
            "Grandma" -> EloQuickEngine.VoicePreset.GRANDMA
            "Grandpa" -> EloQuickEngine.VoicePreset.GRANDPA
            "Kathy" -> EloQuickEngine.VoicePreset.KATHY
            "Princess" -> EloQuickEngine.VoicePreset.PRINCESS
            "Huge" -> EloQuickEngine.VoicePreset.HUGE
            "Tiny" -> EloQuickEngine.VoicePreset.TINY
            else -> currentVoice
        }
        if (handle != 0L) {
            engine.copyVoice(handle, currentVoice)
        }
        return true
    }

    override fun onGetVoices(): MutableList<android.speech.tts.Voice> {
        return EloQuickEngine.VoicePreset.values().map { preset ->
            val locale = when (currentLanguage) {
                EloQuickEngine.Language.EN_US -> Locale.US
                EloQuickEngine.Language.DE_DE -> Locale.GERMANY
                EloQuickEngine.Language.EN_GB -> Locale.UK
                EloQuickEngine.Language.ES_ES -> Locale("es", "ES")
                EloQuickEngine.Language.ES_US -> Locale("es", "US")
                EloQuickEngine.Language.FR_CA -> Locale("fr", "CA")
                EloQuickEngine.Language.FR_FR -> Locale.FRANCE
                EloQuickEngine.Language.IT_IT -> Locale.ITALY
                EloQuickEngine.Language.PL_PL -> Locale("pl", "PL")
                EloQuickEngine.Language.JA_JP -> Locale.JAPAN
                else -> Locale.getDefault()
            }
            android.speech.tts.Voice(
                locale,
                preset.name,
                400, // quality
                200, // latency
                false, // network
                android.speech.tts.Voice.LATENCY_NORMAL,
                android.speech.tts.Voice.QUALITY_HIGH
            )
        }.toMutableList()
    }
}