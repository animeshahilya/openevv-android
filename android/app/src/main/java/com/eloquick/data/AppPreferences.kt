package com.eloquick.data

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import com.eloquick.tts.DateOrder
import com.eloquick.tts.NumberReadingStyle
import com.eloquick.tts.PITCH_REAL_WORLD_RANGE
import com.eloquick.tts.PronunciationEntry
import com.eloquick.tts.PunctuationMode
import com.eloquick.tts.PunctuationPreset
import com.eloquick.tts.TextPipelineOptions
import com.eloquick.tts.RAW_TUNING_RANGE
import com.eloquick.tts.SPEED_REAL_WORLD_RANGE
import com.eloquick.tts.convertTuningRange
import com.eloquick.tts.BUNDLED_COMMUNITY_DICTIONARY_SHA
import com.eloquick.tts.DictionaryKind
import com.eloquick.tts.bundledEnglishCommunityDictionary
import com.eloquick.tts.decodePronunciationDictionary
import com.eloquick.tts.dictionaryFileFor
import com.eloquick.tts.encodePronunciationDictionary
import com.eloquick.tts.entriesForLanguage
import com.eloquick.tts.mergeDictionaries
import com.eloquick.tts.validLangIdOrFallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Everything that controls how the default voice speaks. A single global
 * configuration - this is a system TTS engine, not a per-voice-profile app -
 * applied whenever a synthesis request doesn't pin its own voice/params.
 *
 * -1 for any Int tuning param means "leave it at the preset's own value".
 */
data class TtsConfig(
    val langId: Int = 0x10000,
    val voicePreset: Int = 1,
    val headSize: Int = -1,
    // Speed, volume, and pitch baseline *do* have a real "voice default" (-1)
    // state, same as every other tuning param here - EloquenceNative.
    // nativeSynthesize's own doc comment is explicit that -1 means "leave it
    // at the preset's own value" for any of its Int voice params, not just
    // these four. An earlier version of this app hardcoded these three to a
    // flat 50 instead (reasoning: applyCallerRate/applyCallerPitch "need a
    // real number to scale from") - both of those already guard `value < 0`
    // and pass -1 through untouched, so that reasoning didn't actually hold,
    // and the flat 50 diverged from each voice preset's real factory default
    // (openevv's own lang/enus/enus.settings: Reed's is pitchBaseline=65,
    // volume=92, not 50/50) - a real, audible regression from how the
    // original engine sounds, caught by an on-device listen.
    val pitchBaseline: Int = -1,
    val pitchFluctuation: Int = -1,
    val roughness: Int = -1,
    val breathiness: Int = -1,
    val speed: Int = -1,
    val volume: Int = -1,
    val realWorldUnits: Boolean = false,
    val sampleRateHz: Int = 0,
    // Extra silence between sentences, in milliseconds. 0 = natural engine pacing only.
    val sentencePauseMs: Int = 0,
    // How much standalone punctuation the engine speaks (see
    // PunctuationMode) - QUIET by default: it corrects a documented
    // divergence (openevv#4, isolated marks read as words) back toward the
    // original engine's silence. Replaces the old boolean toggle; upgrades
    // map true->QUIET, false->VERBOSE.
    val punctuationMode: PunctuationMode = PunctuationMode.QUIET,
    // NVDA-IBMTTS-Driver's "Rate boost", generalized from a fixed 1.6x into
    // a user-adjustable multiplier on the actual speed value sent to the
    // engine, beyond what the slider shows - see applyRateBoost. 1x (off)
    // by default since it pushes speed outside the UI's normal range.
    val rateBoostMultiplier: Float = 1f,
    // The engine's own built-in abbreviation dictionary (e.g. "Dr." ->
    // "Doctor", a single eciSetParam call) - not the removed custom
    // pronunciation-dictionary feature. On by default: matches the engine's
    // own default (eciDictionary defaults to 0/on).
    val abbreviationExpansion: Boolean = true,
    // Speaks a description ("grinning face") in place of every recognised
    // emoji instead of letting it fall through to encodeForEngine's '?' -
    // see EmojiSpeech.kt. On by default: this app exists to be spoken
    // through by people who can't see the glyph in the first place.
    val describeEmoji: Boolean = true,
    // How a large number gets spoken (see NumberReadingStyle) - NATURAL by
    // default, the engine's own existing Western-scale reading; unchanged
    // for anyone who doesn't touch this setting.
    val numberReadingStyle: NumberReadingStyle = NumberReadingStyle.NATURAL,
    // How many digits Digit by digit mode reads as one chunk - "1234" at 1
    // is "one two three four" (this app's original, still the default), at
    // 2 is "twelve thirty-four". Only read when numberReadingStyle is
    // DIGIT_BY_DIGIT; harmless and unused otherwise, so it needs no reset
    // of its own when the style is switched away.
    val digitGroupSize: Int = 1,
    // Characters that are never stripped in QUIET/SENTENCES mode even
    // though they'd otherwise count as isolated punctuation - see
    // processPunctuation. Kept as the raw typed string rather than a Set:
    // simpler to persist and edit; [alwaysReadSymbolSet] below is the
    // parsed form, computed once per config instead of once per call site.
    // Empty by default (off).
    val alwaysReadSymbols: String = "",
    // Speaks a symbol's name (see SYMBOL_NAMES) instead of leaving the
    // bare character for the engine to pronounce, for every symbol that
    // ends up spoken at all. Off by default: unnamed behavior is exactly
    // what shipped before this setting existed.
    val readSymbolsByName: Boolean = false,
    // Whether the app honors a *caller's* requested speed/pitch
    // (SynthesisRequest.getSpeechRate()/getPitch() - e.g. TalkBack's own
    // speech-rate slider) or always forces this app's own Tuning-screen
    // value regardless - see applyCallerRate/applyCallerPitch. Off by
    // default: every other engine on the platform honors the caller, and
    // this one silently never read the request at all until this existed.
    val forceSpeed: Boolean = false,
    val forcePitch: Boolean = false,
    // Reads "10:30 AM" as "ten thirty AM" and "14/07/2024" as "fourteenth
    // July twenty twenty-four" instead of the engine's own digit-by-digit
    // handling of a bare HH:MM or DD/MM/YYYY pattern - see
    // NaturalTimeReading.kt/NaturalDateReading.kt. On by default (safe,
    // conservative pattern matching - see each file's own doc comment for
    // exactly what it declines to touch); a toggle exists because a reading
    // this specific is still worth being able to turn off.
    val naturalTimeReading: Boolean = true,
    val naturalDateReading: Boolean = true,
    // How to resolve a numeric date whose day/month can't be told apart by
    // magnitude alone (see NaturalDateReading.applyNaturalDateReading's own
    // doc comment) - AS_WRITTEN by default, matching this app's existing
    // conservative default of leaving that case untouched rather than
    // guessing. Meaningless when naturalDateReading is off, same as
    // digitGroupSize is meaningless outside Digit by digit - needs no reset
    // of its own when that toggle flips.
    val dateOrder: DateOrder = DateOrder.AS_WRITTEN,
    // User-editable find -> replacement overrides, applied before anything
    // else in the text pipeline - see PronunciationDictionary.kt. Empty by
    // default (off); never touches the engine's own dictionary API, which
    // was tried once and pulled for crashing (see
    // ABBREVIATION_EXPANSION_DESCRIPTION in UiStrings.kt).
    val pronunciationDictionary: List<PronunciationEntry> = emptyList(),
    // Same shape and same "off by default, user's own entries only" rule as
    // pronunciationDictionary above, but loaded into the engine's separate
    // eciAbbvDict volume instead of eciMainDict - see
    // EloquenceNative.nativeSynthesize's own doc comment on abbvDictPath for
    // why this earns a second volume/screen rather than living in the same
    // list: confirmed on-device that eciAbbvDict is consulted independently
    // (eci_dict_test.c, 2026-09-11 - a "kg" -> "kilogram" entry here changed
    // "5 kg" without touching a Pronunciation dictionary entry for a whole
    // word). Unlike eciRootDict (deliberately not exposed - see
    // apply_dictionary's own doc comment for why testing it did not hold up
    // the way its name suggests), this one does exactly what its name says.
    val abbreviationDictionary: List<PronunciationEntry> = emptyList(),
    // Set only by configFromBundle (see TtsConfigProvider/toBundle) - the
    // already-built .dic file path(s) for the two dictionaries above,
    // resolved by the provider's own process (which holds the live entries
    // and the shared cache dir) rather than shipping the encoded entries
    // themselves across a Binder transaction. Binder has a small (~1MB,
    // shared across concurrent transactions) buffer: a dictionary of a few
    // thousand entries fits; a much larger one (confirmed with a since-
    // reverted ~68,000-entry bundled dictionary; an imported file of similar
    // size hits the same limit) does not, and overflowing it doesn't throw
    // cleanly - it silently stalls the :tts process's next currentConfig()
    // call for over a second, then fails, which reads as the engine going
    // completely silent. Null for
    // every same-process caller (loadConfig/persist use the entries lists
    // directly); a real value here always wins over resolving from entries.
    val resolvedDictPath: String? = null,
    val resolvedAbbvDictPath: String? = null,
    // "Audio Optimizer" - treble/bass harmonic warmth plus a peak safety
    // limiter, applied to the engine's raw PCM output (see AudioOptimizer.kt).
    // Off by default: it changes the actual timbre of the voice, which isn't
    // something to switch on someone who already has a rate/voice they rely
    // on without asking first, unlike a text-reading behavior default.
    val audioOptimizerEnabled: Boolean = false,
    // Collapses a repeated letter run ("hmmmmm") down to one instance - see
    // eliminateRepeatingCharacters. Off by default, matching CodeFactory's
    // ETI-Eloquence TTS, whose own settings screen offers this same feature
    // as "Eliminate repeating characters".
    val eliminateRepeats: Boolean = false,
    // "Minimum characters to ignore" in ETI-Eloquence's own wording - how
    // many of the same letter in a row before this collapses them. Clamped
    // to at least 3 wherever it's read (see eliminateRepeatingCharacters);
    // stored as typed so a value from an older/different build still loads.
    val repeatThreshold: Int = 3,
) {
    /**
     * Parsed form of [alwaysReadSymbols], computed once per config instance
     * instead of once per call site - the service builds this Set on every
     * utterance and the settings screen on every recomposition, for a value
     * that only changes when the user edits the field. `by lazy` on a data
     * class is safe here: it isn't part of equals/hashCode/copy, so each
     * copy gets its own correct value.
     */
    val alwaysReadSymbolSet: Set<Char> by lazy { alwaysReadSymbols.toSet() }

    fun toBundle(dictCacheDir: java.io.File): Bundle = AppPreferences.configToBundle(this, dictCacheDir)

    companion object {
        fun fromBundle(bundle: Bundle): TtsConfig = AppPreferences.configFromBundle(bundle)
    }
}

/**
 * The single mapping from stored config to pipeline knobs - the service and
 * the voice preview both go through here, so there is exactly one place
 * where a config field meets a pipeline option instead of two positional
 * argument lists to keep in sync. Named arguments throughout: reordering
 * either side is a compile error, not a silent behavior change.
 */
fun TtsConfig.pipelineOptions(): TextPipelineOptions = TextPipelineOptions(
    mode = punctuationMode,
    describeEmoji = describeEmoji,
    numberReadingStyle = numberReadingStyle,
    alwaysReadSymbols = alwaysReadSymbolSet,
    readSymbolsByName = readSymbolsByName,
    naturalTimeReading = naturalTimeReading,
    naturalDateReading = naturalDateReading,
    dateOrder = dateOrder,
    digitGroupSize = digitGroupSize,
    eliminateRepeats = eliminateRepeats,
    repeatThreshold = repeatThreshold,
)

/**
 * SharedPreferences-backed store for [TtsConfig] plus the small bits of UI
 * state worth remembering (last-picked screen etc. are kept in-memory only).
 * Exposes [config] as a StateFlow, kept live in memory, for the UI to
 * collect and recompose from - every setter here updates it immediately and
 * persists in the same call.
 *
 * [EloquenceTtsService] runs in its own process (`android:process=":tts"`
 * in the manifest, isolating real synthesis from the visible UI's own
 * rendering - see that manifest entry's own comment for why), so it does
 * *not* read [config]: a separate process gets its own separate instance of
 * this class, with its own [_config] and its own [prefs] handle.
 *
 * [currentConfig] used to be described here as "a plain fresh disk read" on
 * the theory that re-calling `SharedPreferences.getInt()` every utterance
 * would pick up whatever the UI process last wrote. **That was wrong, and
 * was this app's real "changing a setting does nothing" bug**: a process's
 * `SharedPreferencesImpl` parses its backing XML file into an in-memory map
 * *once*, the first time that file is opened in that process, and every
 * later `getInt`/`getString`/etc. call is served from that same map -
 * Android never re-parses the file on its own, and *only* updates the map
 * when a write goes through that same instance's own `Editor`. The `:tts`
 * process's copy of that map is whatever was on disk when it first opened
 * the file (service startup, or first synthesis request) and would then
 * stay frozen for as long as that process stayed alive - which, for a bound
 * system-service process, the platform is free to do for a very long time.
 * Every setting change made in the UI (a slider drag, a preset tap) was
 * landing correctly on disk and in the UI's own [_config], and then simply
 * never being seen by the process that actually speaks.
 *
 * [currentConfig] now asks [TtsConfigProvider] - a `ContentProvider`
 * declared with no `android:process` override, so it always lives in the
 * *default* process alongside [_config] itself - for the live in-memory
 * config over a real cross-process Binder call, which cannot go stale the
 * way a cached `SharedPreferences` read can: [TtsConfigProvider] answers
 * from the same [_config] the UI reads and writes, every single call. Only
 * falls back to this process's own (possibly stale) [loadConfig] if that
 * call fails outright (provider process not yet up, IPC error) - never
 * silently returning old settings in the common case, and never worse than
 * today's behavior in the rare one.
 */
class AppPreferences(context: Context) {
    private val appContext = context.applicationContext
    // Device-protected storage, not credential-protected: the TTS service
    // (and this provider-serving process) must speak *before first unlock*
    // - TalkBack at the PIN screen - when credential-encrypted storage is
    // still locked and a plain getSharedPreferences read would come back
    // empty (or worse). The DE store works identically once unlocked, so
    // this is the one and only settings file in both modes - no branching
    // per lock state, no second source of truth. Existing installs migrate
    // once, post-unlock (see ensureMigrated); the old CE file is left in
    // place, so downgrading to a build from before this still finds it.
    private val prefs = appContext.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .also { ensureMigrated(appContext, it) }
        .also { ensureCommunityDictionaryMerged(it) }

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<TtsConfig> = _config.asStateFlow()

    /** The live config, however this process needs to reach it - see this
     * class's own doc comment for why a same-process [config] read and a
     * cross-process [loadConfig] disk fallback both fall short of what
     * [EloquenceTtsService] actually needs. */
    fun currentConfig(): TtsConfig {
        refreshAfterMigration()
        try {
            val bundle = appContext.contentResolver.call(
                "content://${appContext.packageName}.ttsconfig".toUri(),
                TtsConfigProvider.METHOD_GET_CONFIG,
                null,
                null,
            )
            if (bundle != null) return configFromBundle(bundle)
        } catch (e: Exception) {
            // Provider's own process not up yet, a transient IPC error, or
            // (in the same-process/UI case) no real bug at all - either way,
            // stale-but-available beats a synthesis request failing outright.
            Log.w(TAG, "Cross-process config read failed, falling back to local disk", e)
        }
        return loadConfig()
    }

    /**
     * One-time copy of the pre-Direct-Boot credential-protected settings
     * file into the device-protected store. Runs only while unlocked (the
     * legacy file is unreadable before that) and only until
     * [KEY_DIRECT_BOOT_MIGRATED] is set - and deliberately NOT set while
     * still locked, so a process born before first unlock retries on its
     * next call instead of concluding there was nothing to migrate. Called
     * from init, [currentConfig], and [update] (idempotent single
     * `contains` check once migrated) because the default process can be
     * born pre-unlock to serve [TtsConfigProvider] and live on past unlock
     * with a migration that hasn't run yet. Copy, never move: a downgrade
     * still finds the old file intact. Returns true when a migration
     * actually completed, so the caller can reload in-memory state (see
     * [refreshAfterMigration]).
     */
    private fun ensureMigrated(appContext: Context, deviceProtected: android.content.SharedPreferences): Boolean {
        if (deviceProtected.contains(KEY_DIRECT_BOOT_MIGRATED)) {
            // One-time cleanup for installs that already migrated to Direct Boot before these keys were retired
            if (deviceProtected.contains(KEY_GENDER) || deviceProtected.contains(KEY_SSML_INPUT) ||
                deviceProtected.contains(KEY_UPSAMPLE_METHOD) || deviceProtected.contains(KEY_HETERONYM_FILTER) ||
                deviceProtected.contains(KEY_STRIP_ISOLATED_PUNCTUATION) || deviceProtected.contains(KEY_RATE_BOOST)
            ) {
                deviceProtected.edit {
                    remove(KEY_GENDER)
                    remove(KEY_SSML_INPUT)
                    remove(KEY_UPSAMPLE_METHOD)
                    remove(KEY_HETERONYM_FILTER)
                    remove(KEY_STRIP_ISOLATED_PUNCTUATION)
                    remove(KEY_RATE_BOOST)
                }
            }
            return false
        }
        if (appContext.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked != true) return false
        // Returns whether KEY_DIRECT_BOOT_MIGRATED was actually persisted, not just attempted - an
        // exception partway through (corrupt legacy file, storage pressure) must not report success:
        // the old unconditional `return true` here made every subsequent currentConfig()/update()
        // call force a full config reload via refreshAfterMigration() forever, since the flag was
        // never set and this function kept getting re-entered while still failing each time.
        return runCatching {
            val legacy = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all
            if (legacy.isNotEmpty()) {
                deviceProtected.edit {
                    for ((key, value) in legacy) {
                        when (value) {
                            is String -> putString(key, value)
                            is Int -> putInt(key, value)
                            is Long -> putLong(key, value)
                            is Float -> putFloat(key, value)
                            is Boolean -> putBoolean(key, value)
                            is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(key, value as Set<String>)
                        }
                    }
                }
            }
            deviceProtected.edit {
                remove(KEY_GENDER)
                remove(KEY_SSML_INPUT)
                remove(KEY_UPSAMPLE_METHOD)
                remove(KEY_HETERONYM_FILTER)
                remove(KEY_STRIP_ISOLATED_PUNCTUATION)
                remove(KEY_RATE_BOOST)
                putBoolean(KEY_DIRECT_BOOT_MIGRATED, true)
            }
            true
        }.onFailure { Log.w(TAG, "Direct Boot settings migration failed; using device-protected defaults", it) }
            .getOrDefault(false)
    }

    /**
     * One-time fold-in of the bundled English community dictionary (see
     * [bundledEnglishCommunityDictionary]) into whatever pronunciation
     * dictionary is already on disk, so every install has it without a
     * manual import step. Safe in a way the earlier "Word Wisdom" auto-fold
     * this app shipped and removed twice wasn't: [decodePronunciationDictionary]
     * defaults an acronym-shaped find (WITH, AKA, LOL, ...) to case
     * sensitive, so this can no longer rewrite the ordinary lowercase word
     * the way that removed feature did. Never touches an entry a person
     * already typed themselves - [mergeDictionaries] only adds a community
     * entry whose find isn't already present, case-insensitively - and runs
     * exactly once per install ([KEY_COMMUNITY_DICTIONARY_MERGED]), so an
     * entry someone later deletes stays deleted instead of reappearing on
     * the next launch. Needs no unlock, unlike [ensureMigrated]'s legacy CE
     * copy: the bundled dictionary is a classpath resource inside the APK,
     * and [deviceProtected] itself is already readable and writable before
     * first unlock.
     */
    private fun ensureCommunityDictionaryMerged(deviceProtected: android.content.SharedPreferences) {
        if (deviceProtected.contains(KEY_COMMUNITY_DICTIONARY_MERGED)) return
        runCatching {
            val bundled = bundledEnglishCommunityDictionary()
            deviceProtected.edit {
                if (bundled != null) {
                    val existing = decodePronunciationDictionary(
                        deviceProtected.getString(KEY_PRONUNCIATION_DICTIONARY, "") ?: "",
                    )
                    val merged = mergeDictionaries(existing, bundled).entries
                    putString(KEY_PRONUNCIATION_DICTIONARY, encodePronunciationDictionary(merged))
                    putString(KEY_COMMUNITY_DICTIONARY_SHA, BUNDLED_COMMUNITY_DICTIONARY_SHA)
                }
                putBoolean(KEY_COMMUNITY_DICTIONARY_MERGED, true)
            }
        }.onFailure { Log.w(TAG, "Community dictionary auto-merge failed", it) }
    }

    /** The upstream SHA this app believes is already folded into the
     * Pronunciation dictionary - what a manual "check for updates" compares
     * against (see [checkCommunityDictionaryUpdate]). Falls back to the
     * bundled snapshot's own SHA for an install that hasn't run the
     * one-time merge yet (or ran it before this tracking existed). */
    fun communityDictionaryKnownSha(): String =
        prefs.getString(KEY_COMMUNITY_DICTIONARY_SHA, null) ?: BUNDLED_COMMUNITY_DICTIONARY_SHA

    /** Records that [sha] is now folded into the Pronunciation dictionary -
     * called once a manual dictionary-update check has been downloaded and
     * merged, so the next check reports up to date instead of offering the
     * same update again. */
    fun recordCommunityDictionaryMerge(sha: String) {
        prefs.edit { putString(KEY_COMMUNITY_DICTIONARY_SHA, sha) }
    }

    private fun loadConfig(): TtsConfig = TtsConfig(
        // Clamped on load (NVDA-driver issue #147's lesson): a langId
        // persisted by a newer/different build - or plain corruption - must
        // degrade to a nearby voice, never to a load failure. Device locale
        // is available here (pure JVM API, no Activity needed).
        langId = validLangIdOrFallback(
            prefs.getInt(KEY_LANG_ID, 0x10000),
            java.util.Locale.getDefault().language,
            java.util.Locale.getDefault().country,
        ),
        voicePreset = prefs.getInt(KEY_VOICE_PRESET, 1),
        headSize = prefs.getInt(KEY_HEAD_SIZE, -1),
        pitchBaseline = prefs.getInt(KEY_PITCH_BASELINE, -1),
        pitchFluctuation = prefs.getInt(KEY_PITCH_FLUCTUATION, -1),
        roughness = prefs.getInt(KEY_ROUGHNESS, -1),
        breathiness = prefs.getInt(KEY_BREATHINESS, -1),
        speed = prefs.getInt(KEY_SPEED, -1),
        volume = prefs.getInt(KEY_VOLUME, -1),
        realWorldUnits = prefs.getBoolean(KEY_REAL_WORLD_UNITS, false),
        sampleRateHz = prefs.getInt(KEY_SAMPLE_RATE, 0),
        sentencePauseMs = prefs.getInt(KEY_SENTENCE_PAUSE_MS, 0),
        punctuationMode = loadPunctuationMode(prefs),
        rateBoostMultiplier = loadRateBoostMultiplier(prefs),
        abbreviationExpansion = prefs.getBoolean(KEY_ABBREVIATION_EXPANSION, true),
        describeEmoji = prefs.getBoolean(KEY_DESCRIBE_EMOJI, true),
        numberReadingStyle = loadNumberReadingStyle(prefs),
        digitGroupSize = prefs.getInt(KEY_DIGIT_GROUP_SIZE, 1).coerceIn(1, 5),
        alwaysReadSymbols = prefs.getString(KEY_ALWAYS_READ_SYMBOLS, "") ?: "",
        readSymbolsByName = prefs.getBoolean(KEY_READ_SYMBOLS_BY_NAME, false),
        forceSpeed = prefs.getBoolean(KEY_FORCE_SPEED, false),
        forcePitch = prefs.getBoolean(KEY_FORCE_PITCH, false),
        naturalTimeReading = prefs.getBoolean(KEY_NATURAL_TIME_READING, true),
        naturalDateReading = prefs.getBoolean(KEY_NATURAL_DATE_READING, true),
        dateOrder = loadDateOrder(prefs),
        pronunciationDictionary = decodePronunciationDictionary(prefs.getString(KEY_PRONUNCIATION_DICTIONARY, "") ?: ""),
        abbreviationDictionary = decodePronunciationDictionary(prefs.getString(KEY_ABBREVIATION_DICTIONARY, "") ?: ""),
        audioOptimizerEnabled = prefs.getBoolean(KEY_AUDIO_OPTIMIZER_ENABLED, false),
        eliminateRepeats = prefs.getBoolean(KEY_ELIMINATE_REPEATS, false),
        repeatThreshold = prefs.getInt(KEY_REPEAT_THRESHOLD, 3).coerceAtLeast(3),
    )

    private fun update(block: (TtsConfig) -> TtsConfig) {
        refreshAfterMigration()
        val next = block(_config.value)
        _config.value = next
        persist(next)
    }

    /**
     * What [TtsConfigProvider] serves: the in-memory config, but reloaded
     * from disk first if a pending Direct Boot migration just completed -
     * otherwise a default process born before first unlock would keep
     * serving the defaults it loaded while locked forever after.
     */
    fun liveConfig(): TtsConfig {
        refreshAfterMigration()
        return _config.value
    }

    /** Runs a pending CE->DE migration, if any, and reloads [_config] when
     * one actually completed so this process never writes stale defaults
     * over freshly-migrated values (see [ensureMigrated]). */
    private fun refreshAfterMigration() {
        if (ensureMigrated(appContext, prefs)) _config.value = loadConfig()
    }

    private fun persist(c: TtsConfig) {
        prefs.edit {
            putInt(KEY_LANG_ID, c.langId)
            putInt(KEY_VOICE_PRESET, c.voicePreset)
            putInt(KEY_HEAD_SIZE, c.headSize)
            putInt(KEY_PITCH_BASELINE, c.pitchBaseline)
            putInt(KEY_PITCH_FLUCTUATION, c.pitchFluctuation)
            putInt(KEY_ROUGHNESS, c.roughness)
            putInt(KEY_BREATHINESS, c.breathiness)
            putInt(KEY_SPEED, c.speed)
            putInt(KEY_VOLUME, c.volume)
            putBoolean(KEY_REAL_WORLD_UNITS, c.realWorldUnits)
            putInt(KEY_SAMPLE_RATE, c.sampleRateHz)
            putInt(KEY_SENTENCE_PAUSE_MS, c.sentencePauseMs)
            putString(KEY_PUNCTUATION_MODE, c.punctuationMode.name)
            putFloat(KEY_RATE_BOOST_MULTIPLIER, c.rateBoostMultiplier)
            putBoolean(KEY_ABBREVIATION_EXPANSION, c.abbreviationExpansion)
            putBoolean(KEY_DESCRIBE_EMOJI, c.describeEmoji)
            putString(KEY_NUMBER_READING_STYLE, c.numberReadingStyle.name)
            putInt(KEY_DIGIT_GROUP_SIZE, c.digitGroupSize)
            putString(KEY_ALWAYS_READ_SYMBOLS, c.alwaysReadSymbols)
            putBoolean(KEY_READ_SYMBOLS_BY_NAME, c.readSymbolsByName)
            putBoolean(KEY_FORCE_SPEED, c.forceSpeed)
            putBoolean(KEY_FORCE_PITCH, c.forcePitch)
            putBoolean(KEY_NATURAL_TIME_READING, c.naturalTimeReading)
            putBoolean(KEY_NATURAL_DATE_READING, c.naturalDateReading)
            putString(KEY_DATE_ORDER, c.dateOrder.name)
            putString(KEY_PRONUNCIATION_DICTIONARY, encodePronunciationDictionary(c.pronunciationDictionary))
            putString(KEY_ABBREVIATION_DICTIONARY, encodePronunciationDictionary(c.abbreviationDictionary))
            putBoolean(KEY_AUDIO_OPTIMIZER_ENABLED, c.audioOptimizerEnabled)
            putBoolean(KEY_ELIMINATE_REPEATS, c.eliminateRepeats)
            putInt(KEY_REPEAT_THRESHOLD, c.repeatThreshold)
        }
    }

    // The framework's own documented way for a TTS engine to tell every
    // listening client (TalkBack included) "re-check what I offer" -
    // without it, a client that already bound to this engine and cached
    // onGetDefaultVoiceNameFor's old answer at connect time keeps using
    // that stale default until it happens to restart, which read on-device
    // as "I have to restart my screen reader for a new voice to stick".
    // Only fired here, not from every tuning setter: rate/pitch/volume/etc.
    // don't change which voice is the default, only how it sounds, and
    // onSynthesizeText already reads those fresh on every single utterance
    // regardless (see EloquenceTtsService's own cfg = currentConfig() line)
    // - there's nothing there for a client to have cached in the first
    // place.
    private fun notifyVoiceChanged() {
        appContext.sendBroadcast(android.content.Intent(TextToSpeech.Engine.ACTION_TTS_DATA_INSTALLED))
    }

    fun setVoice(langId: Int, preset: Int) = update { it.copy(langId = langId, voicePreset = preset) }.also { notifyVoiceChanged() }
    fun setHeadSize(v: Int) = update { it.copy(headSize = v) }
    fun setPitchBaseline(v: Int) = update { it.copy(pitchBaseline = v) }
    fun setPitchFluctuation(v: Int) = update { it.copy(pitchFluctuation = v) }
    fun setRoughness(v: Int) = update { it.copy(roughness = v) }
    fun setBreathiness(v: Int) = update { it.copy(breathiness = v) }
    fun setSpeed(v: Int) = update { it.copy(speed = v) }
    fun setVolume(v: Int) = update { it.copy(volume = v) }

    /** Flips the real-world-units display and carries the *same perceived*
     * speed/pitch across it - speed and pitch baseline are stored in
     * whichever scale is currently active (see [TtsConfig.speed]'s own
     * comment), so without this a value like a raw 50 would suddenly be
     * sent to the engine as 50 WPM the moment this flips on, a jarring drop
     * to near the slowest setting rather than the same speed relabeled. A
     * still-unset -1 ("voice default") is left alone either way - it has no
     * unit-dependent meaning to convert, same as headSize never needing this
     * treatment at all. */
    fun setRealWorldUnits(v: Boolean) = update { cfg ->
        if (v == cfg.realWorldUnits) return@update cfg
        val (fromSpeed, toSpeed) = if (v) RAW_TUNING_RANGE to SPEED_REAL_WORLD_RANGE else SPEED_REAL_WORLD_RANGE to RAW_TUNING_RANGE
        val (fromPitch, toPitch) = if (v) RAW_TUNING_RANGE to PITCH_REAL_WORLD_RANGE else PITCH_REAL_WORLD_RANGE to RAW_TUNING_RANGE
        cfg.copy(
            realWorldUnits = v,
            speed = if (cfg.speed < 0) cfg.speed else convertTuningRange(cfg.speed, fromSpeed, toSpeed),
            pitchBaseline = if (cfg.pitchBaseline < 0) cfg.pitchBaseline else convertTuningRange(cfg.pitchBaseline, fromPitch, toPitch),
        )
    }
    fun setSampleRateHz(v: Int) = update { it.copy(sampleRateHz = v) }
    fun setSentencePauseMs(v: Int) = update { it.copy(sentencePauseMs = v) }
    fun setPunctuationMode(v: PunctuationMode) = update { it.copy(punctuationMode = v) }
    fun setRateBoostMultiplier(v: Float) = update { it.copy(rateBoostMultiplier = v) }
    fun setAbbreviationExpansion(v: Boolean) = update { it.copy(abbreviationExpansion = v) }
    fun setDescribeEmoji(v: Boolean) = update { it.copy(describeEmoji = v) }
    fun setNumberReadingStyle(v: NumberReadingStyle) = update { it.copy(numberReadingStyle = v) }
    fun setDigitGroupSize(v: Int) = update { it.copy(digitGroupSize = v.coerceIn(1, 5)) }
    fun setAlwaysReadSymbols(v: String) = update { it.copy(alwaysReadSymbols = v) }
    fun setReadSymbolsByName(v: Boolean) = update { it.copy(readSymbolsByName = v) }
    fun setForceSpeed(v: Boolean) = update { it.copy(forceSpeed = v) }
    fun setForcePitch(v: Boolean) = update { it.copy(forcePitch = v) }
    fun setNaturalTimeReading(v: Boolean) = update { it.copy(naturalTimeReading = v) }
    fun setNaturalDateReading(v: Boolean) = update { it.copy(naturalDateReading = v) }
    fun setDateOrder(v: DateOrder) = update { it.copy(dateOrder = v) }
    fun setPronunciationDictionary(entries: List<PronunciationEntry>) = update { it.copy(pronunciationDictionary = entries) }
    fun setAbbreviationDictionary(entries: List<PronunciationEntry>) = update { it.copy(abbreviationDictionary = entries) }
    fun setAudioOptimizerEnabled(v: Boolean) = update { it.copy(audioOptimizerEnabled = v) }
    fun setEliminateRepeats(v: Boolean) = update { it.copy(eliminateRepeats = v) }
    fun setRepeatThreshold(v: Int) = update { it.copy(repeatThreshold = v.coerceAtLeast(3)) }

    /** Applies a [PunctuationPreset] as one atomic update - see its own doc
     * comment. A plain three-field `.copy()` rather than three separate
     * setter calls, so a collector of [config] never observes an
     * in-between state that matches neither the old settings nor the new
     * preset. */
    fun setPunctuationPreset(preset: PunctuationPreset) = update {
        it.copy(
            punctuationMode = preset.mode,
            alwaysReadSymbols = preset.alwaysReadSymbols.joinToString(""),
            readSymbolsByName = preset.readSymbolsByName,
        )
    }

    companion object {
        private const val TAG = "AppPreferences"
        private const val PREFS_NAME = "eloquence_revived_prefs"
        // Set in the device-protected store once the legacy CE file has
        // been copied over (see ensureMigrated) - never set while locked.
        private const val KEY_DIRECT_BOOT_MIGRATED = "direct_boot_migrated"
        // Set once the bundled English community dictionary has been folded
        // into the Pronunciation dictionary (see ensureCommunityDictionaryMerged).
        private const val KEY_COMMUNITY_DICTIONARY_MERGED = "community_dictionary_merged"
        // The upstream SHA already folded in - see communityDictionaryKnownSha/recordCommunityDictionaryMerge.
        private const val KEY_COMMUNITY_DICTIONARY_SHA = "community_dictionary_sha"

        /** [TtsConfig] -> [Bundle] for [TtsConfigProvider]'s `call()` reply -
         * reuses this file's own persistence key constants rather than a
         * second parallel naming scheme, since the two serialize the exact
         * same field list for the exact same reason (handing this process's
         * live config to a reader that isn't this process). Not every stored
         * key needs a place here: [alwaysReadSymbolSet] is derived, and
         * [langId] is already validated by the time it's in a [TtsConfig]
         * (validated again on the receiving side's own [loadConfig]
         * fallback path, so no bypass either way).
         *
         * The two dictionaries are the one field pair handled specially:
         * [dictionaryFileFor] resolves them to file paths here, using
         * [dictCacheDir] (the same physical device-protected cache directory
         * every process shares), and only those short paths cross the
         * Binder transaction - see [TtsConfig.resolvedDictPath]'s own doc
         * comment for why shipping the encoded entries themselves doesn't
         * hold up past a few thousand of them. */
        internal fun configToBundle(c: TtsConfig, dictCacheDir: java.io.File): Bundle = Bundle().apply {
            putInt(KEY_LANG_ID, c.langId)
            putInt(KEY_VOICE_PRESET, c.voicePreset)
            putInt(KEY_HEAD_SIZE, c.headSize)
            putInt(KEY_PITCH_BASELINE, c.pitchBaseline)
            putInt(KEY_PITCH_FLUCTUATION, c.pitchFluctuation)
            putInt(KEY_ROUGHNESS, c.roughness)
            putInt(KEY_BREATHINESS, c.breathiness)
            putInt(KEY_SPEED, c.speed)
            putInt(KEY_VOLUME, c.volume)
            putBoolean(KEY_REAL_WORLD_UNITS, c.realWorldUnits)
            putInt(KEY_SAMPLE_RATE, c.sampleRateHz)
            putInt(KEY_SENTENCE_PAUSE_MS, c.sentencePauseMs)
            putString(KEY_PUNCTUATION_MODE, c.punctuationMode.name)
            putFloat(KEY_RATE_BOOST_MULTIPLIER, c.rateBoostMultiplier)
            putBoolean(KEY_ABBREVIATION_EXPANSION, c.abbreviationExpansion)
            putBoolean(KEY_DESCRIBE_EMOJI, c.describeEmoji)
            putString(KEY_NUMBER_READING_STYLE, c.numberReadingStyle.name)
            putInt(KEY_DIGIT_GROUP_SIZE, c.digitGroupSize)
            putString(KEY_ALWAYS_READ_SYMBOLS, c.alwaysReadSymbols)
            putBoolean(KEY_READ_SYMBOLS_BY_NAME, c.readSymbolsByName)
            putBoolean(KEY_FORCE_SPEED, c.forceSpeed)
            putBoolean(KEY_FORCE_PITCH, c.forcePitch)
            putBoolean(KEY_NATURAL_TIME_READING, c.naturalTimeReading)
            putBoolean(KEY_NATURAL_DATE_READING, c.naturalDateReading)
            putString(KEY_DATE_ORDER, c.dateOrder.name)
            dictionaryFileFor(entriesForLanguage(c.pronunciationDictionary, c.langId), dictCacheDir)
                ?.let { putString(KEY_RESOLVED_DICT_PATH, it) }
            dictionaryFileFor(c.abbreviationDictionary, dictCacheDir, DictionaryKind.ABBREVIATION)
                ?.let { putString(KEY_RESOLVED_ABBV_DICT_PATH, it) }
            putBoolean(KEY_AUDIO_OPTIMIZER_ENABLED, c.audioOptimizerEnabled)
            putBoolean(KEY_ELIMINATE_REPEATS, c.eliminateRepeats)
            putInt(KEY_REPEAT_THRESHOLD, c.repeatThreshold)
        }

        /** The other direction of [configToBundle] - same validation as
         * [loadConfig] for anything that needs it (langId), same defaults
         * for a key an older provider process's [configToBundle] didn't
         * send yet (a mid-update app process pair, briefly - Bundle.getX's
         * own default parameter covers it exactly like SharedPreferences'
         * getX already does elsewhere in this file). */
        internal fun configFromBundle(b: Bundle): TtsConfig = TtsConfig(
            langId = validLangIdOrFallback(
                b.getInt(KEY_LANG_ID, 0x10000),
                java.util.Locale.getDefault().language,
                java.util.Locale.getDefault().country,
            ),
            voicePreset = b.getInt(KEY_VOICE_PRESET, 1),
            headSize = b.getInt(KEY_HEAD_SIZE, -1),
            pitchBaseline = b.getInt(KEY_PITCH_BASELINE, -1),
            pitchFluctuation = b.getInt(KEY_PITCH_FLUCTUATION, -1),
            roughness = b.getInt(KEY_ROUGHNESS, -1),
            breathiness = b.getInt(KEY_BREATHINESS, -1),
            speed = b.getInt(KEY_SPEED, -1),
            volume = b.getInt(KEY_VOLUME, -1),
            realWorldUnits = b.getBoolean(KEY_REAL_WORLD_UNITS, false),
            sampleRateHz = b.getInt(KEY_SAMPLE_RATE, 0),
            sentencePauseMs = b.getInt(KEY_SENTENCE_PAUSE_MS, 0),
            punctuationMode = b.getString(KEY_PUNCTUATION_MODE)
                ?.let { name -> runCatching { PunctuationMode.valueOf(name) }.getOrNull() }
                ?: PunctuationMode.QUIET,
            rateBoostMultiplier = b.getFloat(KEY_RATE_BOOST_MULTIPLIER, 1f),
            abbreviationExpansion = b.getBoolean(KEY_ABBREVIATION_EXPANSION, true),
            describeEmoji = b.getBoolean(KEY_DESCRIBE_EMOJI, true),
            numberReadingStyle = b.getString(KEY_NUMBER_READING_STYLE)
                ?.let { name -> runCatching { NumberReadingStyle.valueOf(name) }.getOrNull() }
                ?: NumberReadingStyle.NATURAL,
            digitGroupSize = b.getInt(KEY_DIGIT_GROUP_SIZE, 1).coerceIn(1, 5),
            // Bundle.getString returns String? even with a non-null default -
            // a null value stored by another process would NPE without the
            // elvis (loadConfig above already guards the same way).
            alwaysReadSymbols = b.getString(KEY_ALWAYS_READ_SYMBOLS, "") ?: "",
            readSymbolsByName = b.getBoolean(KEY_READ_SYMBOLS_BY_NAME, false),
            forceSpeed = b.getBoolean(KEY_FORCE_SPEED, false),
            forcePitch = b.getBoolean(KEY_FORCE_PITCH, false),
            naturalTimeReading = b.getBoolean(KEY_NATURAL_TIME_READING, true),
            naturalDateReading = b.getBoolean(KEY_NATURAL_DATE_READING, true),
            dateOrder = b.getString(KEY_DATE_ORDER)
                ?.let { name -> runCatching { DateOrder.valueOf(name) }.getOrNull() }
                ?: DateOrder.AS_WRITTEN,
            // Entries lists stay empty from a Bundle - see resolvedDictPath's own doc comment.
            // A same-process caller that needs the real lists uses loadConfig()/liveConfig()
            // directly instead, never this path.
            resolvedDictPath = b.getString(KEY_RESOLVED_DICT_PATH),
            resolvedAbbvDictPath = b.getString(KEY_RESOLVED_ABBV_DICT_PATH),
            audioOptimizerEnabled = b.getBoolean(KEY_AUDIO_OPTIMIZER_ENABLED, false),
            eliminateRepeats = b.getBoolean(KEY_ELIMINATE_REPEATS, false),
            repeatThreshold = b.getInt(KEY_REPEAT_THRESHOLD, 3).coerceAtLeast(3),
        )
        private const val KEY_LANG_ID = "lang_id"
        private const val KEY_VOICE_PRESET = "voice_preset"
        // Retired: Gender was removed entirely - classic Eloquence never
        // exposed it as a setting of its own (see the Tuning-screen-order
        // README section); only kept here so persist() can clean it off
        // disk once.
        private const val KEY_GENDER = "gender"
        private const val KEY_HEAD_SIZE = "head_size"
        private const val KEY_PITCH_BASELINE = "pitch_baseline"
        private const val KEY_PITCH_FLUCTUATION = "pitch_fluctuation"
        private const val KEY_ROUGHNESS = "roughness"
        private const val KEY_BREATHINESS = "breathiness"
        private const val KEY_SPEED = "speed"
        private const val KEY_VOLUME = "volume"
        private const val KEY_REAL_WORLD_UNITS = "real_world_units"
        private const val KEY_SAMPLE_RATE = "sample_rate_hz"
        private const val KEY_SENTENCE_PAUSE_MS = "sentence_pause_ms"
        private const val KEY_HETERONYM_FILTER = "heteronym_filter"
        // Retired settings: SSML input and upsample method. Read nowhere any
        // more; only kept here so persist() can clean them off disk once.
        private const val KEY_SSML_INPUT = "ssml_input"
        private const val KEY_UPSAMPLE_METHOD = "upsample_method"
        private const val KEY_PUNCTUATION_MODE = "punctuation_mode"
        // Retired toggle form of the above: true meant QUIET, false meant
        // VERBOSE. Read once on upgrade, then dropped.
        private const val KEY_STRIP_ISOLATED_PUNCTUATION = "strip_isolated_punctuation"

        private fun loadPunctuationMode(
            prefs: android.content.SharedPreferences,
        ): PunctuationMode {
            prefs.getString(KEY_PUNCTUATION_MODE, null)?.let { name ->
                runCatching { PunctuationMode.valueOf(name) }.getOrNull()?.let { return it }
            }
            return if (prefs.getBoolean(KEY_STRIP_ISOLATED_PUNCTUATION, true)) {
                PunctuationMode.QUIET
            } else {
                PunctuationMode.VERBOSE
            }
        }
        // Retired boolean form of rateBoostMultiplier: true meant a fixed
        // 1.6x. Read once on upgrade, then dropped (see persist()).
        private const val KEY_RATE_BOOST = "rate_boost"
        private const val KEY_RATE_BOOST_MULTIPLIER = "rate_boost_multiplier"
        private const val KEY_ABBREVIATION_EXPANSION = "abbreviation_expansion"
        private const val KEY_DESCRIBE_EMOJI = "describe_emoji"
        private const val KEY_NUMBER_READING_STYLE = "number_reading_style"
        private const val KEY_DIGIT_GROUP_SIZE = "digit_group_size"
        private const val KEY_ALWAYS_READ_SYMBOLS = "always_read_symbols"
        private const val KEY_READ_SYMBOLS_BY_NAME = "read_symbols_by_name"
        private const val KEY_FORCE_SPEED = "force_speed"
        private const val KEY_FORCE_PITCH = "force_pitch"
        private const val KEY_NATURAL_TIME_READING = "natural_time_reading"
        private const val KEY_NATURAL_DATE_READING = "natural_date_reading"
        private const val KEY_DATE_ORDER = "date_order"
        private const val KEY_PRONUNCIATION_DICTIONARY = "pronunciation_dictionary"
        private const val KEY_ABBREVIATION_DICTIONARY = "abbreviation_dictionary"
        // Bundle-only (see configToBundle/configFromBundle) - never persisted to SharedPreferences.
        private const val KEY_RESOLVED_DICT_PATH = "resolved_dict_path"
        private const val KEY_RESOLVED_ABBV_DICT_PATH = "resolved_abbv_dict_path"
        private const val KEY_AUDIO_OPTIMIZER_ENABLED = "audio_optimizer_enabled"
        private const val KEY_ELIMINATE_REPEATS = "eliminate_repeats"
        private const val KEY_REPEAT_THRESHOLD = "repeat_threshold"

        private fun loadRateBoostMultiplier(prefs: android.content.SharedPreferences): Float {
            if (prefs.contains(KEY_RATE_BOOST_MULTIPLIER)) {
                return prefs.getFloat(KEY_RATE_BOOST_MULTIPLIER, 1f)
            }
            return if (prefs.getBoolean(KEY_RATE_BOOST, false)) 1.6f else 1f
        }

        private fun loadNumberReadingStyle(prefs: android.content.SharedPreferences): NumberReadingStyle {
            val name = prefs.getString(KEY_NUMBER_READING_STYLE, null) ?: return NumberReadingStyle.NATURAL
            return runCatching { NumberReadingStyle.valueOf(name) }.getOrDefault(NumberReadingStyle.NATURAL)
        }

        private fun loadDateOrder(prefs: android.content.SharedPreferences): DateOrder {
            val name = prefs.getString(KEY_DATE_ORDER, null) ?: return DateOrder.AS_WRITTEN
            return runCatching { DateOrder.valueOf(name) }.getOrDefault(DateOrder.AS_WRITTEN)
        }
    }
}
