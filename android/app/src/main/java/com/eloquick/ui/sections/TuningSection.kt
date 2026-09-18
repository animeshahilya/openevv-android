package com.eloquick.ui.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.eloquick.data.TtsConfig
import com.eloquick.tts.PITCH_REAL_WORLD_RANGE
import com.eloquick.tts.RAW_TUNING_RANGE
import com.eloquick.tts.SPEED_REAL_WORLD_RANGE
import com.eloquick.ui.BREATHINESS_DESCRIPTION
import com.eloquick.ui.HEAD_SIZE_DESCRIPTION
import com.eloquick.ui.LabeledSlider
import com.eloquick.ui.PITCH_BASELINE_DESCRIPTION
import com.eloquick.ui.PITCH_FLUCTUATION_DESCRIPTION
import com.eloquick.ui.ROUGHNESS_DESCRIPTION
import com.eloquick.ui.SPEED_DESCRIPTION
import com.eloquick.ui.SectionDescription
import com.eloquick.ui.SectionRowHeading
import com.eloquick.ui.VOLUME_DESCRIPTION
import kotlin.math.roundToInt

/**
 * A tuning slider that starts at the voice's own built-in default (raw value of -1) until moved -
 * see [TtsConfig.headSize]'s own comment. With [realWorldRange]/[unit] null it is a plain 0-100
 * slider (Volume, Head size, Roughness, Breathiness, Inflection); with them set it displays and
 * edits in real-world units instead (Rate in wpm, Pitch in Hz).
 */
@Composable
fun TuningSliderRow(
    label: String,
    description: String,
    value: Int,
    onChange: (Int) -> Unit,
    unit: String?,
    realWorldRange: ClosedFloatingPointRange<Float>?,
) {
    val range = realWorldRange ?: RAW_TUNING_RANGE
    val usesDefault = value < 0
    val current = (if (usesDefault) (range.start + range.endInclusive) / 2f else value.toFloat())
        .coerceIn(range.start, range.endInclusive)
    val valueText = if (usesDefault) {
        "Voice default"
    } else if (unit != null) {
        "${current.roundToInt()} $unit"
    } else {
        "${current.roundToInt()}"
    }
    SectionDescription(description)
    LabeledSlider(
        label = label,
        valueText = valueText,
        value = current,
        onValueChange = { onChange(it.roundToInt()) },
        valueRange = range,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
fun RatePitchContent(
    config: TtsConfig,
    onRealWorldUnitsChange: (Boolean) -> Unit,
    onSpeedChange: (Int) -> Unit,
    onRateBoostMultiplierChange: (Float) -> Unit,
    onPitchBaselineChange: (Int) -> Unit,
    onPitchFluctuationChange: (Int) -> Unit,
) {
    val rwu = config.realWorldUnits
    val rateBoostText = if (config.rateBoostMultiplier <= 1f) "Off" else String.format(java.util.Locale.US, "%.1fx", config.rateBoostMultiplier)

    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        SectionRowHeading(
            "Real-world units",
            trailing = {
                Switch(
                    checked = rwu,
                    onCheckedChange = onRealWorldUnitsChange,
                    modifier = Modifier.semantics { contentDescription = "Real-world units" },
                )
            },
        )
        SectionDescription(
            "Show and set pitch in Hertz and rate in words per minute instead of this app's own plain 0-100 scale. Switching this carries your current rate and pitch across with it, at the same relative point in the new range.",
        )

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        TuningSliderRow(
            label = "Rate",
            description = SPEED_DESCRIPTION,
            value = config.speed,
            onChange = onSpeedChange,
            unit = if (rwu) "wpm" else null,
            realWorldRange = if (rwu) SPEED_REAL_WORLD_RANGE else null,
        )

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        SectionDescription("Speeds Eloquence up beyond its normal range, up to 3x.")
        LabeledSlider(
            label = "Rate boost",
            valueText = rateBoostText,
            value = config.rateBoostMultiplier,
            onValueChange = onRateBoostMultiplierChange,
            valueRange = 1f..3f,
        )

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        TuningSliderRow(
            label = "Pitch",
            description = PITCH_BASELINE_DESCRIPTION,
            value = config.pitchBaseline,
            onChange = onPitchBaselineChange,
            unit = if (rwu) "Hz" else null,
            realWorldRange = if (rwu) PITCH_REAL_WORLD_RANGE else null,
        )

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        TuningSliderRow(
            label = "Inflection",
            description = PITCH_FLUCTUATION_DESCRIPTION,
            value = config.pitchFluctuation,
            onChange = onPitchFluctuationChange,
            unit = null,
            realWorldRange = null,
        )
    }
}

@Composable
fun VoiceCharacterContent(
    config: TtsConfig,
    onVolumeChange: (Int) -> Unit,
    onHeadSizeChange: (Int) -> Unit,
    onRoughnessChange: (Int) -> Unit,
    onBreathinessChange: (Int) -> Unit,
) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        TuningSliderRow(
            label = "Volume",
            description = VOLUME_DESCRIPTION,
            value = config.volume,
            onChange = onVolumeChange,
            unit = null,
            realWorldRange = null,
        )

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        TuningSliderRow(
            label = "Head size",
            description = HEAD_SIZE_DESCRIPTION,
            value = config.headSize,
            onChange = onHeadSizeChange,
            unit = null,
            realWorldRange = null,
        )

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        TuningSliderRow(
            label = "Roughness",
            description = ROUGHNESS_DESCRIPTION,
            value = config.roughness,
            onChange = onRoughnessChange,
            unit = null,
            realWorldRange = null,
        )

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        TuningSliderRow(
            label = "Breathiness",
            description = BREATHINESS_DESCRIPTION,
            value = config.breathiness,
            onChange = onBreathinessChange,
            unit = null,
            realWorldRange = null,
        )
    }
}
