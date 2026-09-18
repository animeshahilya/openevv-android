package com.eloquick.ui.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.eloquick.data.TtsConfig
import com.eloquick.tts.DateOrder
import com.eloquick.tts.NumberReadingStyle
import com.eloquick.tts.PunctuationMode
import com.eloquick.tts.PunctuationPreset
import com.eloquick.tts.punctuationPresetFor
import com.eloquick.ui.ABBREVIATION_EXPANSION_DESCRIPTION
import com.eloquick.ui.ALWAYS_READ_SYMBOLS_DESCRIPTION
import com.eloquick.ui.AUDIO_OPTIMIZER_DESCRIPTION
import com.eloquick.ui.AUDIO_QUALITY_DESCRIPTION
import com.eloquick.ui.AUDIO_QUALITY_LEVELS
import com.eloquick.ui.ChoiceButtonRow
import com.eloquick.ui.DATE_ORDER_DESCRIPTION
import com.eloquick.ui.DESCRIBE_EMOJI_DESCRIPTION
import com.eloquick.ui.DIGIT_GROUP_SIZE_DESCRIPTION
import com.eloquick.ui.DIGIT_GROUP_SIZE_LABELS
import com.eloquick.ui.DropdownPickerButton
import com.eloquick.ui.ELIMINATE_REPEATS_DESCRIPTION
import com.eloquick.ui.LabeledSlider
import com.eloquick.ui.NATURAL_DATE_READING_DESCRIPTION
import com.eloquick.ui.NATURAL_TIME_READING_DESCRIPTION
import com.eloquick.ui.NUMBER_READING_STYLE_DESCRIPTION
import com.eloquick.ui.PUNCTUATION_MODE_DESCRIPTION
import com.eloquick.ui.READ_SYMBOLS_BY_NAME_DESCRIPTION
import com.eloquick.ui.REPEAT_THRESHOLD_DESCRIPTION
import com.eloquick.ui.SectionDescription
import com.eloquick.ui.SectionRowHeading
import com.eloquick.ui.audioQualityLabelFor
import kotlin.math.roundToInt

/**
 * Everything about how punctuation and symbols get spoken, in one place:
 * a one-tap None/Some/Most/All preset, verbosity (how much standalone
 * punctuation is read at all), an allowlist of symbols that stay spoken
 * regardless, and whether a spoken symbol is announced by name.
 */
@Composable
fun PunctuationAndSymbolsContent(
    config: TtsConfig,
    onPunctuationModeChange: (PunctuationMode) -> Unit,
    onPunctuationPresetChange: (PunctuationPreset) -> Unit,
    onAlwaysReadSymbolsChange: (String) -> Unit,
    onReadSymbolsByNameChange: (Boolean) -> Unit,
) {
    var symbolsText by rememberSaveable(config.alwaysReadSymbols) { mutableStateOf(config.alwaysReadSymbols) }
    val punctuationLabel = config.punctuationMode.label
    val activePreset = punctuationPresetFor(config.punctuationMode, config.alwaysReadSymbolSet, config.readSymbolsByName)

    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        Text(
            "How much standalone punctuation gets spoken, and how it sounds.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        ChoiceButtonRow(
            options = PunctuationPreset.entries.map { it to it.label },
            selected = activePreset,
            onSelect = onPunctuationPresetChange,
        )

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        SectionRowHeading("Verbosity: $punctuationLabel")
        SectionDescription(PUNCTUATION_MODE_DESCRIPTION)
        DropdownPickerButton(
            title = "Verbosity",
            label = punctuationLabel,
            selected = config.punctuationMode,
            options = PunctuationMode.entries.map { it to it.label },
            onSelect = onPunctuationModeChange,
        )

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        SectionRowHeading("Always read these symbols")
        SectionDescription(ALWAYS_READ_SYMBOLS_DESCRIPTION)
        OutlinedTextField(
            value = symbolsText,
            onValueChange = { symbolsText = it; onAlwaysReadSymbolsChange(it) },
            singleLine = true,
            label = { Text("Symbols, e.g. #&") },
            trailingIcon = if (symbolsText.isNotEmpty()) {
                {
                    IconButton(
                        onClick = {
                            symbolsText = ""
                            onAlwaysReadSymbolsChange("")
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "Clear always read symbols"
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = null,
                        )
                    }
                }
            } else null,
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        SectionRowHeading(
            "Read symbols by name",
            trailing = {
                Switch(
                    checked = config.readSymbolsByName,
                    onCheckedChange = onReadSymbolsByNameChange,
                    modifier = Modifier.semantics { contentDescription = "Read symbols by name" },
                )
            },
        )
        SectionDescription(READ_SYMBOLS_BY_NAME_DESCRIPTION)
    }
}

@Composable
fun NumbersDatesTimesContent(
    config: TtsConfig,
    onNumberReadingStyleChange: (NumberReadingStyle) -> Unit,
    onDigitGroupSizeChange: (Int) -> Unit,
    onNaturalTimeReadingChange: (Boolean) -> Unit,
    onNaturalDateReadingChange: (Boolean) -> Unit,
    onDateOrderChange: (DateOrder) -> Unit,
) {
    val numberStyleLabel = config.numberReadingStyle.label

    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        SectionRowHeading("Number reading style: $numberStyleLabel")
        SectionDescription(NUMBER_READING_STYLE_DESCRIPTION)
        DropdownPickerButton(
            title = "Number reading style",
            label = numberStyleLabel,
            selected = config.numberReadingStyle,
            options = NumberReadingStyle.entries.map { it to it.label },
            onSelect = onNumberReadingStyleChange,
        )

        if (config.numberReadingStyle == NumberReadingStyle.DIGIT_BY_DIGIT) {
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text(
                "Digits at a time: ${config.digitGroupSize}",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { hideFromAccessibility() },
            )
            SectionDescription(DIGIT_GROUP_SIZE_DESCRIPTION)
            ChoiceButtonRow(
                options = DIGIT_GROUP_SIZE_LABELS,
                selected = config.digitGroupSize,
                onSelect = onDigitGroupSizeChange,
                groupLabel = "Digits at a time",
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        SectionRowHeading(
            "Natural time reading",
            trailing = {
                Switch(
                    checked = config.naturalTimeReading,
                    onCheckedChange = onNaturalTimeReadingChange,
                    modifier = Modifier.semantics { contentDescription = "Natural time reading" },
                )
            },
        )
        SectionDescription(NATURAL_TIME_READING_DESCRIPTION)

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        SectionRowHeading(
            "Natural date reading",
            trailing = {
                Switch(
                    checked = config.naturalDateReading,
                    onCheckedChange = onNaturalDateReadingChange,
                    modifier = Modifier.semantics { contentDescription = "Natural date reading" },
                )
            },
        )
        SectionDescription(NATURAL_DATE_READING_DESCRIPTION)

        if (config.naturalDateReading) {
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            val dateOrderLabel = config.dateOrder.label
            SectionRowHeading("Ambiguous dates: $dateOrderLabel")
            SectionDescription(DATE_ORDER_DESCRIPTION)
            DropdownPickerButton(
                title = "Ambiguous dates",
                label = dateOrderLabel,
                selected = config.dateOrder,
                options = DateOrder.entries.map { it to it.label },
                onSelect = onDateOrderChange,
            )
        }
    }
}

@Composable
fun AbbreviationsEmojiContent(
    config: TtsConfig,
    onAbbreviationExpansionChange: (Boolean) -> Unit,
    onDescribeEmojiChange: (Boolean) -> Unit,
) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        SectionRowHeading(
            "Abbreviation expansion",
            trailing = {
                Switch(
                    checked = config.abbreviationExpansion,
                    onCheckedChange = onAbbreviationExpansionChange,
                    modifier = Modifier.semantics { contentDescription = "Abbreviation expansion" },
                )
            },
        )
        SectionDescription(ABBREVIATION_EXPANSION_DESCRIPTION)

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        SectionRowHeading(
            "Describe emoji",
            trailing = {
                Switch(
                    checked = config.describeEmoji,
                    onCheckedChange = onDescribeEmojiChange,
                    modifier = Modifier.semantics { contentDescription = "Describe emoji" },
                )
            },
        )
        SectionDescription(DESCRIBE_EMOJI_DESCRIPTION)
    }
}

/**
 * Matches CodeFactory's ETI-Eloquence TTS's own "Eliminate repeating
 * characters" / "Minimum characters to ignore" pair.
 */
@Composable
fun RepeatedLettersContent(
    config: TtsConfig,
    onEliminateRepeatsChange: (Boolean) -> Unit,
    onRepeatThresholdChange: (Int) -> Unit,
) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        SectionRowHeading(
            "Eliminate repeating letters",
            trailing = {
                Switch(
                    checked = config.eliminateRepeats,
                    onCheckedChange = onEliminateRepeatsChange,
                    modifier = Modifier.semantics { contentDescription = "Eliminate repeating letters" },
                )
            },
        )
        SectionDescription(ELIMINATE_REPEATS_DESCRIPTION)

        if (config.eliminateRepeats) {
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text(
                "Minimum repeats: ${config.repeatThreshold}",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { hideFromAccessibility() },
            )
            SectionDescription(REPEAT_THRESHOLD_DESCRIPTION)
            ChoiceButtonRow(
                options = (3..6).map { it to "$it" },
                selected = config.repeatThreshold,
                onSelect = onRepeatThresholdChange,
                groupLabel = "Minimum repeats",
            )
        }
    }
}

/**
 * Audio quality, sentence pause, and Audio Optimizer settings.
 */
@Composable
fun AudioContent(
    config: TtsConfig,
    onSentencePauseMsChange: (Int) -> Unit,
    onSampleRateChange: (Int) -> Unit,
    onAudioOptimizerChange: (Boolean) -> Unit,
) {
    val pauseValueText = if (config.sentencePauseMs <= 0) "Natural pacing (no extra pause)" else "${config.sentencePauseMs} ms"
    val audioQualityLabel = audioQualityLabelFor(config.sampleRateHz)

    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        SectionDescription("Extra silence inserted between sentences, on top of the engine's own pacing.")
        LabeledSlider(
            label = "Pause between sentences",
            valueText = pauseValueText,
            value = config.sentencePauseMs.coerceIn(0, 2000).toFloat(),
            onValueChange = { onSentencePauseMsChange(it.roundToInt()) },
            valueRange = 0f..2000f,
        )

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        SectionRowHeading("Audio quality: $audioQualityLabel")
        SectionDescription(AUDIO_QUALITY_DESCRIPTION)
        DropdownPickerButton(
            title = "Audio quality",
            label = audioQualityLabel,
            selected = config.sampleRateHz,
            options = AUDIO_QUALITY_LEVELS,
            onSelect = onSampleRateChange,
        )

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        SectionRowHeading(
            "Audio Optimizer",
            trailing = {
                Switch(
                    checked = config.audioOptimizerEnabled,
                    onCheckedChange = onAudioOptimizerChange,
                    modifier = Modifier.semantics { contentDescription = "Audio Optimizer" },
                )
            },
        )
        SectionDescription(AUDIO_OPTIMIZER_DESCRIPTION)
    }
}
