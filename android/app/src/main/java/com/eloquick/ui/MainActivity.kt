package com.eloquick.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eloquick.tts.DictionaryKind
import com.eloquick.tts.languageInfo

/**
 * The whole app is this activity: it's a system TTS engine, so what Android
 * opens (via tts_engine.xml's settingsActivity) is a configuration surface,
 * not something people type into day to day. One screen, no tabs -
 * [MainScreen]'s own doc comment says why voice selection, tuning, and
 * reading behavior all fit as collapsible sections on it rather than
 * needing separate destinations.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate(): installSplashScreen() reads and clears
        // the activity's cold-start theme (Theme.App.Starting - see
        // themes.xml) itself, replacing it with postSplashScreenTheme's
        // value. Calling it after would be too late for that swap to take
        // effect for this launch.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: AppViewModel = viewModel()
            // Lifecycle-aware: stops collecting (and the recomposition work
            // that would follow) while the activity is stopped, instead of
            // updating a screen nobody can see.
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val systemEngineLabel by viewModel.systemEngineLabel.collectAsStateWithLifecycle()

            // The user may have just switched the system engine and come
            // back - re-read which engine is active on every resume, not
            // just at startup, so the "not your system voice" banner can
            // never go stale underneath them.
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> viewModel.refreshSystemEngineStatus()
                        Lifecycle.Event.ON_STOP -> viewModel.stopPreview()
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            EloquenceRevivedTheme {
                // About is a credits screen reached from a link on the main
                // screen, not a destination of its own - plain boolean state
                // instead of a real back stack, same as the rest of this
                // single-activity app's navigation.
                var showAbout by rememberSaveable { mutableStateOf(false) }
                BackHandler(enabled = showAbout) { showAbout = false }
                // Pronunciation dictionary is reached the same way About is -
                // same plain-boolean-state treatment, not a real back stack.
                var showDictionary by rememberSaveable { mutableStateOf(false) }
                BackHandler(enabled = showDictionary) { showDictionary = false }
                // Abbreviation dictionary is reached the same way - same
                // plain-boolean-state treatment, its own screen/file/volume.
                var showAbbreviationDictionary by rememberSaveable { mutableStateOf(false) }
                BackHandler(enabled = showAbbreviationDictionary) { showAbbreviationDictionary = false }
                // Troubleshoot is reached the same way - same plain-boolean-state treatment.
                var showTroubleshoot by rememberSaveable { mutableStateOf(false) }
                BackHandler(enabled = showTroubleshoot) { showTroubleshoot = false }

                // Which flat-list row a screen reader user tapped to get to whichever screen is
                // currently open - hoisted here, above all four of them, because returning from
                // About/Dictionary/Troubleshoot fully recomposes MainScreen from scratch (the same
                // `if`/`else` shape that already drops its scroll position on return), so the state
                // has to survive that swap rather than live inside MainScreen itself. See
                // [rememberReturnFocusRequester]'s own doc comment for the rest of this mechanism.
                var returnFocusKey by rememberSaveable { mutableStateOf<String?>(null) }

                // Preview playback is the only thing in this app that can
                // fail after the user's already acted - a missing native
                // library, a synth error. UiState.error carried that message
                // all the way to the UI layer with nowhere to actually show
                // it; one shared Snackbar host here covers the whole screen.
                val snackbarHostState = remember { SnackbarHostState() }
                // Keyed on errorSeq, not the message: UiState.error is a
                // plain String?, so consecutive identical errors (or one
                // arriving mid-display) would never re-fire the effect.
                LaunchedEffect(uiState.errorSeq) {
                    val message = uiState.error ?: return@LaunchedEffect
                    snackbarHostState.showSnackbar(message)
                    viewModel.dismissError()
                }

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                ) { padding ->
                    if (showAbout) {
                        AboutScreen(
                            modifier = Modifier.padding(padding),
                            onBack = { showAbout = false },
                        )
                    } else if (showDictionary || showAbbreviationDictionary) {
                        val kind = if (showDictionary) DictionaryKind.MAIN else DictionaryKind.ABBREVIATION
                        PronunciationDictionaryScreen(
                            modifier = Modifier.padding(padding),
                            kind = kind,
                            entries = if (kind == DictionaryKind.MAIN) uiState.config.pronunciationDictionary else uiState.config.abbreviationDictionary,
                            onEntriesChange = if (kind == DictionaryKind.MAIN) viewModel::setPronunciationDictionary else viewModel::setAbbreviationDictionary,
                            onSuggestPhoneticSpelling = viewModel::suggestPhoneticSpelling,
                            onCheckForCommunityDictionaryUpdate = viewModel::checkForCommunityDictionaryUpdate,
                            onApplyCommunityDictionaryUpdate = viewModel::applyCommunityDictionaryUpdate,
                            onBack = {
                                if (kind == DictionaryKind.MAIN) showDictionary = false else showAbbreviationDictionary = false
                            },
                        )
                    } else if (showTroubleshoot) {
                        TroubleshootScreen(
                            modifier = Modifier.padding(padding),
                            onBack = { showTroubleshoot = false },
                            onError = viewModel::showError,
                        )
                    } else {
                        MainScreen(
                            modifier = Modifier.padding(padding),
                            uiState = uiState,
                            systemEngineLabel = systemEngineLabel,
                            onOpenSystemTtsSettings = {
                                runCatching {
                                    startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                                }.onFailure {
                                    viewModel.showError("Couldn't open the system voice settings.")
                                }
                            },
                            onSelectVoice = viewModel::selectVoice,
                            onHeadSizeChange = viewModel::setHeadSize,
                            onPitchBaselineChange = viewModel::setPitchBaseline,
                            onPitchFluctuationChange = viewModel::setPitchFluctuation,
                            onRoughnessChange = viewModel::setRoughness,
                            onBreathinessChange = viewModel::setBreathiness,
                            onSpeedChange = viewModel::setSpeed,
                            onVolumeChange = viewModel::setVolume,
                            onRealWorldUnitsChange = viewModel::setRealWorldUnits,
                            onRateBoostMultiplierChange = viewModel::setRateBoostMultiplier,
                            onResetTuning = viewModel::resetTuning,
                            onPreviewTuning = {
                                val cfg = uiState.config
                                val bcp47 = languageInfo(cfg.langId)?.bcp47 ?: "en-US"
                                viewModel.previewVoice(cfg.langId, cfg.voicePreset, bcp47)
                            },
                            onPreviewVoice = { langId, preset ->
                                val bcp47 = languageInfo(langId)?.bcp47 ?: "en-US"
                                viewModel.previewVoice(langId, preset, bcp47)
                            },
                            onPreviewCustomText = {
                                val cfg = uiState.config
                                val bcp47 = languageInfo(cfg.langId)?.bcp47 ?: "en-US"
                                viewModel.previewVoice(cfg.langId, cfg.voicePreset, bcp47, uiState.customPreviewText)
                            },
                            customPreviewText = uiState.customPreviewText,
                            onCustomPreviewTextChange = viewModel::setCustomPreviewText,
                            onStopPreview = viewModel::stopPreview,
                            onSampleRateChange = viewModel::setSampleRateHz,
                            onSentencePauseMsChange = viewModel::setSentencePauseMs,
                            onPunctuationModeChange = viewModel::setPunctuationMode,
                            onPunctuationPresetChange = viewModel::setPunctuationPreset,
                            onAbbreviationExpansionChange = viewModel::setAbbreviationExpansion,
                            onDescribeEmojiChange = viewModel::setDescribeEmoji,
                            onNumberReadingStyleChange = viewModel::setNumberReadingStyle,
                            onDigitGroupSizeChange = viewModel::setDigitGroupSize,
                            onAlwaysReadSymbolsChange = viewModel::setAlwaysReadSymbols,
                            onReadSymbolsByNameChange = viewModel::setReadSymbolsByName,
                            onNaturalTimeReadingChange = viewModel::setNaturalTimeReading,
                            onNaturalDateReadingChange = viewModel::setNaturalDateReading,
                            onDateOrderChange = viewModel::setDateOrder,
                            onAudioOptimizerChange = viewModel::setAudioOptimizerEnabled,
                            onEliminateRepeatsChange = viewModel::setEliminateRepeats,
                            onRepeatThresholdChange = viewModel::setRepeatThreshold,
                            onAboutClick = { showAbout = true },
                            onPronunciationDictionaryClick = { showDictionary = true },
                            onAbbreviationDictionaryClick = { showAbbreviationDictionary = true },
                            onTroubleshootClick = { showTroubleshoot = true },
                            returnFocusKey = returnFocusKey,
                            onReturnFocusKeyChange = { returnFocusKey = it },
                        )
                    }
                }
            }
        }
    }
}
