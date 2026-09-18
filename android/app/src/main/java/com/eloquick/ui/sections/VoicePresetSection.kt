package com.eloquick.ui.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.eloquick.tts.PRESET_COUNT
import com.eloquick.tts.SUPPORTED_LANGUAGES
import com.eloquick.tts.isValidPreset
import com.eloquick.tts.presetCategory
import com.eloquick.tts.presetName
import com.eloquick.ui.PreviewButton

/**
 * All nine supported languages as a plain single-choice list - picking one keeps the current
 * voice preset number (so "slot 1" of the new language, whatever it's actually named there,
 * stays selected) rather than resetting to preset 1 every time, since a language switch and a
 * preset switch are independent choices.
 */
@Composable
fun LanguageContent(currentLangId: Int, onSelectLanguage: (Int) -> Unit) {
    val current = SUPPORTED_LANGUAGES.firstOrNull { it.langId == currentLangId }
    SingleChoiceList(
        items = SUPPORTED_LANGUAGES,
        labelOf = { lang -> if (lang.experimental) "${lang.displayName} (experimental)" else lang.displayName },
        secondaryLabelOf = { null },
        selectedItem = current,
        onSelect = { onSelectLanguage(it.langId) },
    )
}

/**
 * The current language's eight built-in presets as a plain single-choice list - same shape as
 * [LanguageContent], just one language's worth of options instead of all nine languages.
 */
@Composable
fun VoiceContent(currentLangId: Int, currentPreset: Int, onSelectVoice: (Int, Int) -> Unit) {
    SingleChoiceList(
        items = (1..PRESET_COUNT).toList(),
        labelOf = ::presetName,
        secondaryLabelOf = ::presetCategory,
        selectedItem = currentPreset.takeIf { isValidPreset(it) },
        onSelect = { onSelectVoice(currentLangId, it) },
    )
}

/**
 * One "pick one of N" list (radio rows + dividers, correct collection
 * semantics) behind both [LanguageContent] and [VoiceContent].
 */
@Composable
private fun <T> SingleChoiceList(
    items: List<T>,
    labelOf: (T) -> String,
    secondaryLabelOf: (T) -> String?,
    selectedItem: T?,
    onSelect: (T) -> Unit,
) {
    Column(
        Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            .semantics { collectionInfo = CollectionInfo(items.size, 1) },
    ) {
        items.forEachIndexed { index, item ->
            SelectableRow(
                label = labelOf(item),
                secondaryLabel = secondaryLabelOf(item),
                isSelected = item == selectedItem,
                onSelect = { onSelect(item) },
                index = index,
            )
            if (index != items.lastIndex) HorizontalDivider()
        }
    }
}

/**
 * A single-choice list row (radio button + label), the shape [LanguageContent] and
 * [VoiceContent] both need.
 */
@Composable
private fun SelectableRow(label: String, secondaryLabel: String?, isSelected: Boolean, onSelect: () -> Unit, index: Int) {
    val fullLabel = if (secondaryLabel != null) "$label, $secondaryLabel" else label
    val announced = if (isSelected) "$fullLabel, selected" else fullLabel
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Select", role = Role.RadioButton, onClick = onSelect)
            .clearAndSetSemantics {
                contentDescription = announced
                selected = isSelected
                role = Role.RadioButton
                onClick("Select") {
                    onSelect()
                    true
                }
                collectionItemInfo = CollectionItemInfo(index, 1, 0, 1)
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = isSelected, onClick = null, modifier = Modifier.clearAndSetSemantics {})
        if (secondaryLabel != null) {
            Column(Modifier.padding(start = 8.dp)) {
                Text(label)
                Text(secondaryLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Text(label, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/**
 * Type anything, hear it in the current voice with current tuning.
 */
@Composable
fun OwnTextContent(
    text: String,
    onTextChange: (String) -> Unit,
    isPreviewing: Boolean,
    onSpeak: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text("Text to hear in this voice") },
            placeholder = { Text("Type names, numbers, anything you listen to") },
            trailingIcon = if (text.isNotEmpty()) {
                {
                    IconButton(
                        onClick = { onTextChange("") },
                        modifier = Modifier.semantics {
                            contentDescription = "Clear text"
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = null,
                        )
                    }
                }
            } else null,
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        val idleDescription = if (text.isBlank()) {
            "Speak the sample sentence in this voice"
        } else {
            "Speak your text in this voice"
        }
        PreviewButton(
            isPreviewing = isPreviewing,
            idleLabel = "Speak it",
            idleDescription = idleDescription,
            stopDescription = "Stop speaking your text",
            onToggle = { if (isPreviewing) onStop() else onSpeak() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
