package com.eloquick.ui

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eloquick.EloquenceRevivedApp
import com.eloquick.data.TtsConfig
import com.eloquick.data.pipelineOptions
import com.eloquick.pipeline.EqClauses
import com.eloquick.pipeline.EqCrashGuards
import com.eloquick.pipeline.EqIndicText
import com.eloquick.tts.AudioPlayer
import com.eloquick.tts.EloquenceNative
import com.eloquick.tts.DictionaryKind
import com.eloquick.tts.LanguageFamily
import com.eloquick.tts.dictionaryFileFor
import com.eloquick.tts.entriesForLanguage
import com.eloquick.tts.encodeForEngine
import com.eloquick.tts.NumberReadingStyle
import com.eloquick.tts.CommunityDictionaryUpdateCheck
import com.eloquick.tts.DictionaryMergeResult
import com.eloquick.tts.PronunciationEntry
import com.eloquick.tts.PunctuationMode
import com.eloquick.tts.PunctuationPreset
import com.eloquick.tts.DateOrder
import com.eloquick.tts.applyRateBoost
import com.eloquick.tts.checkCommunityDictionaryUpdate
import com.eloquick.tts.decodeImportedDictionaryBytes
import com.eloquick.tts.decodePronunciationDictionary
import com.eloquick.tts.downloadCommunityDictionaryUpdate
import com.eloquick.tts.familyForBcp47
import com.eloquick.tts.mergeDictionaries
import com.eloquick.tts.prepareTextForSynthesis
import com.eloquick.tts.sampleTextFor
import com.eloquick.tts.voiceKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val config: TtsConfig = TtsConfig(),
    // Key of the voice currently being previewed ("langId:preset"), null when idle.
    val previewingKey: String? = null,
    val error: String? = null,
    // Bumps on every showError so MainActivity's snackbar effect keys on
    // this, not on the message: two identical errors in a row (or a second
    // error arriving while the first is still showing) would otherwise
    // coalesce into one effect run and swallow the second.
    val errorSeq: Int = 0,
    // Scratch text for "try your own text": lives here instead of in the
    // section's own rememberSaveable so navigating away (to switch voice,
    // then back) doesn't eat what was typed - rotation was never the risk,
    // leaving the section was. Deliberately not a persisted setting.
    val customPreviewText: String = "",
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val eloquenceApp = app as EloquenceRevivedApp
    private val prefs = eloquenceApp.preferences
    private val player = AudioPlayer(app)

    private val _uiState = MutableStateFlow(UiState(config = prefs.config.value))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * Human label of the system's *currently active* TTS engine, or null
     * when this app already is it (the steady state - the banner stays
     * hidden then so TalkBack has one less swipe). Refreshed on every
     * resume (see MainActivity): the user may have just switched engines in
     * system settings and come back. A plain Secure-settings read - no
     * permission needed to read this key, main-thread safe.
     */
    private val _systemEngineLabel = MutableStateFlow<String?>(null)
    val systemEngineLabel: StateFlow<String?> = _systemEngineLabel.asStateFlow()

    fun refreshSystemEngineStatus() {
        val app = eloquenceApp
        val current = runCatching {
            Settings.Secure.getString(app.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
        }.getOrNull()
        _systemEngineLabel.value = if (current == app.packageName) {
            null
        } else {
            engineLabelFor(app, current)
        }
    }

    private fun engineLabelFor(app: EloquenceRevivedApp, packageName: String?): String {
        if (packageName.isNullOrEmpty()) return "no engine yet"
        return runCatching {
            app.packageManager.getApplicationInfo(packageName, 0)
                .loadLabel(app.packageManager).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: packageName
    }

    fun showError(message: String) = _uiState.update { it.copy(error = message, errorSeq = it.errorSeq + 1) }

    /**
     * The currently running preview coroutine, if any. Two rapid preview
     * taps used to run two overlapping syntheses (plus a shared AudioPlayer
     * with no generation guard): stale audio played over the new voice and
     * the superseded tap flashed a bogus "couldn't preview" error. Now a
     * new preview cancels the old job first, and only the current job may
     * report errors or clear [UiState.previewingKey].
     */
    private var previewJob: kotlinx.coroutines.Job? = null

    /**
     * A fresh identity per preview tap, checked from inside the coroutine
     * instead of comparing against [previewJob] itself - deliberately, not
     * a style choice. `viewModelScope` runs on `Dispatchers.Main.immediate`,
     * so when [previewVoice] is called from the main thread (the normal
     * case), the `launch {}` body can start running *synchronously*, before
     * the `previewJob = viewModelScope.launch { ... }` assignment below it
     * has actually completed - a real window (however narrow) where reading
     * [previewJob] from inside that very closure would see whatever it held
     * *before* this call, not the job that's currently running. This field
     * is instead written before `launch` is even called, so by the time the
     * coroutine body runs - synchronously or not - it's already correct;
     * the coroutine's own closure captures the local `token` it was given
     * directly, with no dependency on outer-variable assignment timing at
     * all.
     */
    private var previewToken: Any? = null

    init {
        viewModelScope.launch {
            prefs.config.collect { cfg -> _uiState.update { it.copy(config = cfg) } }
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    fun setCustomPreviewText(text: String) = _uiState.update { it.copy(customPreviewText = text) }

    // ---- Voice selection ----

    fun selectVoice(langId: Int, preset: Int) = prefs.setVoice(langId, preset)

    // ---- Tuning ----

    fun setHeadSize(v: Int) = prefs.setHeadSize(v)
    fun setPitchBaseline(v: Int) = prefs.setPitchBaseline(v)
    fun setPitchFluctuation(v: Int) = prefs.setPitchFluctuation(v)
    fun setRoughness(v: Int) = prefs.setRoughness(v)
    fun setBreathiness(v: Int) = prefs.setBreathiness(v)
    fun setSpeed(v: Int) = prefs.setSpeed(v)
    fun setVolume(v: Int) = prefs.setVolume(v)
    fun setRealWorldUnits(v: Boolean) = prefs.setRealWorldUnits(v)
    fun setRateBoostMultiplier(v: Float) = prefs.setRateBoostMultiplier(v)
    /**
     * Resets every tuning setting at once - the screen no longer offers a
     * per-parameter "use voice default"/"force" switch (removed for being
     * clutter around eight sliders that all needed the same undo), so this
     * single button is the only way back to defaults and has to actually
     * cover everything, not just the four params that used to have their
     * own switch. All eight voice params (speed, pitch baseline, and volume
     * included - see TtsConfig.speed's own comment) share one "voice
     * default" sentinel, -1, so resetting every one of them back to it is
     * this simple.
     */
    fun resetTuning() {
        prefs.setHeadSize(-1)
        prefs.setPitchBaseline(-1)
        prefs.setPitchFluctuation(-1)
        prefs.setRoughness(-1)
        prefs.setBreathiness(-1)
        prefs.setSpeed(-1)
        prefs.setVolume(-1)
        prefs.setRateBoostMultiplier(1f)
        prefs.setForceSpeed(false)
        prefs.setForcePitch(false)
    }

    // ---- Reading (text processing) ----

    fun setSampleRateHz(v: Int) = prefs.setSampleRateHz(v)
    fun setSentencePauseMs(v: Int) = prefs.setSentencePauseMs(v)
    fun setPunctuationMode(v: PunctuationMode) = prefs.setPunctuationMode(v)
    fun setAbbreviationExpansion(v: Boolean) = prefs.setAbbreviationExpansion(v)
    fun setDescribeEmoji(v: Boolean) = prefs.setDescribeEmoji(v)
    fun setNumberReadingStyle(v: NumberReadingStyle) = prefs.setNumberReadingStyle(v)
    fun setDigitGroupSize(v: Int) = prefs.setDigitGroupSize(v)
    fun setAlwaysReadSymbols(v: String) = prefs.setAlwaysReadSymbols(v)
    fun setReadSymbolsByName(v: Boolean) = prefs.setReadSymbolsByName(v)
    fun setPunctuationPreset(v: PunctuationPreset) = prefs.setPunctuationPreset(v)
    fun setNaturalTimeReading(v: Boolean) = prefs.setNaturalTimeReading(v)
    fun setNaturalDateReading(v: Boolean) = prefs.setNaturalDateReading(v)
    fun setDateOrder(v: DateOrder) = prefs.setDateOrder(v)
    fun setPronunciationDictionary(entries: List<PronunciationEntry>) = prefs.setPronunciationDictionary(entries)
    fun setAbbreviationDictionary(entries: List<PronunciationEntry>) = prefs.setAbbreviationDictionary(entries)

    /**
     * Asks GitHub whether the community dictionary bundled in this app has
     * moved on since [com.eloquick.data.AppPreferences.communityDictionaryKnownSha].
     * The only network call in this app, and only ever made from here -
     * from an explicit "check for updates" tap - never on startup, never on
     * a timer.
     */
    suspend fun checkForCommunityDictionaryUpdate(): CommunityDictionaryUpdateCheck = withContext(Dispatchers.IO) {
        checkCommunityDictionaryUpdate(prefs.communityDictionaryKnownSha())
    }

    /**
     * Downloads [downloadUrl] (from a prior [checkCommunityDictionaryUpdate]
     * the user has already agreed to) and merges whatever's new into the
     * Pronunciation dictionary - never touching an entry already there,
     * same as importing any other file (see [mergeDictionaries]). Records
     * [sha] as the new known state so the next check reports up to date.
     */
    suspend fun applyCommunityDictionaryUpdate(downloadUrl: String, sha: String): DictionaryMergeResult =
        withContext(Dispatchers.IO) {
            val bytes = downloadCommunityDictionaryUpdate(downloadUrl)
            val incoming = decodePronunciationDictionary(decodeImportedDictionaryBytes(bytes))
            val result = mergeDictionaries(prefs.config.value.pronunciationDictionary, incoming)
            prefs.setPronunciationDictionary(result.entries)
            prefs.recordCommunityDictionaryMerge(sha)
            result
        }

    /**
     * Asks the engine's own letter-to-sound rules what [word] would sound
     * like by default, as a phonetic annotation in the engine's own syntax
     * (`` `[.2hE.1lo] `` for "hello") - a starting point for the
     * Pronunciation dictionary's Replace field, not a final answer: this is
     * exactly the syntax [PronunciationDictionary]'s own dictionary-file
     * writer already passes straight through to `eciSetDict` untouched, so
     * whatever comes back here is directly usable as-typed, or as a base to
     * hand-tune. Uses the *current* voice's language, matching what the
     * dictionary screen's entries actually apply to. Null on failure (a
     * blank word, or the engine refusing) - the caller leaves the field
     * alone rather than clobbering it with nothing.
     */
    suspend fun suggestPhoneticSpelling(word: String): String? {
        if (word.isBlank() || !EloquenceNative.isLoaded) return null
        val langId = prefs.config.value.langId
        return withContext(Dispatchers.IO) {
            EloquenceNative.nativeGeneratePhonemes(langId, encodeForEngine(word, langId))
        }
    }
    fun setAudioOptimizerEnabled(v: Boolean) = prefs.setAudioOptimizerEnabled(v)
    fun setEliminateRepeats(v: Boolean) = prefs.setEliminateRepeats(v)
    fun setRepeatThreshold(v: Int) = prefs.setRepeatThreshold(v)

    // ---- Preview playback ----

    /** Previews [langId]/[preset] with the app's *current* tuning settings
     * (so the tuning screen's sliders feel live), speaking a short sample
     * sentence in that language - or [text], when the "try your own text"
     * box supplies one (blank falls back to the sample: there is nothing
     * to synthesize in an empty string, and the pipeline would just
     * report done). */
    fun previewVoice(langId: Int, preset: Int, bcp47: String, text: String? = null) {
        val key = voiceKey(langId, preset)
        stopPreview()
        _uiState.update { it.copy(previewingKey = key, error = null) }
        // prefs.config, not _uiState's copy: every setter updates the
        // source flow synchronously while _uiState only mirrors it on the
        // next collect emission - reading _uiState here would preview the
        // PREVIOUS settings when a choice auto-previews on the same tap
        // that changed it (voice/language/dropdown/toggle confirmations).
        val cfg = prefs.config.value
        if (!EloquenceNative.isLoaded) {
            _uiState.update { if (it.previewingKey == key) it.copy(previewingKey = null) else it }
            // showError, not a bare copy(error = ...): the snackbar effect
            // keys on errorSeq, which only showError bumps.
            showError("Voice engine library is missing - reinstall the app.")
            return
        }
        // Written before launch, not after - see previewToken's own doc comment.
        val token = Any()
        previewToken = token
        previewJob = viewModelScope.launch {
            // Only the current job reports: a coroutine cancelled by a newer
            // preview tap must stay silent (its failure is supersession, not
            // an error) and must not clear the new tap's previewingKey.
            val isCurrent = { previewToken === token }
            try {
                // Same pipeline the system service runs (punctuation handling
                // honors the user's verbosity choice here too), so the sample
                // is what TalkBack would actually get - previously the preview
                // skipped every text fix. Crash guards included, same as the
                // service path, so a preview that crashes is a real crash.
                // Normalize + Indic stages match the service order too.
                val guardedPreview = EqCrashGuards.apply(
                    EqIndicText.apply(
                        EqClauses.normalize(
                            if (text.isNullOrBlank()) sampleTextFor(bcp47) else text,
                        ),
                        bcp47,
                    ),
                    when (familyForBcp47(bcp47)) {
                        LanguageFamily.ENGLISH -> "eng"
                        LanguageFamily.SPANISH -> "spa"
                        LanguageFamily.FRENCH -> "fra"
                        LanguageFamily.GERMAN -> "deu"
                        LanguageFamily.OTHER -> ""
                    },
                )
                val previewText = prepareTextForSynthesis(
                    guardedPreview,
                    familyForBcp47(bcp47),
                    cfg.pipelineOptions(),
                )
                // The service answers blank text with done() and never
                // touches native code; the preview must do the same instead
                // of pointlessly opening an AudioTrack for silence (e.g. a
                // custom "..." under QUIET stripping).
                if (previewText.isBlank()) {
                    if (isCurrent()) {
                        showError("Nothing to preview after text cleanup.")
                    }
                    return@launch
                }
                val ok = withContext(Dispatchers.IO) {
                    // Only the user's own dictionary entries - see
                    // mergeDictionaries' own doc comment for why this app no
                    // longer auto-injects anything of its own here.
                    // Device-protected cache, matching the service path (see
                    // EloquenceRevivedApp.dictCacheDir's own comment) - one
                    // shared location, so the dictionary memo stays shared
                    // across both callers.
                    val dictPath = dictionaryFileFor(
                        // This function's own langId param (the voice actually being
                        // previewed), not cfg.langId (the app's separately-configured
                        // default) - previewing a non-default language must filter by
                        // that language, not whichever one happens to be the default.
                        entriesForLanguage(cfg.pronunciationDictionary, langId),
                        eloquenceApp.dictCacheDir,
                    )
                    val abbvDictPath = dictionaryFileFor(
                        cfg.abbreviationDictionary,
                        eloquenceApp.dictCacheDir,
                        DictionaryKind.ABBREVIATION,
                    )
                    player.speakBlocking(
                        langId = langId,
                        text = previewText,
                        voicePreset = preset,
                        headSize = cfg.headSize,
                        pitchBaseline = cfg.pitchBaseline,
                        pitchFluctuation = cfg.pitchFluctuation,
                        roughness = cfg.roughness,
                        breathiness = cfg.breathiness,
                        speed = applyRateBoost(cfg.speed, cfg.rateBoostMultiplier, cfg.realWorldUnits),
                        volume = cfg.volume,
                        realWorldUnits = cfg.realWorldUnits,
                        sampleRateHz = cfg.sampleRateHz,
                        sentencePauseMs = cfg.sentencePauseMs,
                        abbreviationExpansion = cfg.abbreviationExpansion,
                        audioOptimizerEnabled = cfg.audioOptimizerEnabled,
                        dictPath = dictPath,
                        abbvDictPath = abbvDictPath,
                    )
                }
                if (!ok && isCurrent()) {
                    showError("Couldn't preview this voice.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Throwable, not Exception: a native failure surfaces as
                // UnsatisfiedLinkError/related Errors, which must show as a
                // message, never crash the app out of a preview tap.
                if (isCurrent()) {
                    showError("Couldn't preview this voice: ${t.message}")
                }
            } finally {
                if (isCurrent()) {
                    previewToken = null
                    previewJob = null
                    _uiState.update { if (it.previewingKey == key) it.copy(previewingKey = null) else it }
                }
            }
        }
    }

    fun stopPreview() {
        previewToken = null
        previewJob?.cancel()
        previewJob = null
        player.stop()
        _uiState.update { it.copy(previewingKey = null) }
    }

    override fun onCleared() {
        // No super.onCleared() call: ViewModel's is defined empty (lint
        // EmptySuperCall), and player.stop() is the only real cleanup.
        player.stop()
    }
}
