package com.eloquick.tts

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.tanh

// Presence band: a real bandpass (highpass then lowpass in series), not the open-ended highpass
// shelf Tarang's Aural Exciter uses - Tarang tunes for a music/radio stream, where "everything
// above ~3.5kHz" is mostly cymbals and air. For a synthetic voice, that same open-ended shelf
// also excites the 5-8kHz sibilance region (s/sh/ch/t), and speech's own consonant clarity - what
// a "presence boost" is actually for in vocal engineering - sits lower, roughly 2.5-5kHz. Rolling
// the excited band off above PRESENCE_HIGH_HZ keeps the boost aimed at clarity instead of also
// making every "s" harsher.
private const val PRESENCE_LOW_HZ = 2500.0
private const val PRESENCE_HIGH_HZ = 5000.0
private const val PRESENCE_DRIVE = 6f
private const val PRESENCE_BLEND = 0.14f

// Warmth band: a one-pole lowpass around typical vocal fundamental/first-harmonic territory, for
// body rather than clarity. Still gentler than Tarang's own Bass Warmth (which tunes for a mixed
// music/talk stream that can carry real sub-bass) - this app's voice is always mono speech with no
// bass instrument content to speak of, so the corner itself stays put rather than reaching lower
// toward true sub-bass, which would pull in too little actual voice energy to blend anything back
// from. Drive and blend were both raised a step from this class's original, more conservative
// values (5f/0.11f) at explicit user request for a richer, more bass-forward voice - blend carries
// most of that move (it's the direct "how much of this coloring is audible" knob) with drive only
// bumped enough alongside it to keep the added harmonics from thinning out as blend grows.
private const val WARMTH_HZ = 180.0
private const val WARMTH_DRIVE = 6f
private const val WARMTH_BLEND = 0.20f

// Leveler: a gentle, upward-only gain rider that nudges quiet passages of an utterance (a soft
// word-final consonant, a trailing syllable) up toward a comfortable level, adapted from Tarang's
// own LoudnessLevelerAudioProcessor - a proven, already-tuned design, not reinvented here. Tuned
// far more conservatively than Tarang's own radio-facing bounds (0.5x-4x): Tarang's leveler exists
// to smooth over *other creators'* wildly different mastering levels between one stream and the
// next, a problem this engine's own synthetic, consistently-leveled output doesn't really have.
// What it *can* still do for a single synthetic voice is even out its own natural micro-dynamics
// (prosody makes some syllables quieter than others) for a fuller, more consistently-present feel
// - a small, safe nudge (0.85x-1.4x) rather than the wide swing a mismatched-source radio stream
// needs. Tarang's own alpha constants are tuned for a 44.1kHz stream; scaled here by sample-rate
// ratio (not the exp() corner-frequency derivation the filters above use - a plain EMA time
// constant scales linearly with rate, see scaledEnvelopeAlpha) so the same real-world ~100ms
// envelope window and gain-smoothing pace hold at this engine's own lower rates too.
private const val LEVELER_REFERENCE_RATE_HZ = 44100
private const val LEVEL_ENVELOPE_ALPHA_AT_REFERENCE_RATE = 0.0005f
private const val GAIN_SMOOTH_ALPHA_AT_REFERENCE_RATE = 0.0003f
private const val LEVELER_TARGET_LEVEL = 9000f
private const val LEVELER_MIN_GAIN = 0.85f
private const val LEVELER_MAX_GAIN = 1.4f

// Just under full scale (32767), same margin and same reasoning as Tarang's ClipGuardAudioProcessor:
// a safety net for the two boosts above stacking on an already-loud passage, not a loudness target.
private const val CLIP_GUARD_THRESHOLD = 30000f

// Same release pacing as Tarang's ClipGuard. Deliberately zero-latency here rather than a genuine
// lookahead limiter: Tarang's lookahead exists to smooth gain reduction across a continuous, long-
// running stream, at the cost of needing an end-of-stream drain so no buffered audio is lost when
// the stream stops. An utterance here is short and already broken into per-request chunks (see
// EloquenceTtsService.onSynthesizeText's chunkRangesForSynthesis loop) - adding a lookahead delay
// line would mean silently swallowing the last few milliseconds of every utterance unless that
// drain logic is threaded through both callers correctly, for a benefit (smoother gain ramp-in on
// a rare stacked transient) that isn't worth that risk on a screen-reader's speech path. Snapping
// down immediately on a genuine peak and only smoothing the release is the same trade every
// same-sample limiter makes.
private const val CLIP_GUARD_RELEASE_ALPHA = 0.01f

/** One-pole IIR alpha for the EMA lowpass form `y += alpha*(x-y)` at a corner of [cornerHz] and
 * the stream's actual [sampleRateHz] - same derivation SherpaVoices' own VoiceEnhancer uses,
 * needed here (unlike Tarang, which hardcodes an alpha for one assumed ~44.1kHz stream rate)
 * because this engine's own output rate is a user setting (Reading > Audio quality) rather than a
 * fixed platform constant. Only correct for *this* recurrence - see [onePoleHighpassPole] for the
 * different (complementary) coefficient Tarang's own highpass form needs. */
internal fun onePoleAlpha(cornerHz: Double, sampleRateHz: Int): Float =
    (1.0 - exp(-2.0 * PI * cornerHz / sampleRateHz)).toFloat()

/** The pole for Tarang's highpass recurrence `y = a*(y_prev + x - x_prev)` at a corner of
 * [cornerHz] and [sampleRateHz] - a *different* coefficient than [onePoleAlpha] despite both
 * being "a one-pole filter's alpha", because this recurrence's pole sits at `exp(-2*pi*fc/fs)`
 * rather than at its complement (the EMA lowpass form [onePoleAlpha] is derived for). Using
 * [onePoleAlpha]'s value here instead - the actual bug this shipped with, caught by a real
 * on-device listen - inverts the coefficient: at a low sample rate (e.g. 11025Hz) and a 3500Hz
 * corner it produced ~0.86 instead of the correct ~0.14, an almost-runaway filter that colored
 * the treble band far too aggressively and made the voice sound wrong rather than richer. */
internal fun onePoleHighpassPole(cornerHz: Double, sampleRateHz: Int): Float =
    exp(-2.0 * PI * cornerHz / sampleRateHz).toFloat()

// Exact power-of-two reciprocal of the 16-bit full-scale divisor used to normalize a sample into
// [-1, 1] before saturation. Multiplying by this is bit-identical to dividing by 32768f (a power
// of two divides/multiplies a float exactly, with no rounding difference) but avoids a hardware
// float division - several times the latency of a multiply on typical ARM FPUs - in a loop that
// runs once per output sample.
private const val INV_32768 = 1f / 32768f

/** Odd-symmetric soft-clip via tanh, generating the harmonic content each stage below blends back
 * in - identical shape to Tarang's own harmonicSaturate. Internal (not private) for direct unit
 * testing. [normalizedInput] is expected in roughly [-1, 1]. */
internal fun harmonicSaturate(normalizedInput: Float, drive: Float): Float = tanh(normalizedInput * drive)

/**
 * [harmonicSaturate] evaluated at a cheap 2x oversample and decimated back down by averaging,
 * instead of once per output sample - a real correctness gap in the plain version, not a
 * tuning choice: tanh is a nonlinearity, and any nonlinearity fed a signal with energy near this
 * stream's own Nyquist frequency generates harmonics *above* Nyquist that alias - fold back down
 * into the audible band as inharmonic noise - exactly the kind of artifact a "richer" effect isn't
 * supposed to add. The presence band in particular reaches up to [PRESENCE_HIGH_HZ] (5kHz); its
 * own 3rd harmonic alone (15kHz) already exceeds Nyquist at this app's own new 22050Hz default
 * rate (see [com.eloquick.tts.EloquenceNative.DEFAULT_SAMPLE_RATE_HZ]), let
 * alone the 44.1kHz "Clearest" tier's own harder-driven upper harmonics.
 *
 * [prevInput] is the same stage's own filtered value one sample ago (already tracked by every
 * caller for its own filter recurrence) - `(prevInput + currentInput) / 2` is a cheap linear-
 * interpolation estimate of the in-between sample a true 2x-oversampled signal would have had.
 * Evaluating the nonlinearity at both points and averaging the *results* (a plain two-tap boxcar
 * lowpass, decimating back to 1x) pulls down the imaging energy this stage's own upsampling step
 * would otherwise introduce above the new Nyquist - not a perfect anti-aliasing filter (a boxcar's
 * stopband rejection is modest, around -13dB at its worst frequency), but a real, well-understood
 * reduction for near-zero extra cost, in keeping with this class's own already-pragmatic-not-
 * audiophile leveler and limiter.
 *
 * Provably identical to a single [harmonicSaturate] call whenever the signal isn't actually
 * changing sample to sample ([prevInput] == [currentInput], e.g. silence or a sustained tone) -
 * both interpolated points collapse to the same value, so nothing about this stage's steady-state
 * tone or its already-tuned [PRESENCE_BLEND]/[WARMTH_BLEND] loudness changes; only genuinely fast-
 * moving (high-frequency, alias-prone) content is affected, which is exactly the content this
 * exists to smooth. Internal for direct unit testing.
 */
internal fun oversampledHarmonicSaturate(prevInput: Float, currentInput: Float, drive: Float): Float {
    val midpoint = (prevInput + currentInput) * 0.5f
    return (harmonicSaturate(midpoint, drive) + harmonicSaturate(currentInput, drive)) * 0.5f
}

/** The gain that would bring [sampleAbs] down to [threshold] - 1f (no reduction) if already under
 * threshold. Pure/stateless, same shape as Tarang's limiterGainForSample. */
internal fun limiterGainForSample(sampleAbs: Float, threshold: Float = CLIP_GUARD_THRESHOLD): Float =
    if (sampleAbs > threshold) threshold / sampleAbs else 1f

/** One step of gain smoothing: snaps down immediately whenever [targetGain] is more restrictive
 * than [currentGain] (a real peak must never be delayed), eases back up at [releaseAlpha] pace
 * otherwise. Pure/stateless, same shape as Tarang's releaseSmoothedGain. */
internal fun releaseSmoothedGain(currentGain: Float, targetGain: Float, releaseAlpha: Float = CLIP_GUARD_RELEASE_ALPHA): Float =
    if (targetGain < currentGain) targetGain else currentGain + releaseAlpha * (targetGain - currentGain)

/** Scales a plain EMA alpha (as opposed to the exp()-derived filter poles above) tuned for
 * [referenceRateHz] to the equivalent alpha at [sampleRateHz], preserving the same real-world time
 * constant - an EMA's window in samples is roughly `1/alpha`, so its window in *seconds* is
 * `1/(alpha*fs)`; holding that product constant across a rate change means `alpha` must scale by
 * `referenceRateHz/sampleRateHz`. Pure/stateless - internal for direct unit testing. */
internal fun scaledEnvelopeAlpha(referenceAlpha: Float, sampleRateHz: Int, referenceRateHz: Int = LEVELER_REFERENCE_RATE_HZ): Float =
    (referenceAlpha * referenceRateHz / sampleRateHz).coerceIn(0f, 1f)

/** One step of the leveler's envelope follower - an exponential moving average of the absolute
 * sample value, same shape as Tarang's own updateLevelEnvelope. Pure/stateless - internal for
 * direct unit testing. */
internal fun updateLevelEnvelope(prevEnvelope: Float, sampleAbs: Float, alpha: Float): Float =
    prevEnvelope + alpha * (sampleAbs - prevEnvelope)

/** Maps a measured loudness envelope to the gain that would pull it toward [targetLevel], clamped
 * to [minGain]/[maxGain] - same shape as Tarang's own levelerGain, at this class's own gentler
 * default bounds (see this file's leveler constants for why). Pure/stateless - internal for direct
 * unit testing. */
internal fun levelerGain(
    envelope: Float,
    targetLevel: Float = LEVELER_TARGET_LEVEL,
    minGain: Float = LEVELER_MIN_GAIN,
    maxGain: Float = LEVELER_MAX_GAIN,
): Float = (targetLevel / envelope.coerceAtLeast(1f)).coerceIn(minGain, maxGain)

/**
 * "Audio Optimizer" - a speech-tailored descendant of Tarang's "Tone Color" section (Sound
 * Upscaler + Analog Warmth) and its Clip Guard limiter, collapsed into one mono, one-switch
 * effect. Deliberately not a straight port: Tarang tunes for a mixed music/talk radio stream, and
 * carrying its exact bands and blend amounts over unchanged both excited the sibilance region
 * (see [PRESENCE_HIGH_HZ]'s own doc comment) and stacked three separate saturators - the
 * open-ended treble shelf, the bass shelf, and a *third*, full-band pass on top of both -
 * compounding into more coloring than a single synthetic voice needs. This version keeps two
 * bands, each aimed at one real vocal-engineering axis (presence/clarity vs. warmth/body) instead
 * of a generic "saturate everything" pass, at gentler blend amounts throughout - richer and
 * fuller without the voice's own character (or its intelligibility) getting smeared by it. A
 * third stage, adapted from Tarang's own Loudness Leveler, rides quiet moments within an
 * utterance gently upward before the tone-shaping runs, adding fullness on the dynamics axis
 * rather than only the spectral one. Where Tarang exposes each piece as its own toggle across two
 * sections, this app's own request was the opposite - one button that just sounds better, not a
 * new bank of DSP sliders to tune.
 *
 * A hardware/platform equalizer or dynamics effect ([android.media.audiofx.Equalizer],
 * [android.media.audiofx.DynamicsProcessing], and similar) isn't an option here the way it would
 * be for an app that plays its own audio: those attach to a live [android.media.AudioTrack]'s
 * session id, and this app only ever owns one for its own in-app Preview button - the real
 * synthesis path hands raw PCM to [android.speech.tts.SynthesisCallback.audioAvailable] instead,
 * whose framework contract has no [android.media.AudioTrack]/session of this app's own to attach
 * anything to (TalkBack, or whatever app asked for speech, owns that). So "device-specific"
 * enhancement of the audio every real caller actually hears can only happen in software, applied
 * to the PCM directly, which is exactly what this class already does.
 *
 * Stateful (each filter tracks its previous sample) - construct one instance per synthesis
 * request (a whole [com.eloquick.service.EloquenceTtsService.onSynthesizeText]
 * call, or one preview tap), not a shared/reused one, same convention SherpaVoices' own
 * VoiceEnhancer doc comment establishes for exactly this reason: reusing an instance across
 * utterances would carry stale filter state (and a stale limiter gain) into the next one.
 */
class AudioOptimizer(sampleRateHz: Int) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz must be resolved (see resolveSampleRateHz) before constructing AudioOptimizer" }
    }

    companion object {
        /** The one construction rule both synthesis paths share: an
         * instance per request when enabled, null when not - so the two
         * call sites can't drift into different enable/rate handling. */
        fun maybe(enabled: Boolean, sampleRateHz: Int): AudioOptimizer? =
            if (enabled) AudioOptimizer(sampleRateHz) else null
    }

    private val presenceHighpassAlpha = onePoleHighpassPole(PRESENCE_LOW_HZ, sampleRateHz)
    private val presenceLowpassAlpha = onePoleAlpha(PRESENCE_HIGH_HZ, sampleRateHz)
    private val warmthAlpha = onePoleAlpha(WARMTH_HZ, sampleRateHz)
    private val levelEnvelopeAlpha = scaledEnvelopeAlpha(LEVEL_ENVELOPE_ALPHA_AT_REFERENCE_RATE, sampleRateHz)
    private val gainSmoothAlpha = scaledEnvelopeAlpha(GAIN_SMOOTH_ALPHA_AT_REFERENCE_RATE, sampleRateHz)

    private var prevInput = 0f
    private var prevHighPass = 0f
    private var presenceBand = 0f
    private var warmthLowPass = 0f
    private var levelEnvelope = 0f
    private var levelerSmoothedGain = 1f
    private var smoothedGain = 1f

    // One sample of history per saturated band, for oversampledHarmonicSaturate's own
    // interpolation - see that function's doc comment. Distinct from presenceBand/warmthLowPass
    // themselves (which already hold the *current* sample by the time saturation runs) - these
    // trail one sample behind, updated only after each is used.
    private var prevPresenceBand = 0f
    private var prevWarmthLowPass = 0f

    /** Processes the first [length] bytes of [data] in place - 16-bit little-endian mono PCM, the
     * same format/byte order [EloquenceNative.AudioConsumer.onAudioChunk] already delivers and
     * [android.speech.tts.SynthesisCallback.audioAvailable]/[android.media.AudioTrack] both expect
     * back. Odd trailing byte (shouldn't normally happen for 16-bit PCM) is left untouched.
     * [length] is clamped to [data]'s actual size: callers pass the native
     * callback's byte count, which must never read past the array. */
    fun process(data: ByteArray, length: Int) {
        val end = length.coerceIn(0, data.size)
        var i = 0
        while (i + 1 < end) {
            var sample = (((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort()).toFloat()

            // Leveler runs first, on the clean signal, so the tone-shaping stages below see a
            // consistently-leveled input rather than reacting to whatever level happened to come
            // out of a particular syllable.
            levelEnvelope = updateLevelEnvelope(levelEnvelope, abs(sample), levelEnvelopeAlpha)
            val levelerTarget = levelerGain(levelEnvelope)
            levelerSmoothedGain += gainSmoothAlpha * (levelerTarget - levelerSmoothedGain)
            sample *= levelerSmoothedGain

            // Presence: highpass above PRESENCE_LOW_HZ, then lowpass that result below
            // PRESENCE_HIGH_HZ - the two in series isolate the 2.5-5kHz band alone, leaving
            // sibilance above it untouched (see this class's own doc comment).
            val highPass = presenceHighpassAlpha * (prevHighPass + sample - prevInput)
            prevInput = sample
            prevHighPass = highPass
            presenceBand += presenceLowpassAlpha * (highPass - presenceBand)
            sample += oversampledHarmonicSaturate(prevPresenceBand * INV_32768, presenceBand * INV_32768, PRESENCE_DRIVE) * 32768f * PRESENCE_BLEND
            prevPresenceBand = presenceBand

            // Warmth: a plain lowpass near the vocal fundamental, for body.
            warmthLowPass += warmthAlpha * (sample - warmthLowPass)
            sample += oversampledHarmonicSaturate(prevWarmthLowPass * INV_32768, warmthLowPass * INV_32768, WARMTH_DRIVE) * 32768f * WARMTH_BLEND
            prevWarmthLowPass = warmthLowPass

            val targetGain = limiterGainForSample(abs(sample))
            smoothedGain = releaseSmoothedGain(smoothedGain, targetGain)
            sample *= smoothedGain

            val out = sample.toInt().coerceIn(-32768, 32767)
            data[i] = (out and 0xFF).toByte()
            data[i + 1] = ((out shr 8) and 0xFF).toByte()
            i += 2
        }
    }
}
