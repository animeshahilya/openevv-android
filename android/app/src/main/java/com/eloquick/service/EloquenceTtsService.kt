package com.eloquick.service

import android.media.AudioFormat
import android.os.Process
import android.os.SystemClock
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import com.eloquick.EloquenceRevivedApp
import com.eloquick.data.DiagnosticLog
import com.eloquick.data.TtsConfig
import com.eloquick.data.pipelineOptions
import com.eloquick.pipeline.EqClauses
import com.eloquick.pipeline.EqCrashGuards
import com.eloquick.pipeline.EqIndicText
import com.eloquick.tts.AudioOptimizer
import com.eloquick.tts.EloquenceNative
import com.eloquick.tts.PRESET_NAMES
import com.eloquick.tts.SynthesizeParams
import com.eloquick.tts.SUPPORTED_LANGUAGES
import com.eloquick.tts.applyCallerPitch
import com.eloquick.tts.applyCallerRate
import com.eloquick.tts.applyRateBoost
import com.eloquick.tts.chunkRangesForSynthesis
import com.eloquick.tts.clampPreset
import com.eloquick.tts.DictionaryKind
import com.eloquick.tts.LanguageFamily
import com.eloquick.tts.dictionaryFileFor
import com.eloquick.tts.entriesForLanguage
import com.eloquick.tts.encodeForEngineWithOffsets
import com.eloquick.tts.familyForLangId
import com.eloquick.tts.isValidPreset
import com.eloquick.tts.localeFor
import com.eloquick.tts.matchLanguage
import com.eloquick.tts.prepareTextForSynthesis
import com.eloquick.tts.validLangIdOrFallback
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Builds/parses framework voice names in the form "en-US#3". */
private fun voiceName(bcp47: String, preset: Int) = "$bcp47#$preset"

private fun parseVoiceName(name: String): Pair<String, Int>? {
    val hash = name.lastIndexOf('#')
    if (hash <= 0) return null
    val bcp47 = name.substring(0, hash)
    val preset = name.substring(hash + 1).toIntOrNull() ?: return null
    return bcp47 to preset
}

class EloquenceTtsService : TextToSpeechService() {
    private val mStopped = AtomicBoolean(false)
    // Written on onLoadLanguage's binder thread, read on onGetLanguage's
    // and on the synthesis thread - volatile, like mStopped's own atomic,
    // so a voice change is never half-visible across threads.
    @Volatile private var mCurrentLang = "eng"
    @Volatile private var mCurrentCountry = "USA"
    @Volatile private var mCurrentVariant = ""

    // Hang watchdog: drop_slot (evv_tts_jni.c) catches a nativeSynthesize
    // call that *returns* having delivered zero audio, but not one that
    // never returns at all - a genuine native deadlock/infinite loop leaves
    // this service's single synthesis thread blocked forever with nothing
    // to notice, since neither onStop's mStopped flag nor anything else is
    // polled from inside a hung native call. synthActive/lastProgressUptime
    // are updated from onSynthesizeText's own thread (never concurrently
    // with the watchdog's reads, which only ever compare/log) - plain
    // fields would already be safe here, atomics just make that explicit.
    private val synthActive = AtomicBoolean(false)
    private val lastProgressUptime = AtomicLong(0L)
    private var watchdogThread: Thread? = null

    private val app: EloquenceRevivedApp get() = application as EloquenceRevivedApp

    // Shared with AppViewModel's own preview path - see EloquenceRevivedApp.dictCacheDir's own
    // comment.
    private val dictCacheDir: java.io.File get() = app.dictCacheDir

    override fun onCreate() {
        super.onCreate()
        // Corrects the "ui" tag EloquenceRevivedApp.onCreate already set
        // for this process (Application.onCreate runs first, in every
        // process, regardless of which component started it) - see that
        // call site's own comment. Must happen before any DiagnosticLog
        // call below, or this process's own entries would land tagged "ui".
        DiagnosticLog.init(application, "tts")
        // Same guard onSynthesizeText below already applies, and for the
        // same reason (a missing/unloadable native library must not throw
        // UnsatisfiedLinkError out of this call) - missing here specifically
        // was worse than a failed synthesis request: an uncaught throw out
        // of Service.onCreate() takes down the whole :tts process at
        // startup, which Android just restarts on the next TTS request from
        // any app, into the same crash again.
        if (EloquenceNative.isLoaded) {
            EloquenceNative.nativeInit()
        } else {
            DiagnosticLog.e(TAG, "Native library not loaded; TTS service starting without it")
        }
        startHangWatchdog()
        DiagnosticLog.i(TAG, "EloquenceTtsService created")
    }

    override fun onDestroy() {
        watchdogThread?.interrupt()
        super.onDestroy()
    }

    /** Kills this process if a synthesis request is marked active but has
     * delivered no audio/index-mark progress for [HANG_TIMEOUT_MS] - the
     * same recovery Android already performs for a crash, just reached from
     * a hang instead. A killed :tts process is restarted by the framework
     * on the next TTS request; nothing here needs to survive the kill. */
    private fun startHangWatchdog() {
        val thread = Thread({
            while (true) {
                try {
                    Thread.sleep(HANG_CHECK_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                if (synthActive.get()) {
                    val stalledFor = SystemClock.uptimeMillis() - lastProgressUptime.get()
                    if (stalledFor > HANG_TIMEOUT_MS) {
                        // crashSync, not the normal async e() - this line is
                        // the entire point of a Troubleshoot export after a
                        // real hang, and a queued write racing the kill
                        // below is not a durability guarantee (see
                        // DiagnosticLog's own doc comment).
                        DiagnosticLog.crashSync(TAG, "Synthesis stalled for ${stalledFor}ms with no progress - restarting :tts process")
                        Process.killProcess(Process.myPid())
                        return@Thread
                    }
                }
            }
        }, "eloquence-tts-watchdog")
        thread.isDaemon = true
        thread.start()
        watchdogThread = thread
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val match = matchLanguage(lang, country) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        val loc = localeFor(match.bcp47)
        return if (!country.isNullOrEmpty() && loc.country.equals(country, ignoreCase = true)) {
            TextToSpeech.LANG_COUNTRY_AVAILABLE
        } else {
            TextToSpeech.LANG_AVAILABLE
        }
    }

    override fun onGetLanguage(): Array<String> = arrayOf(mCurrentLang, mCurrentCountry, mCurrentVariant)

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val avail = onIsLanguageAvailable(lang, country, variant)
        if (avail != TextToSpeech.LANG_NOT_SUPPORTED) {
            mCurrentLang = lang.orEmpty()
            mCurrentCountry = country.orEmpty()
            mCurrentVariant = variant.orEmpty()
        }
        return avail
    }

    @Suppress("DEPRECATION")
    override fun onGetFeaturesForLanguage(lang: String?, country: String?, variant: String?): Set<String> {
        val avail = onIsLanguageAvailable(lang, country, variant)
        return if (avail != TextToSpeech.LANG_NOT_SUPPORTED) {
            setOf(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS)
        } else {
            emptySet()
        }
    }

    override fun onStop() {
        Log.i(TAG, "onStop")
        mStopped.set(true)
    }

    // Built once: the voice set is compile-time static (SUPPORTED_LANGUAGES
    // x 1..8 presets), so rebuilding 70+ Voice objects on every query -
    // and clients like Settings query repeatedly while open - is pure
    // waste. Returned as a copy: the framework owns the list it gets and
    // is free to sort or filter it, which must never corrupt the next
    // caller's answer; copying 70 references is noise next to one Voice
    // construction. Every voice is fully embedded/offline - signaled via
    // both `requiresNetworkConnection = false` and KEY_FEATURE_EMBEDDED_SYNTHESIS
    // in features for maximum compatibility across TalkBack versions and OEM
    // TTS voice filter queries.
    @Suppress("DEPRECATION")
    private val cachedVoices: List<Voice> by lazy {
        buildList {
            for (lang in SUPPORTED_LANGUAGES) {
                val loc = localeFor(lang.bcp47)
                for (preset in 1..PRESET_NAMES.size) {
                    add(
                        Voice(
                            voiceName(lang.bcp47, preset),
                            loc,
                            Voice.QUALITY_VERY_HIGH,
                            Voice.LATENCY_VERY_LOW,
                            false,
                            setOf(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS),
                        )
                    )
                }
            }
        }
    }

    override fun onGetVoices(): MutableList<Voice> = cachedVoices.toMutableList()

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String {
        val match = matchLanguage(lang, country) ?: SUPPORTED_LANGUAGES.first()
        val cfg = app.preferences.currentConfig()
        val preset = clampPreset(if (cfg.langId == match.langId) cfg.voicePreset else 1)
        return voiceName(match.bcp47, preset)
    }

    override fun onLoadVoice(voiceName: String?): Int = onIsValidVoiceName(voiceName)

    override fun onIsValidVoiceName(voiceName: String?): Int {
        if (voiceName == null) return TextToSpeech.ERROR
        val (bcp47, preset) = parseVoiceName(voiceName) ?: return TextToSpeech.ERROR
        val known = SUPPORTED_LANGUAGES.any { it.bcp47 == bcp47 } && isValidPreset(preset)
        return if (known) TextToSpeech.SUCCESS else TextToSpeech.ERROR
    }

    /** Resolves (langId, preset) for this request: an explicit voice name
     * wins, then a matched language (framework speech-rate/pitch/locale
     * request) keeping the app's default preset, then the app's own default
     * voice untouched. */
    private fun resolveVoice(request: SynthesisRequest, cfg: TtsConfig): Pair<Int, Int> {
        val requestedVoice = request.voiceName
        if (!requestedVoice.isNullOrEmpty()) {
            parseVoiceName(requestedVoice)?.let { (bcp47, preset) ->
                val lang = SUPPORTED_LANGUAGES.firstOrNull { it.bcp47 == bcp47 }
                // Clamped, not rejected: a stale "#9" from an older build
                // must still speak (on the nearest preset), never hand the
                // native side a preset it silently ignores while keeping a
                // previous utterance's voice on a reused slot.
                if (lang != null) return lang.langId to clampPreset(preset)
            }
        }
        val matched = matchLanguage(request.language, request.country)
        if (matched != null) return matched.langId to clampPreset(cfg.voicePreset)
        // cfg.langId comes from disk (restores, downgrades, corruption can
        // all leave a value this build has no voice for) - validate it the
        // same way NVDA's driver does instead of handing native code a
        // language id it can't load.
        val deviceLocale = java.util.Locale.getDefault()
        val safeLang = validLangIdOrFallback(cfg.langId, deviceLocale.language, deviceLocale.country)
        return safeLang to clampPreset(cfg.voicePreset)
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        // The framework's synthesis thread otherwise runs at the default
        // priority - fine in isolation, but a screen reader's speech is
        // latency-sensitive in a way that ordinary priority doesn't protect
        // against on a busy device (another app's animation, a background
        // sync, all scheduled as equals). THREAD_PRIORITY_URGENT_AUDIO is
        // the same class AudioTrack/AudioFlinger's own mixer threads run
        // at - device-specific in effect (it only matters under real
        // contention) though the call itself is identical on every device.
        // The previous priority is restored below: the framework may reuse
        // this thread for later, non-audio work that must not inherit it.
        val oldPriority = Process.getThreadPriority(Process.myTid())
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        try {
            synthesizeInternal(request, callback)
        } catch (t: Throwable) {
            // A throw anywhere below (pipeline, prefs, dictionary, a dead
            // binder under callback.*) must fail this one request, never
            // escape the binder thread with the framework still waiting on
            // done()/error().
            DiagnosticLog.e(TAG, "Synthesis failed", t)
            runCatching { callback.error(TextToSpeech.ERROR_SYNTHESIS) }
        } finally {
            runCatching { Process.setThreadPriority(oldPriority) }
        }
    }

    private fun isCallbackFinished(callback: SynthesisCallback): Boolean =
        runCatching { callback.hasFinished() }.getOrDefault(false)

    private fun isCancelledOrFinished(callback: SynthesisCallback): Boolean =
        mStopped.get() || isCallbackFinished(callback)

    // The whole request body, factored out of onSynthesizeText so the
    // thread-priority save/restore and the fail-the-request catch above
    // wrap every return path below - early returns used to leak the
    // urgent-audio priority onto whatever the framework ran next on a
    // reused thread, and throws used to hang the caller with no done().
    private fun synthesizeInternal(request: SynthesisRequest, callback: SynthesisCallback) {
        mStopped.set(false)
        if (isCallbackFinished(callback)) {
            Log.i(TAG, "Request callback already finished before synthesis started")
            return
        }
        // A missing or unloadable native library (corrupt install, ABI
        // mismatch) would otherwise throw UnsatisfiedLinkError out of the
        // call below and take down the system TTS binder thread - fail this
        // one request cleanly instead so the framework can fall back.
        if (!EloquenceNative.isLoaded) {
            DiagnosticLog.e(TAG, "Synthesis requested but native library is not loaded")
            if (!isCallbackFinished(callback)) {
                runCatching { callback.error(TextToSpeech.ERROR_SERVICE) }
            }
            return
        }
        val rawText = request.charSequenceText?.toString().orEmpty()
        val cfg = app.preferences.currentConfig()
        val sampleRate = EloquenceNative.resolveSampleRateHz(cfg.sampleRateHz)

        if (rawText.isEmpty()) {
            if (!isCancelledOrFinished(callback)) {
                if (callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) {
                    runCatching { callback.error(TextToSpeech.ERROR_SYNTHESIS) }
                    return
                }
                runCatching { callback.done() }
            } else if (!isCallbackFinished(callback)) {
                @Suppress("WrongConstant")
                runCatching { callback.error(TextToSpeech.STOPPED) }
            }
            return
        }

        // The voice is resolved first so the text pipeline knows which
        // language family's extra fixes apply to this exact utterance (a
        // Spanish voice pinned by name on an English-default install still
        // gets the Spanish rules).
        val (langId, preset) = resolveVoice(request, cfg)
        val family = familyForLangId(langId)
        val bcp47 = SUPPORTED_LANGUAGES.firstOrNull { it.langId == langId }?.bcp47.orEmpty()

        // Raw-text cleanup, in remit order: whitespace/ellipsis
        // normalization, then Indian-script preprocessing (both no-ops
        // outside their remit), then the crash-guard pre-filters
        // (NVDA-IBMTTS-Driver's IBM tables, see EqCrashGuards) - all on the
        // raw request text, before anything deletes, reflows, or annotates
        // it. The backquote strip must see the original hyphens, and later
        // stages add their own annotations only after this has run.
        val guardedRaw = EqCrashGuards.apply(
            EqIndicText.apply(EqClauses.normalize(rawText), bcp47),
            when (family) {
                LanguageFamily.ENGLISH -> "eng"
                LanguageFamily.SPANISH -> "spa"
                LanguageFamily.FRENCH -> "fra"
                LanguageFamily.GERMAN -> "deu"
                LanguageFamily.OTHER -> ""
            },
        )

        // One shared pipeline (see prepareTextForSynthesis): punctuation
        // handling first - it deletes/reflows text - then the engine fixes,
        // which see the text closest to what the engine receives. The
        // in-app voice preview runs this same function, so sampling a voice
        // is what TalkBack actually gets.
        val text = prepareTextForSynthesis(
            guardedRaw,
            family,
            cfg.pipelineOptions(),
        )
        // Nothing left to say (e.g. a lone bullet the QUIET strip removed):
        // report done without touching native code. This also keeps the
        // native side's new "zero audio delivered means a dead engine"
        // accounting honest - it must never see an utterance that was
        // empty by construction, or it would drop a healthy slot over it.
        if (text.isBlank()) {
            if (!isCancelledOrFinished(callback)) {
                if (callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) {
                    runCatching { callback.error(TextToSpeech.ERROR_SYNTHESIS) }
                    return
                }
                runCatching { callback.done() }
            } else if (!isCallbackFinished(callback)) {
                @Suppress("WrongConstant")
                runCatching { callback.error(TextToSpeech.STOPPED) }
            }
            return
        }
        // Written once per request rather than per chunk below - the same
        // dictionary applies to every chunk of one utterance, and
        // dictionaryFileFor is already a no-op write when the content
        // hasn't changed since the last call (content-addressed file name).
        // Only the user's own entries - nothing this app injects on its own.
        // This app has twice tried auto-injecting a built-in dictionary on
        // top of the user's own (first the bundled community "Word Wisdom"
        // list, then a small hand-verified "known initialism" list), and
        // both were removed 2026-09-11 at the user's explicit request: any
        // reading this app alters on its own, beyond what a person typed
        // into their own Pronunciation dictionary, is unwanted - the engine
        // should read exactly as upstream openevv does by default. See
        // mergeDictionaries' own doc comment for the fuller history.
        // Device-protected cache, not plain cacheDir: before first unlock
        // the credential-encrypted cache is unusable, and the dictionary
        // file must be writable there too (a failed write just means no
        // user dictionary for that utterance, but there is no reason to
        // accept that). Same location the preview path uses, so the
        // content-addressed memo stays shared across both callers.
        // cfg comes from currentConfig(), which for this cross-process (:tts) service almost
        // always means the Binder Bundle path - resolvedDictPath is already the answer in that
        // case (see TtsConfig.resolvedDictPath's own doc comment for why calling
        // dictionaryFileFor here with cfg.pronunciationDictionary would be wrong: that list is
        // always empty from a Bundle). Falls back to resolving from entries directly only if the
        // provider call itself failed and currentConfig() fell back to a local loadConfig().
        val dictPath = cfg.resolvedDictPath
            ?: dictionaryFileFor(entriesForLanguage(cfg.pronunciationDictionary, cfg.langId), dictCacheDir)
        // Same reasoning as dictPath above, own volume/screen/file - see
        // EloquenceNative.nativeSynthesize's own doc comment on abbvDictPath.
        val abbvDictPath = cfg.resolvedAbbvDictPath
            ?: dictionaryFileFor(cfg.abbreviationDictionary, dictCacheDir, DictionaryKind.ABBREVIATION)

        if (isCancelledOrFinished(callback)) {
            Log.i(TAG, "Synthesis stopped before audio start")
            if (!isCallbackFinished(callback)) {
                @Suppress("WrongConstant")
                runCatching { callback.error(TextToSpeech.STOPPED) }
            }
            return
        }

        // A rejected start (the HAL refused this sample rate on this
        // device) must fail the request now, not proceed into audioAvailable
        // calls guaranteed to fail under it.
        if (callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) {
            DiagnosticLog.e(TAG, "SynthesisCallback.start rejected sample rate $sampleRate")
            if (!isCallbackFinished(callback)) {
                runCatching { callback.error(TextToSpeech.ERROR_SYNTHESIS) }
            }
            return
        }

        // The framework's own contract for audioAvailable(): "length must
        // not be larger than getMaxBufferSize()". This engine's native side
        // hands each callback a FRAME-sized (4096-byte) chunk, which happens
        // to fit under every buffer size actually seen on real devices - but
        // "happens to fit today" isn't the same as honoring the contract,
        // and a caller (this app has no control over which - a different
        // OEM build, a future Android version, another engine host
        // process entirely for synthesizeToFile) is free to report a
        // smaller one. Split defensively rather than assume; see
        // onAudioChunk below.
        val maxBufferSize = callback.getMaxBufferSize()

        // One instance for the whole request (every chunkRangesForSynthesis
        // piece below is the same utterance split for the engine's own
        // sake, not a new one) - see AudioOptimizer's own doc comment for
        // why it must not be shared across separate onSynthesizeText calls.
        val optimizer = AudioOptimizer.maybe(cfg.audioOptimizerEnabled, sampleRate)

        var lastMark = 0
        var chunkBaseOffset = 0
        var deliveredBytes = 0
        // Maps a byte offset into the *current* chunk's encoded bytes back
        // to a char offset into that same chunk's text - see
        // engineByteToCharOffsets' own doc comment for why this can't just
        // be added to chunkBaseOffset directly (the native engine's mark is
        // a byte position, not a char position, and the two only coincide
        // when nothing in the chunk needed encodeForEngine's multi-byte
        // BEST_FIT_FALLBACK or its single-byte supplementary-code-point
        // '?' collapse). Empty array is a safe initial value: onIndexMark
        // is only ever invoked by the native call inside the loop below,
        // by which point this has already been set for that call's chunk.
        var chunkByteToChar = IntArray(0)
        val consumer = object : EloquenceNative.AudioConsumer {
            override fun onAudioChunk(data: ByteArray, length: Int): Boolean {
                if (isCancelledOrFinished(callback)) return false
                lastProgressUptime.set(SystemClock.uptimeMillis())
                // Runs once over the whole chunk before any splitting below:
                // AudioOptimizer.process mutates data in place and carries
                // filter/leveler state from call to call, so it needs the
                // one true contiguous view of this chunk, not several
                // sub-slices of it.
                optimizer?.process(data, length)
                // Split to respect maxBufferSize with zero heap allocation on this audio thread
                if (length <= 0) return true
                if (maxBufferSize <= 0 || length <= maxBufferSize) {
                    val res = callback.audioAvailable(data, 0, length)
                    if (res == TextToSpeech.SUCCESS) deliveredBytes += length
                    return res == TextToSpeech.SUCCESS && !isCancelledOrFinished(callback)
                }
                val pieceSize = (maxBufferSize / 2 * 2).coerceIn(1, maxBufferSize)
                var offset = 0
                while (offset < length) {
                    val pieceLen = pieceSize.coerceAtMost(length - offset)
                    val res = callback.audioAvailable(data, offset, pieceLen)
                    if (res != TextToSpeech.SUCCESS || isCancelledOrFinished(callback)) return false
                    deliveredBytes += pieceLen
                    offset += pieceLen
                }
                return true
            }

            override fun onIndexMark(charOffset: Int) {
                if (isCancelledOrFinished(callback)) return
                lastProgressUptime.set(SystemClock.uptimeMillis())
                // Sentence-level only (not word-level) - best-effort TalkBack
                // progress highlighting per the native contract. Despite the
                // parameter's own name, this is a *byte* offset into the
                // chunk encodeForEngine produced (the engine has no concept
                // of a Kotlin String), translated back to a char offset via
                // chunkByteToChar before chunkBaseOffset - relative to
                // whichever chunk is currently synthesizing (see
                // chunkForSynthesis below) - turns it into a position in the
                // original text. Clamped (see clampHighlightEnd): a mark
                // that runs backwards (engine quirk, or a stale callback
                // from a chunk that already finished) or past the request
                // text (the pipeline rewrites text before synthesis -
                // emoji/dictionary expansions make the spoken text longer
                // than what TalkBack asked about) must never reach
                // rangeStart as an inverted or over-long range - that throws
                // on the binder thread and kills the whole synthesis
                // request.
                val chunkCharOffset = if (chunkByteToChar.isEmpty()) {
                    0
                } else {
                    chunkByteToChar[charOffset.coerceIn(0, chunkByteToChar.size - 1)]
                }
                val end = clampHighlightEnd(lastMark, chunkBaseOffset + chunkCharOffset, rawText.length)
                if (end != null && end > lastMark) {
                    val deliveredFrames = deliveredBytes / 2
                    runCatching { callback.rangeStart(deliveredFrames, lastMark, end) }
                    lastMark = end
                }
            }
        }

        // Honor the *caller's* requested rate/pitch (e.g. TalkBack's own
        // speech-rate slider) unless the user has explicitly locked this
        // app's own Tuning value regardless of what asks for speech - see
        // applyCallerRate/applyCallerPitch. This app used to never read
        // request.getSpeechRate()/getPitch() at all, which meant the
        // caller's own rate control silently did nothing.
        val requestedSpeed = if (cfg.forceSpeed) cfg.speed else applyCallerRate(cfg.speed, request.speechRate, cfg.realWorldUnits)
        val speed = applyRateBoost(requestedSpeed, cfg.rateBoostMultiplier, cfg.realWorldUnits)
        val pitchBaseline = if (cfg.forcePitch) cfg.pitchBaseline else applyCallerPitch(cfg.pitchBaseline, request.pitch, cfg.realWorldUnits)

        // Very long text (a whole log dump, a text-editor line with no word
        // wrap) is split into sentence-sized pieces and synthesized one at a
        // time - openevv's engine family has a documented history of
        // hanging/crashing on one enormous unbroken utterance (see
        // chunkRangesForSynthesis). Ordinary speech never has more than one
        // chunk, so this loop runs once for the overwhelming majority of
        // requests. ssmlInput and upsampleMethod are no longer
        // user-configurable (see TtsConfig): SSML was a global markup
        // toggle with no benefit to a screen-reader user and a real risk of
        // mangling ordinary text (angle brackets in code/URLs), and upsample
        // method was a DSP choice folded into Audio quality, which always
        // uses the engine's best/default method (null).
        synthActive.set(true)
        lastProgressUptime.set(SystemClock.uptimeMillis())
        var success = true
        try {
        for (range in chunkRangesForSynthesis(text)) {
            if (isCancelledOrFinished(callback)) break
            chunkBaseOffset = range.first
            // Encoding is the very last step, after every Kotlin-String-based
            // text fix has run - the native call now takes raw pre-encoded
            // bytes and does no encoding decision of its own.
            val chunkText = text.substring(range.first, range.last + 1)
            // Both pieces from one encoding pass - see encodeForEngineWithOffsets' own doc
            // comment. Built alongside encodedChunk, not lazily inside onIndexMark: onIndexMark
            // fires from the native callback below, potentially many times per chunk, and byte
            // offsets need to already exist by then, not be recomputed per callback.
            val encoded = encodeForEngineWithOffsets(chunkText, langId)
            val encodedChunk = encoded.bytes
            chunkByteToChar = encoded.byteToChar
            // Local so the two call sites below (first try, one retry) can't
            // drift out of sync with each other's arguments.
            val synthesizeChunk = {
                EloquenceNative.synthesize(
                    SynthesizeParams(
                        langId = langId,
                        text = encodedChunk,
                        voicePreset = preset,
                        headSize = cfg.headSize,
                        pitchBaseline = pitchBaseline,
                        pitchFluctuation = cfg.pitchFluctuation,
                        roughness = cfg.roughness,
                        breathiness = cfg.breathiness,
                        speed = speed,
                        volume = cfg.volume,
                        realWorldUnits = cfg.realWorldUnits,
                        sampleRateHz = sampleRate,
                        sentencePauseMs = cfg.sentencePauseMs,
                        abbreviationExpansion = cfg.abbreviationExpansion,
                        dictPath = dictPath,
                        abbvDictPath = abbvDictPath,
                        callback = consumer,
                    )
                )
            }
            success = synthesizeChunk()
            // A `false` here (and mStopped still false, so this wasn't a
            // user-initiated cancel) means evv_tts_jni.c's nativeSynthesize
            // ran this chunk to completion but delivered zero audio - its own
            // drop_slot comment explains why: an abandoned/wedged ECIHand
            // that answers "success" yet never speaks again. The native side
            // already evicts that slot before returning, so the very next
            // nativeSynthesize call for this same (langId, filters) rebuilds
            // a brand-new engine instance from scratch via find_or_make_slot
            // - one retry turns what used to be a silently dropped utterance
            // (this is the "Synthesis failed" pattern seen repeating in a
            // 2026-09-11 field diagnostics export, several times in a few
            // minutes of ordinary use) into, at worst, one extra engine
            // build's worth of latency.
            if (!success && !isCancelledOrFinished(callback)) {
                DiagnosticLog.w(TAG, "Synthesis chunk delivered no audio; retrying once on a fresh engine slot")
                success = synthesizeChunk()
            }
            if (!success) break
        }
        } finally {
            synthActive.set(false)
        }

        if (!isCallbackFinished(callback)) {
            when {
                mStopped.get() -> {
                    Log.i(TAG, "Synthesis stopped/cancelled")
                    @Suppress("WrongConstant")
                    runCatching { callback.error(TextToSpeech.STOPPED) }
                }
                success -> runCatching { callback.done() }
                else -> {
                    DiagnosticLog.e(TAG, "Synthesis failed")
                    runCatching { callback.error(TextToSpeech.ERROR_SYNTHESIS) }
                }
            }
        }
    }

    companion object {
        private const val TAG = "EloquenceTtsService"

        // How often the watchdog thread wakes to check for a stall, and how
        // long a synthesis request may go without any progress before it's
        // treated as genuinely hung rather than just working on a slow
        // chunk. Normal synthesis calls back many times per second (once
        // per FRAME's worth of audio, or on an index mark), so this leaves
        // wide headroom against a false positive on a slow device while
        // still recovering within a few checks of a real deadlock.
        private const val HANG_CHECK_INTERVAL_MS = 2_000L
        private const val HANG_TIMEOUT_MS = 10_000L
    }
}

/**
 * Clamps a TalkBack highlight end offset to a range `rangeStart` accepts:
 * returns null when the mark ran backwards (stale/quirky engine callback -
 * the caller keeps the previous mark), otherwise the offset capped at the
 * request text length (the synthesis pipeline rewrites text - emoji
 * descriptions, dictionary expansions - so native offsets routinely run
 * past what the caller asked about). Pure function so the rule is
 * unit-testable without a binder thread.
 */
internal fun clampHighlightEnd(lastMark: Int, offset: Int, textLength: Int): Int? {
    if (offset < lastMark) return null
    return offset.coerceAtMost(textLength)
}

/**
 * Splits a [length]-byte audio chunk into consecutive (offset, byte count)
 * pieces, each no larger than [maxBufferSize] - what [SynthesisCallback
 * .audioAvailable]'s own contract requires ("length must not be larger than
 * getMaxBufferSize()"), which this engine's native side doesn't itself know
 * or enforce (see onSynthesizeText's own comment on why this exists at
 * all). Each piece is rounded down to an even byte count so a split can
 * never land mid-sample (this engine's audio is always 16-bit PCM); the
 * final piece for a chunk absorbs whatever's left over, however small.
 *
 * [maxBufferSize] of zero or less - the API gives no documented floor,
 * though a real implementation always returns a positive size - is treated
 * as "no limit": one piece covering the whole chunk, same as
 * `length <= maxBufferSize` would produce anyway. Pure function so the
 * splitting math is unit-testable without a real [SynthesisCallback].
 */
internal fun audioChunkPieces(length: Int, maxBufferSize: Int): List<Pair<Int, Int>> {
    if (length <= 0) return emptyList()
    if (maxBufferSize <= 0 || length <= maxBufferSize) return listOf(0 to length)
    // Largest even size within maxBufferSize: an odd split would land
    // mid-sample in 16-bit PCM. Bounded above by maxBufferSize itself, so
    // even a degenerate max of 1 honors the length contract (alignment is
    // best-effort there - no even piece can fit in 1 byte).
    val pieceSize = (maxBufferSize / 2 * 2).coerceIn(1, maxBufferSize)
    val pieces = mutableListOf<Pair<Int, Int>>()
    var offset = 0
    while (offset < length) {
        val pieceLen = pieceSize.coerceAtMost(length - offset)
        pieces.add(offset to pieceLen)
        offset += pieceLen
    }
    return pieces
}
