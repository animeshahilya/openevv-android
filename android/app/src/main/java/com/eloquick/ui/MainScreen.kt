package com.eloquick.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eloquick.data.TtsConfig
import com.eloquick.tts.DateOrder
import com.eloquick.tts.NumberReadingStyle
import com.eloquick.tts.PunctuationMode
import com.eloquick.tts.PunctuationPreset
import com.eloquick.tts.languageInfo
import com.eloquick.tts.presetCategory
import com.eloquick.tts.presetName
import com.eloquick.tts.punctuationPresetFor
import com.eloquick.tts.voiceKey
import com.eloquick.ui.sections.AbbreviationsEmojiContent
import com.eloquick.ui.sections.AudioContent
import com.eloquick.ui.sections.LanguageContent
import com.eloquick.ui.sections.NumbersDatesTimesContent
import com.eloquick.ui.sections.OwnTextContent
import com.eloquick.ui.sections.PunctuationAndSymbolsContent
import com.eloquick.ui.sections.RatePitchContent
import com.eloquick.ui.sections.RepeatedLettersContent
import com.eloquick.ui.sections.VoiceCharacterContent
import com.eloquick.ui.sections.VoiceContent

/**
 * The entire app in one screen, no tabs. Each settings group used to be an [ElevatedCard]-wrapped
 * [ExpandableSection] that expanded in place - accurate, but visually heavy (every group, expanded
 * or not, drew a full rounded card) and slow to scan (an already-open section pushed everything
 * below it down the screen). Restructured 2026-09-10 into a flat native-Settings-style list: one
 * compact row per group (title + a live "what's set" summary, same summaries as before), no card
 * chrome, thin dividers between rows - and tapping a row opens that group full-screen rather than
 * expanding it in place, the same plain-state "push a screen" navigation [AboutScreen] and
 * [PronunciationDictionaryScreen] already used from this screen's two link rows, now shared by
 * every settings group too. Every group's actual controls ([LanguageContent], [VoiceContent],
 * [RatePitchContent], etc.) are untouched by this - same TalkBack semantics, same logic, just
 * hosted by [SectionDetailScreen] instead of an inline [ExpandableSection] body. Reset/Preview
 * stay outside any group - an immediate action, not a value to check later. (The Heteronym filter
 * that used to sit here the same way is gone entirely as of the same day - it fixed ~17 English
 * heteronyms with no perceptible effect in practice.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    uiState: UiState,
    systemEngineLabel: String?,
    onOpenSystemTtsSettings: () -> Unit,
    onPreviewVoice: (langId: Int, preset: Int) -> Unit,
    customPreviewText: String,
    onCustomPreviewTextChange: (String) -> Unit,
    onPreviewCustomText: () -> Unit,
    onSelectVoice: (langId: Int, preset: Int) -> Unit,
    onHeadSizeChange: (Int) -> Unit,
    onPitchBaselineChange: (Int) -> Unit,
    onPitchFluctuationChange: (Int) -> Unit,
    onRoughnessChange: (Int) -> Unit,
    onBreathinessChange: (Int) -> Unit,
    onSpeedChange: (Int) -> Unit,
    onVolumeChange: (Int) -> Unit,
    onRealWorldUnitsChange: (Boolean) -> Unit,
    onRateBoostMultiplierChange: (Float) -> Unit,
    onResetTuning: () -> Unit,
    onPreviewTuning: () -> Unit,
    onStopPreview: () -> Unit,
    onSampleRateChange: (Int) -> Unit,
    onSentencePauseMsChange: (Int) -> Unit,
    onPunctuationModeChange: (PunctuationMode) -> Unit,
    onPunctuationPresetChange: (PunctuationPreset) -> Unit,
    onAbbreviationExpansionChange: (Boolean) -> Unit,
    onDescribeEmojiChange: (Boolean) -> Unit,
    onNumberReadingStyleChange: (NumberReadingStyle) -> Unit,
    onDigitGroupSizeChange: (Int) -> Unit,
    onAlwaysReadSymbolsChange: (String) -> Unit,
    onReadSymbolsByNameChange: (Boolean) -> Unit,
    onNaturalTimeReadingChange: (Boolean) -> Unit,
    onNaturalDateReadingChange: (Boolean) -> Unit,
    onDateOrderChange: (DateOrder) -> Unit,
    onAudioOptimizerChange: (Boolean) -> Unit,
    onEliminateRepeatsChange: (Boolean) -> Unit,
    onRepeatThresholdChange: (Int) -> Unit,
    onAboutClick: () -> Unit,
    onPronunciationDictionaryClick: () -> Unit,
    onAbbreviationDictionaryClick: () -> Unit,
    onTroubleshootClick: () -> Unit,
    // Which row (a section key, or "dictionary"/"troubleshoot"/"about") a screen reader user was
    // just on when they opened whatever they're now returning from - set right before leaving that
    // row (either into this screen's own AnimatedContent detail view or into one of MainActivity's
    // sibling screens, which fully removes MainScreen from composition and back) and consumed once
    // that row is on screen again, moving accessibility focus back to it. Without this, TalkBack's
    // focus silently fell off the whole screen on return - a real reported gap, not cosmetic: the
    // flat list (and every full-screen destination it opens) is rebuilt fresh each time, so nothing
    // any framework does automatically remembers which row a listener was just on.
    returnFocusKey: String? = null,
    onReturnFocusKeyChange: (String?) -> Unit = {},
) {
    val config = uiState.config
    val lang = languageInfo(config.langId)
    val voiceLabel = "${lang?.displayName ?: "Voice"} - ${presetName(config.voicePreset)}"
    val isPreviewingTuning = uiState.previewingKey == voiceKey(config.langId, config.voicePreset)

    // Which settings group, if any, is currently pushed full-screen - null means "showing the
    // flat list". Saveable so a rotation reopens the same group instead of dropping back to the
    // list underneath it.
    var openSection by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = openSection != null) { openSection = null }
    // One accidental tap used to wipe every tuned slider with no way back -
    // for someone who spent twenty minutes finding their rate, that is data
    // loss. The dialog states exactly what Reset covers (voice tuning only:
    // language, reading rules, and dictionary are untouched) so the choice
    // is informed, not just confirmed.
    var confirmReset by rememberSaveable { mutableStateOf(false) }

    // Every discrete choice on this screen applies the instant it's tapped
    // (there is no Save button anywhere) and then gets out of the way: the
    // section closes and the voice speaks the sample with the new settings,
    // so what's heard is proof of what just changed instead of a setting
    // that silently takes effect somewhere. Sliders and text fields are the
    // deliberate exception - a drag in progress or a half-typed word is not
    // a finished decision, and evicting the user mid-typing would be the
    // opposite of polished.
    fun chooseAndConfirm(choose: () -> Unit) {
        choose()
        openSection = null
        onPreviewTuning()
    }

    val languageSummary = lang?.let { if (it.experimental) "${it.displayName} (experimental)" else it.displayName } ?: "Unknown"
    val voiceSummary = "${presetName(config.voicePreset)}, ${presetCategory(config.voicePreset)}"

    // Every other section's summary tells a TalkBack user what's actually set without
    // expanding - this one used to be the exception, checking only realWorldUnits and
    // rateBoostMultiplier while staying silent about Rate, Pitch, and Inflection themselves.
    // That meant a customized Rate could sit behind a summary that still read "Voice default".
    val ratePitchSummary = buildList {
        if (config.realWorldUnits) add("real-world units")
        if (config.speed >= 0) add("Rate ${config.speed}${if (config.realWorldUnits) " wpm" else ""}")
        if (config.pitchBaseline >= 0) add("Pitch ${config.pitchBaseline}${if (config.realWorldUnits) " Hz" else ""}")
        if (config.pitchFluctuation >= 0) add("Inflection ${config.pitchFluctuation}")
        if (config.rateBoostMultiplier > 1f) add(String.format(java.util.Locale.US, "%.1fx boost", config.rateBoostMultiplier))
    }.let { if (it.isEmpty()) "Voice default" else it.joinToString(", ") }

    // Same fix as ratePitchSummary above: "Customized" told a listener something had changed
    // but not what, forcing an expand just to find out - the one piece of information a
    // collapsed summary exists to avoid needing.
    val voiceCharacterSummary = buildList {
        if (config.volume >= 0) add("Volume ${config.volume}")
        if (config.headSize >= 0) add("Head size ${config.headSize}")
        if (config.roughness >= 0) add("Roughness ${config.roughness}")
        if (config.breathiness >= 0) add("Breathiness ${config.breathiness}")
    }.let { if (it.isEmpty()) "Voice default" else it.joinToString(", ") }

    val punctuationLabel = config.punctuationMode.label
    // A matched preset's own definition already accounts for alwaysReadSymbols/readSymbolsByName
    // (see punctuationPresetFor), so its label alone is a complete summary. A hand-tuned
    // combination that matches no preset used to fall back to just the verbosity label,
    // silently dropping whether extra symbols were pinned to always-read or spoken by name -
    // the same "summary hides a real setting" gap fixed above for Rate & Pitch and Voice
    // character.
    val activePunctuationPreset = punctuationPresetFor(config.punctuationMode, config.alwaysReadSymbolSet, config.readSymbolsByName)
    val punctuationSummary = if (activePunctuationPreset != null) {
        activePunctuationPreset.label
    } else {
        buildList {
            add(punctuationLabel)
            if (config.alwaysReadSymbolSet.isNotEmpty()) add("always read ${config.alwaysReadSymbols}")
            if (config.readSymbolsByName) add("by name")
        }.joinToString(", ")
    }

    val numberStyleLabel = config.numberReadingStyle.label
    val numbersSummary = buildList {
        add(numberStyleLabel)
        if (config.numberReadingStyle == NumberReadingStyle.DIGIT_BY_DIGIT) add("${config.digitGroupSize} at a time")
        if (config.naturalTimeReading) add("natural time")
        if (config.naturalDateReading) {
            add(if (config.dateOrder == DateOrder.AS_WRITTEN) "natural date" else "natural date, ${config.dateOrder.label}")
        }
    }.joinToString(", ")

    val abbreviationsEmojiSummary = buildList {
        if (config.abbreviationExpansion) add("Abbreviations")
        if (config.describeEmoji) add("Emoji described")
    }.let { if (it.isEmpty()) "Off" else it.joinToString(", ") }

    val repeatsSummary = if (config.eliminateRepeats) "${config.repeatThreshold}+ in a row" else "Off"

    val audioQualityLabel = audioQualityLabelFor(config.sampleRateHz)
    val audioSummary = buildList {
        add(audioQualityLabel)
        if (config.sentencePauseMs > 0) add("${config.sentencePauseMs}ms pause")
        if (config.audioOptimizerEnabled) add("Audio Optimizer")
    }.joinToString(", ")

    // One descriptor per settings group: title + live summary (same summary text as before) +
    // the group's own already-existing content composable, untouched. Doubles as the row list
    // (flat mode) and the lookup for whichever one is currently pushed full-screen.
    // Keyed on customPreviewText/isPreviewingTuning too, not just config: the "own_text" entry
    // below closes over both, and remember only recreates its lambdas - with a fresh capture of
    // whatever these currently are - when one of its keys actually changes. Missing them meant
    // every keystroke in "Try your own text" landed in a closure holding whatever the text was the
    // last time *config* changed, so onTextChange's own update got silently overwritten back to
    // that stale value on the very next recomposition - nothing ever visibly typed. Real bug, not
    // just a stale-read risk: found via on-device testing when typing into that field produced no
    // visible characters despite the field being correctly focused (IME connection confirmed via
    // `adb shell dumpsys input_method`).
    val sections = remember(config, customPreviewText, isPreviewingTuning) {
        listOf(
            SectionDef("language", "Language", languageSummary) {
                LanguageContent(
                    currentLangId = config.langId,
                    // Identity choice: apply, close, and let the new
                    // language introduce itself - the explicit ids (not
                    // config's copy) are what the preview speaks.
                    onSelectLanguage = { newLangId ->
                        onSelectVoice(newLangId, config.voicePreset)
                        openSection = null
                        onPreviewVoice(newLangId, config.voicePreset)
                    },
                )
            },
            SectionDef("voice", "Voice", voiceSummary) {
                VoiceContent(
                    currentLangId = config.langId,
                    currentPreset = config.voicePreset,
                    onSelectVoice = { langId, preset ->
                        onSelectVoice(langId, preset)
                        openSection = null
                        onPreviewVoice(langId, preset)
                    },
                )
            },
            // Evaluating a voice on one fixed sentence only goes so far -
            // names, numbers, and your own language's quirks are what
            // actually matter. Same pipeline and tuning as the Preview
            // button (and the same Stop behavior, keyed off the same
            // previewingKey), just with your words instead of the sample.
            SectionDef("own_text", "Try your own text", "Hear anything in this voice") {
                OwnTextContent(
                    text = customPreviewText,
                    onTextChange = onCustomPreviewTextChange,
                    isPreviewing = isPreviewingTuning,
                    onSpeak = onPreviewCustomText,
                    onStop = onStopPreview,
                )
            },
            // Rate, rate boost, pitch, inflection - in that order, and under those exact names.
            // Not an arbitrary choice: it's the order and the wording NVDA's own Eloquence driver
            // has shown for over a decade (SynthDriver.RateSetting/PitchSetting/InflectionSetting,
            // "rateBoost" - see davidacm/NVDA-IBMTTS-Driver's ibmeci.py), so anyone arriving here
            // with that muscle memory finds the same controls under the same names.
            SectionDef("rate_pitch", "Rate & Pitch", ratePitchSummary) {
                RatePitchContent(
                    config = config,
                    onRealWorldUnitsChange = { chooseAndConfirm { onRealWorldUnitsChange(it) } },
                    onSpeedChange = onSpeedChange,
                    onRateBoostMultiplierChange = onRateBoostMultiplierChange,
                    onPitchBaselineChange = onPitchBaselineChange,
                    onPitchFluctuationChange = onPitchFluctuationChange,
                )
            },
            SectionDef("voice_character", "Voice character", voiceCharacterSummary) {
                VoiceCharacterContent(
                    config = config,
                    onVolumeChange = onVolumeChange,
                    onHeadSizeChange = onHeadSizeChange,
                    onRoughnessChange = onRoughnessChange,
                    onBreathinessChange = onBreathinessChange,
                )
            },
            SectionDef("punctuation", "Punctuation & symbols", punctuationSummary) {
                PunctuationAndSymbolsContent(
                    config = config,
                    onPunctuationModeChange = { chooseAndConfirm { onPunctuationModeChange(it) } },
                    onPunctuationPresetChange = { chooseAndConfirm { onPunctuationPresetChange(it) } },
                    onAlwaysReadSymbolsChange = onAlwaysReadSymbolsChange,
                    onReadSymbolsByNameChange = { chooseAndConfirm { onReadSymbolsByNameChange(it) } },
                )
            },
            SectionDef("numbers", "Numbers, dates & times", numbersSummary) {
                NumbersDatesTimesContent(
                    config = config,
                    onNumberReadingStyleChange = { chooseAndConfirm { onNumberReadingStyleChange(it) } },
                    onDigitGroupSizeChange = { chooseAndConfirm { onDigitGroupSizeChange(it) } },
                    onNaturalTimeReadingChange = { chooseAndConfirm { onNaturalTimeReadingChange(it) } },
                    onNaturalDateReadingChange = { chooseAndConfirm { onNaturalDateReadingChange(it) } },
                    onDateOrderChange = { chooseAndConfirm { onDateOrderChange(it) } },
                )
            },
            SectionDef("abbreviations", "Abbreviations & emoji", abbreviationsEmojiSummary) {
                AbbreviationsEmojiContent(
                    config = config,
                    onAbbreviationExpansionChange = { chooseAndConfirm { onAbbreviationExpansionChange(it) } },
                    onDescribeEmojiChange = { chooseAndConfirm { onDescribeEmojiChange(it) } },
                )
            },
            SectionDef("repeats", "Repeated letters", repeatsSummary) {
                RepeatedLettersContent(
                    config = config,
                    onEliminateRepeatsChange = { chooseAndConfirm { onEliminateRepeatsChange(it) } },
                    onRepeatThresholdChange = { chooseAndConfirm { onRepeatThresholdChange(it) } },
                )
            },
            SectionDef("audio", "Audio", audioSummary) {
                AudioContent(
                    config = config,
                    onSentencePauseMsChange = onSentencePauseMsChange,
                    onSampleRateChange = { chooseAndConfirm { onSampleRateChange(it) } },
                    onAudioOptimizerChange = { chooseAndConfirm { onAudioOptimizerChange(it) } },
                )
            },
        )
    }

    // config.pronunciationDictionary never contains a community entry by the time it reaches
    // here - AppPreferences.currentConfig() strips any on load (see its own comment), and
    // nothing downstream of that ever adds one back - so a plain size is the whole count.
    val pronunciationDictionarySummary = config.pronunciationDictionary.size.let { count ->
        if (count == 0) "No custom pronunciations yet" else "$count custom pronunciation${if (count == 1) "" else "s"}"
    }
    val abbreviationDictionarySummary = config.abbreviationDictionary.size.let { count ->
        if (count == 0) "No custom abbreviations yet" else "$count custom abbreviation${if (count == 1) "" else "s"}"
    }

    // Keyed on the plain key string, not the SectionDef instance: `sections` above is rebuilt
    // (new SectionDef objects) on every config change, including one caused by dragging a slider
    // *inside* the open section itself - keying on the object would make AnimatedContent think a
    // brand new target arrived on every drag tick and replay the slide-in transition mid-drag.
    // The key string is stable for as long as the same section stays open, which is the only
    // thing that should ever trigger this transition.
    AnimatedContent(
        targetState = openSection,
        transitionSpec = {
            // The one thing left that didn't read as "native Android Settings" after the
            // 2026-09-10 restyle: an instant, un-animated swap between the flat list and a
            // pushed section. A real Settings app slides the new screen in from the right and
            // lets the old one drift left underneath it (and the reverse on the way back) -
            // same shape here, just without a Navigation library's own transition plumbing.
            if (targetState != null) {
                (slideInHorizontally(initialOffsetX = { it }) + fadeIn())
                    .togetherWith(slideOutHorizontally(targetOffsetX = { -it / 4 }) + fadeOut())
            } else {
                (slideInHorizontally(initialOffsetX = { -it / 4 }) + fadeIn())
                    .togetherWith(slideOutHorizontally(targetOffsetX = { it }) + fadeOut())
            }
        },
        label = "settings-section",
    ) { key ->
        val section = sections.firstOrNull { it.key == key }
        if (section != null) {
            // Shared DetailScaffold (see Components.kt), not a local copy
            // of the same app-bar scaffold About/Dictionary already use.
            DetailScaffold(
                modifier = modifier,
                title = section.title,
                onBack = { openSection = null },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState()),
                ) {
                    section.content()
                }
            }
        } else {
            Scaffold(
                modifier = modifier,
                topBar = {
                    CenterAlignedTopAppBar(
                        title = { Text("Eloquence Revived", modifier = Modifier.semantics { heading() }) },
                    )
                },
            ) { padding ->
                // No manual collectionInfo: this column is heterogeneous
                // (banner, header, preview row, the ten sections, dictionary,
                // about) and any hand-stated row count TalkBack is told here
                // is wrong by construction. The two homogeneous choice lists
                // (LanguageContent/VoiceContent) keep their own, correct one.
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
                ) {
                    // First item, before anything else: the number-one support
                    // question for any TTS engine app is "I changed settings and
                    // nothing sounds different", whose answer is almost always "the
                    // system is still using another engine". When that is the case,
                    // say so plainly with the actual engine name and a way out -
                    // hidden entirely when this app already is the system voice, so
                    // the steady state costs TalkBack users zero extra swipes.
                    if (systemEngineLabel != null) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Warning,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            "TalkBack is currently using $systemEngineLabel, so nothing tuned here will be heard yet.",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Button(onClick = onOpenSystemTtsSettings) {
                                            Text("Choose system voice")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Column(Modifier.padding(top = 12.dp, bottom = 12.dp)) {
                            // heading() - same reasoning as every other landmark on this
                            // screen (see SettingsRow's own comment): this is the
                            // first content after the app bar title, and TalkBack's
                            // "headings" navigation should be able to land here too,
                            // not skip straight to Language.
                            Text(
                                "Default voice: $voiceLabel",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { heading() },
                            )
                            Text(
                                "This is what TalkBack and every other app hears - change it below, live.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            PreviewButton(
                                isPreviewing = isPreviewingTuning,
                                idleLabel = "Preview",
                                idleDescription = "Preview this voice with current tuning",
                                stopDescription = "Stop preview",
                                onToggle = { if (isPreviewingTuning) onStopPreview() else onPreviewTuning() },
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(
                                onClick = { confirmReset = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics { contentDescription = "Reset tuning to voice defaults" },
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null)
                                Text("Reset", modifier = Modifier.padding(start = 8.dp).semantics { hideFromAccessibility() })
                            }
                        }
                    }

                    item { HorizontalDivider() }

                    itemsIndexed(sections, key = { _, section -> section.key }) { _, section ->
                        val focusRequester = rememberReturnFocusRequester(section.key, returnFocusKey, onReturnFocusKeyChange)
                        SettingsRow(
                            title = section.title,
                            summary = section.summary,
                            onClick = {
                                onReturnFocusKeyChange(section.key)
                                openSection = section.key
                            },
                            modifier = Modifier.focusRequester(focusRequester),
                        )
                        HorizontalDivider()
                    }

                    item {
                        val focusRequester = rememberReturnFocusRequester("dictionary", returnFocusKey, onReturnFocusKeyChange)
                        SettingsRow(
                            title = "Pronunciation dictionary",
                            summary = pronunciationDictionarySummary,
                            onClick = {
                                onReturnFocusKeyChange("dictionary")
                                onPronunciationDictionaryClick()
                            },
                            modifier = Modifier.focusRequester(focusRequester),
                        )
                    }

                    item { HorizontalDivider() }

                    item {
                        val focusRequester = rememberReturnFocusRequester("abbreviation_dictionary", returnFocusKey, onReturnFocusKeyChange)
                        SettingsRow(
                            title = "Abbreviation dictionary",
                            summary = abbreviationDictionarySummary,
                            onClick = {
                                onReturnFocusKeyChange("abbreviation_dictionary")
                                onAbbreviationDictionaryClick()
                            },
                            modifier = Modifier.focusRequester(focusRequester),
                        )
                    }

                    item { HorizontalDivider() }

                    item {
                        val focusRequester = rememberReturnFocusRequester("troubleshoot", returnFocusKey, onReturnFocusKeyChange)
                        SettingsRow(
                            title = "Troubleshoot",
                            summary = "Diagnostic logs and background reliability",
                            onClick = {
                                onReturnFocusKeyChange("troubleshoot")
                                onTroubleshootClick()
                            },
                            modifier = Modifier.focusRequester(focusRequester),
                        )
                    }

                    item { HorizontalDivider() }

                    item {
                        val focusRequester = rememberReturnFocusRequester("about", returnFocusKey, onReturnFocusKeyChange)
                        SettingsRow(
                            title = "About",
                            summary = "Credits and how this app is built",
                            onClick = {
                                onReturnFocusKeyChange("about")
                                onAboutClick()
                            },
                            modifier = Modifier.focusRequester(focusRequester),
                        )
                    }

                    item { Spacer(Modifier.padding(bottom = 24.dp)) }
                }
            }

            // See confirmReset's own comment: an explicit, scope-honest confirmation
            // between the tap and the wipe. AlertDialog is natively TalkBack-safe
            // (heading announced, focus contained on the two actions).
            if (confirmReset) {
                AlertDialog(
                    onDismissRequest = { confirmReset = false },
                    title = { Text("Reset tuning?") },
                    text = {
                        Text("Rate, pitch, volume, and voice character return to this voice's defaults. Language, reading rules, and your dictionary are untouched.")
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                confirmReset = false
                                onResetTuning()
                            },
                        ) { Text("Reset tuning") }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmReset = false }) { Text("Keep my settings") }
                    },
                )
            }
        }
    }
}

/** One settings group: a key (matched against [MainScreen]'s `openSection`), the title shown both
 * on its flat-list row and its detail screen's app bar, a live summary of what's currently set,
 * and the group's own existing content composable - unchanged by the 2026-09-10 restructuring,
 * just hosted differently. */
private class SectionDef(val key: String, val title: String, val summary: String, val content: @Composable () -> Unit)

/**
 * Restores TalkBack's accessibility focus to a flat-list row after returning from wherever tapping
 * it led - a settings group's own detail screen, or one of MainActivity's sibling full-screen
 * destinations (Pronunciation dictionary, Troubleshoot, About). All of those are "fully removed,
 * fully rebuilt" navigation (see [MainScreen]'s own [AnimatedContent] and MainActivity's sibling
 * `if`/`else` screens) - nothing about that shape leaves an Android accessibility focus behind to
 * come back to, so without this a screen reader's focus silently fell off the whole screen on
 * return, a real reported gap. [key] is this row's own stable identity (a section key, or
 * "dictionary"/"troubleshoot"/"about"); the caller records the same key into `returnFocusKey`
 * right when the row is tapped (before leaving), and whichever row matches it once the list is back
 * on screen claims focus and clears the key so this doesn't refire on every later recomposition.
 * `runCatching`: a `FocusRequester` not yet attached to a laid-out node throws rather than no-ops,
 * which a transient frame during the return animation can otherwise hit.
 *
 * Deliberately `LaunchedEffect(Unit)`, not keyed on `returnFocusKey` itself: the row that's tapped
 * records its own key into `returnFocusKey` *while still on screen* (so the value survives the
 * navigation away), and that same row's composable is still present at that moment - keying the
 * effect on `returnFocusKey` would fire it immediately on the tap that sets it, consuming (clearing)
 * the key before the screen it was meant for ever comes back. `Unit` instead fires this exactly
 * once, whenever this row's composable freshly enters composition - which is precisely "the list
 * just came back" for every case that matters here (returning from a sibling screen, or from this
 * screen's own detail view - both fully rebuild this row's composable, per this function's own doc
 * comment above), and does not refire from a state change on an already-composed, already-visible
 * row.
 */
@Composable
private fun rememberReturnFocusRequester(
    key: String,
    returnFocusKey: String?,
    onReturnFocusKeyChange: (String?) -> Unit,
): FocusRequester {
    val focusRequester = remember(key) { FocusRequester() }
    LaunchedEffect(Unit) {
        if (returnFocusKey == key) {
            runCatching { focusRequester.requestFocus() }
            onReturnFocusKeyChange(null)
        }
    }
    return focusRequester
}

/**
 * A flat, native-Settings-style row: title + a live summary of what's set, a trailing chevron,
 * tapping it opens [onClick] - a settings group's own full-screen ([SectionDetailScreen]) or a
 * link elsewhere (Pronunciation dictionary, About). Replaces both the old [ElevatedCard]-wrapped
 * [ExpandableSection] rows and the two separate near-duplicate link-card composables this screen
 * used to carry - see [MainScreen]'s own doc comment for why.
 *
 * Not marked `heading()` - a real Android Settings row isn't one either, only a genuine section
 * label is, and marking every row a heading meant TalkBack announced "...Heading" after every
 * single one of these (nine of them, back to back) - noise, not a landmark worth jumping to.
 * `Role.Button` is the correct, native role for a row that performs an action on tap; a manual
 * `contentDescription` still carries the live summary in one clean announcement (title's and
 * summary's own [Text]s are hidden from accessibility so neither is spoken a second time).
 */
@Composable
private fun SettingsRow(title: String, summary: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open", role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = "$title. $summary. Tap to change."
            }
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { hideFromAccessibility() },
            )
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { hideFromAccessibility() },
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
