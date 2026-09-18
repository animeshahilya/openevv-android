package com.eloquick.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log

private const val TAG = "AudioPlayer"

/**
 * How long a write may make zero forward progress before [writeAllOrGiveUp]
 * gives up on it. Far longer than this app's own small stream buffer could
 * honestly take to drain under ordinary back-pressure, so a real device
 * never trips it - it only fires if the audio pipeline has actually wedged.
 */
private const val WRITE_STALL_TIMEOUT_MS = 5_000L

/**
 * Writes [length] bytes of [data] to [track] without ever blocking
 * indefinitely. The default blocking [AudioTrack.write] can stall forever
 * if the audio pipeline stops draining (a wedged HAL, a route that never
 * resumes) - openevv's own reference NVDA driver hit exactly this class of
 * problem in its wave player and added a watchdog for it
 * (Mudb0y/openevv#12, "Break a stall in the wave player rather than
 * waiting on it for ever"): nothing downstream would ever see it as
 * stopped, the Preview button's spinner would never come back, and the
 * coroutine waiting on [AudioPlayer.speakBlocking] would wait forever too.
 * Ported here as a non-blocking write loop instead: keep offering data as
 * buffer space frees up, bail out the moment [stillWanted] says this
 * generation has been superseded or stopped (checked every iteration, same
 * as every other write already did), and give up after
 * [WRITE_STALL_TIMEOUT_MS] of zero forward progress. A pause the caller
 * actually asked for isn't a concern here - [AudioPlayer] has no pause
 * concept, only stop/supersede, both of which [stillWanted] already covers.
 */
private fun writeAllOrGiveUp(track: AudioTrack, data: ByteArray, length: Int, stillWanted: () -> Boolean): Boolean {
    var offset = 0
    var lastProgressAt = SystemClock.elapsedRealtime()
    while (offset < length) {
        if (!stillWanted()) return false
        val written = try {
            track.write(data, offset, length - offset, AudioTrack.WRITE_NON_BLOCKING)
        } catch (e: Exception) {
            Log.w(TAG, "Error writing audio chunk", e)
            return false
        }
        if (written < 0) {
            Log.w(TAG, "AudioTrack.write error: $written")
            return false
        }
        if (written > 0) {
            offset += written
            lastProgressAt = SystemClock.elapsedRealtime()
        } else if (SystemClock.elapsedRealtime() - lastProgressAt > WRITE_STALL_TIMEOUT_MS) {
            Log.w(TAG, "AudioTrack.write stalled for ${WRITE_STALL_TIMEOUT_MS}ms, giving up")
            return false
        } else {
            // Outside the try above: an interrupt here used to escape
            // onAudioChunk into JNI with a pending exception. Treat it as
            // "no longer wanted" and unwind cleanly instead.
            try {
                Thread.sleep(5)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }
    return true
}

/**
 * Streaming synth-and-play helper for the voice picker's "preview" button
 * and the tuning screen's live preview. Not used by the TTS service itself,
 * which streams straight into [android.speech.tts.SynthesisCallback] -
 * this exists because the app has its own playback to drive instead.
 *
 * Used to buffer the whole utterance (MODE_STATIC) before playing any of
 * it - simple, but it meant a preview sat silent for the entire synthesis
 * time instead of the ~90ms-to-first-chunk this engine actually delivers
 * (see the README's own measurement), every single tap. Writes each
 * [EloquenceNative.AudioConsumer.onAudioChunk] chunk straight into a
 * MODE_STREAM [AudioTrack] as it arrives instead, so playback starts as
 * soon as the first chunk lands - the same "instant response" property the
 * system-service path already had.
 */
class AudioPlayer(context: Context) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    private val nativeOutputRate: Int by lazy {
        try {
            audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private val hardwareBurstFrames: Int by lazy {
        try {
            audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
        } catch (_: Exception) {
            0
        }
    }

    @Volatile private var stopped = false

    /**
     * Monotonic playback generation: every [begin]/[stop] invalidates all
     * older generations. Rapid preview taps used to overlap - the previous
     * utterance's [speakBlocking] would adopt the shared [track] slot after
     * the new one, leaking the new track and playing stale audio over it,
     * or (now that chunks stream in one at a time) keep writing its own
     * stale chunks into whichever track is current. Every place that
     * touches [track] checks [id] against [session] first, so a superseded
     * generation can only release, never adopt, write, or play.
     */
    private val lock = Any()
    private var session = 0
    private var track: AudioTrack? = null

    /** Cancels any in-flight synthesis/playback started by [speakBlocking]. */
    fun stop() {
        stopped = true
        synchronized(lock) {
            session++
            quietRelease(track)
            track = null
        }
    }

    private fun quietRelease(t: AudioTrack?) {
        if (t == null) return
        try {
            t.stop()
        } catch (_: Exception) {
            // Already stopped/released - nothing to do.
        }
        try {
            t.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing playback", e)
        }
    }

    /**
     * Synthesizes [text] with the given voice/tuning parameters and plays it
     * back as audio arrives. Blocking - call from a background thread/
     * coroutine (synthesis, and every [AudioTrack.write] call along the
     * way, both block the calling thread). Returns true if any audio was
     * produced and written for playback.
     */
    fun speakBlocking(
        langId: Int,
        text: String,
        voicePreset: Int,
        headSize: Int,
        pitchBaseline: Int,
        pitchFluctuation: Int,
        roughness: Int,
        breathiness: Int,
        speed: Int,
        volume: Int,
        realWorldUnits: Boolean,
        sampleRateHz: Int,
        sentencePauseMs: Int = 0,
        abbreviationExpansion: Boolean,
        audioOptimizerEnabled: Boolean = false,
        dictPath: String? = null,
        abbvDictPath: String? = null,
    ): Boolean {
        // The service guards this per request; the preview path must too -
        // an unguarded nativeSynthesize throws UnsatisfiedLinkError on a
        // corrupt/ABI-mismatched install instead of failing this preview.
        if (!EloquenceNative.isLoaded) {
            Log.e(TAG, "Preview requested but native library is not loaded")
            return false
        }
        val id = begin()
        // Same resolution rule as the service path (see resolveSampleRateHz)
        // - the two must never disagree about what "Standard" means.
        val effectiveRate = EloquenceNative.resolveSampleRateHz(sampleRateHz)
        val newTrack = openStreamTrack(effectiveRate)
        // One instance per speakBlocking call, never reused across calls -
        // see AudioOptimizer's own doc comment for why (stateful filters and
        // limiter gain must not carry stale state into the next preview tap).
        val optimizer = AudioOptimizer.maybe(audioOptimizerEnabled, effectiveRate)
        synchronized(lock) {
            if (id != session || stopped) {
                // Superseded (or stopped) before synthesis even started:
                // only release, never adopt or play - see [session].
                quietRelease(newTrack)
                return false
            }
            quietRelease(track)
            track = newTrack
        }
        var wroteAny = false
        var totalBytesWritten = 0L
        try {
            // MODE_STREAM plays whatever is queued as it's queued - starting
            // now, before the first chunk exists, is the standard pattern (the
            // track just outputs silence until data arrives) and means there is
            // no separate "first chunk" state to track below.
            newTrack.play()

            val consumer = object : EloquenceNative.AudioConsumer {
                override fun onAudioChunk(data: ByteArray, length: Int): Boolean {
                    if (stopped) return false
                    val stillCurrent = { synchronized(lock) { id == session && !stopped && track === newTrack } }
                    if (!stillCurrent()) return false // superseded or stopped: stop feeding this track
                    optimizer?.process(data, length)
                    if (!writeAllOrGiveUp(newTrack, data, length, stillCurrent)) return false
                    wroteAny = true
                    totalBytesWritten += length
                    return !stopped
                }

                override fun onIndexMark(charOffset: Int) {
                    // Not used for preview playback.
                }
            }

            // Encode last, right before the native call - text stays a plain
            // Kotlin String for every caller of speakBlocking up to this point.
            val encodedText = encodeForEngine(text, langId)

            val ok = EloquenceNative.synthesize(
                SynthesizeParams(
                    langId = langId,
                    text = encodedText,
                    voicePreset = voicePreset,
                    headSize = headSize,
                    pitchBaseline = pitchBaseline,
                    pitchFluctuation = pitchFluctuation,
                    roughness = roughness,
                    breathiness = breathiness,
                    speed = speed,
                    volume = volume,
                    realWorldUnits = realWorldUnits,
                    sampleRateHz = effectiveRate,
                    sentencePauseMs = sentencePauseMs,
                    abbreviationExpansion = abbreviationExpansion,
                    dictPath = dictPath,
                    abbvDictPath = abbvDictPath,
                    callback = consumer,
                )
            )

            // Drain the track buffer so speech doesn't get cut off when synthesis
            // finishes 10x faster than real-time playback.
            if (ok && wroteAny && !stopped) {
                val totalFrames = totalBytesWritten / 2L // ENCODING_PCM_16BIT mono
                val stillCurrent = { synchronized(lock) { id == session && !stopped && track === newTrack } }
                var lastHead = -1L
                var lastHeadAdvanceTime = SystemClock.elapsedRealtime()
                while (stillCurrent()) {
                    val head = try {
                        newTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    } catch (_: Exception) {
                        break
                    }
                    if (head >= totalFrames) break
                    if (head != lastHead) {
                        lastHead = head
                        lastHeadAdvanceTime = SystemClock.elapsedRealtime()
                    } else if (SystemClock.elapsedRealtime() - lastHeadAdvanceTime > 1500L) {
                        break
                    }
                    try {
                        Thread.sleep(20)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }

            return ok && wroteAny && !stopped
        } finally {
            // Normal completion used to return with the adopted track still
            // held: every preview leaked one AudioTrack (and its AudioFlinger
            // mixer slot) until the next preview or an explicit stop released
            // it - found as a track row outliving playback by 10+ minutes
            // during on-device testing. Release here when this generation is
            // still current; a superseding generation or stop() already
            // released (or adopted past) this track, so touch nothing then -
            // and never null out a track that is no longer ours. In finally
            // (not just on the success path) so a throw out of play() or the
            // native call can't adopt-then-leak the track either.
            synchronized(lock) {
                if (id == session && track === newTrack) {
                    quietRelease(newTrack)
                    track = null
                }
            }
        }
    }

    /**
     * Opens a new playback generation, invalidating any older one still
     * synthesizing or holding [track] - see [session]. Always paired with a
     * [stop] (or a newer [begin]) before the previous generation can adopt.
     */
    private fun begin(): Int {
        synchronized(lock) {
            session++
            stopped = false
            return session
        }
    }

    /**
     * A ready-to-play (but not yet started) streaming [AudioTrack] at
     * [sampleRate] - doubled past the platform minimum for headroom against
     * an occasional slow [AudioTrack.write], same reasoning any streaming
     * producer/consumer buffer needs some slack for, then rounded up to a
     * multiple of the hardware's own burst size ([AudioManager]'s
     * `PROPERTY_OUTPUT_FRAMES_PER_BUFFER`) so the HAL isn't left padding or
     * splitting an odd-sized buffer on every callback - ported from
     * SherpaVoices' `AudioPlayer`, which measured this as a real latency win
     * on its own streaming preview path. [AudioTrack.PERFORMANCE_MODE_LOW_LATENCY]
     * likewise requests the platform's fast-mixer track where one exists.
     * Both are preview-only wins, same as the buffer math itself - this
     * class backs the in-app Preview button alone (see the class doc
     * comment); the actual system-TTS path a screen reader uses never
     * touches an [AudioTrack] here at all, so neither change affects real
     * day-to-day battery/performance the way it would for a synthesis-side
     * change.
     */
    private fun openStreamTrack(sampleRate: Int): AudioTrack {
        val bytesPerFrame = 2 // ENCODING_PCM_16BIT mono

        // getMinBufferSize returns ERROR_BAD_VALUE (-2) or ERROR (-1), not an
        // exception, when the HAL doesn't support this rate/channel/encoding
        // combo - the AOSP reference HAL a Pixel runs takes almost anything
        // (AudioFlinger resamples internally), but a lower/mid-range OEM HAL
        // is more likely to actually reject an unusual mono 16-bit
        // combination. Trusting a negative return here as a byte count would
        // make rawBufferSize negative and hand Builder.setBufferSizeInBytes
        // garbage, throwing IllegalArgumentException out of this function on
        // exactly the class of device this app's own audio-quality bug (see
        // EloquenceNative.DEFAULT_SAMPLE_RATE_HZ) already showed behaves
        // differently than a Pixel. A fixed ~100ms of audio at this rate is a
        // safe, always-computable fallback size.
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).let { if (it > 0) it else (sampleRate / 10) * bytesPerFrame }

        // PROPERTY_OUTPUT_FRAMES_PER_BUFFER describes the mixer's burst size
        // at the device's *native* output rate (commonly 48000Hz), not at
        // this track's own sampleRate (11025/22050/44100 - a user setting,
        // see AudioOptimizer's doc comment on why). Rounding a buffer sized
        // in one sample-rate domain up to a multiple of a burst size defined
        // in a different one doesn't actually land on real HAL callback
        // boundaries unless the two happen to match - scale the burst frame
        // count into this track's own domain first so the rounding means
        // what it's meant to.
        val scaledBurstFrames = if (hardwareBurstFrames > 0 && nativeOutputRate > 0) {
            (hardwareBurstFrames.toLong() * sampleRate / nativeOutputRate).toInt()
        } else 0
        val burstBytes = scaledBurstFrames * bytesPerFrame
        val rawBufferSize = minBuf * 2
        val bufferSize = if (burstBytes > 0) {
            ((rawBufferSize + burstBytes - 1) / burstBytes) * burstBytes
        } else rawBufferSize
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        return AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            // No setSessionId call: the Builder generates a fresh session
            // by default, and passing AUDIO_SESSION_ID_GENERATE (= 0)
            // explicitly trips lint's Range check (must be >= 1).
            .build()
    }
}
