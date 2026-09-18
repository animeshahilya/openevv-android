package com.eloquick.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * The one "pushed screen" scaffold: app bar with a title heading and a Back
 * affordance, shared by every full-screen destination ([MainScreen]'s own
 * per-group detail host, [AboutScreen], [PronunciationDictionaryScreen])
 * instead of three copies of the same Scaffold + CenterAlignedTopAppBar.
 * System-back handling stays with each caller (each owns its own
 * plain-boolean navigation state); only the visuals are shared.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        content(padding)
    }
}

/**
 * A labeled tuning slider with a value announced in human terms
 * (contentDescription says what it controls, stateDescription carries the
 * current value) so TalkBack reads it once, cleanly, instead of a bare
 * numeric slider plus a separately-read label.
 *
 * [onValueChange] fires once, when a drag *ends*, not on every pixel dragged
 * across - every caller wires it straight to a setter that writes through
 * SharedPreferences and republishes the app's whole [TtsConfig] StateFlow,
 * which every open screen collects and recomposes from. Firing that on
 * every drag pixel (the platform [Slider]'s own default behavior) meant
 * dragging any of this app's eight tuning sliders wrote to disk and
 * recomposed the current screen dozens of times a second - real, measurable
 * jank that a screen-reader user would feel as sluggish navigation right
 * after, since TalkBack's own accessibility-tree work competes for the same
 * main thread. [dragPosition] tracks the live thumb position locally
 * (cheap, scoped to this one slider) while a drag is in progress, so the
 * slider itself still tracks a finger smoothly - only the expensive part is
 * deferred.
 */
@Composable
fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..100f,
    steps: Int = 0,
) {
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "$label: $valueText",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { hideFromAccessibility() },
        )
        Slider(
            value = dragPosition ?: value,
            onValueChange = { dragPosition = it },
            onValueChangeFinished = {
                dragPosition?.let(onValueChange)
                dragPosition = null
            },
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = label
                    stateDescription = valueText
                },
        )
    }
}

// Material3's default button content padding (24dp horizontal) is sized for
// a button sitting on its own; four of them sharing a row with 8dp gutters
// leaves too little width for the text at that padding - "Some" was
// wrapping into "Som"/"e" on a Pixel 8 while "None"/"Most"/"All" happened to
// still fit, purely because 'm' is a wider glyph than 'n'/'s'/'l'. A tighter
// padding gives every label room; maxLines/ellipsis is the safety net for
// whatever preset name doesn't fit even then, on any device.
private val ChoiceButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)

/**
 * A row of mutually-exclusive named choices, filled when selected and
 * outlined otherwise - a shared shape so a preset row (e.g. punctuation's
 * None/Some/Most/All) doesn't need its own copy of the same pattern.
 * [selected] may match none of [options] (a custom combination that isn't
 * any preset) - nothing is rendered filled in that case, which is itself
 * the honest state.
 */
@Composable
fun <T> ChoiceButtonRow(
    options: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    // Spoken before every option ("Digits at a time: 2, selected") so a
    // button that would otherwise announce as a bare number carries its
    // group context - the row's own heading Text is then hidden from
    // accessibility at the call site to avoid saying it twice.
    groupLabel: String? = null,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            val desc = buildList {
                if (groupLabel != null) add(groupLabel)
                add(label)
                if (isSelected) add("selected")
            }.joinToString(", ")
            if (isSelected) {
                Button(
                    onClick = { onSelect(value) },
                    contentPadding = ChoiceButtonPadding,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = desc },
                ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { hideFromAccessibility() }) }
            } else {
                OutlinedButton(
                    onClick = { onSelect(value) },
                    contentPadding = ChoiceButtonPadding,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = desc },
                ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { hideFromAccessibility() }) }
            }
        }
    }
}

/**
 * A "pick one of N" picker rendered as a real dialog with one RadioButton
 * row per option, replacing the popup-anchored `DropdownMenu` this app used
 * to open from its dropdown-style setting cards (audio quality, number
 * reading style, punctuation verbosity).
 *
 * Two concrete problems with that popup, not just a style preference: a
 * `DropdownMenu` is a `Popup`, not a real window, and TalkBack doesn't
 * reliably announce entering it or move focus inside it the way it does for
 * an actual `Dialog` - a screen-reader user could open the menu and have
 * their next swipe land back on whatever was already focused underneath,
 * with no indication anything opened at all. And nothing in a
 * `DropdownMenuItem` said which option was the *current* one, so even a
 * sighted user re-opening "Audio quality" saw three equally-plain rows with
 * no indication which one was active. A real dialog fixes the first
 * (TalkBack grabs focus into any `Dialog` on open); the explicit
 * `RadioButton` + `selected` semantics on each row fixes the second; and the
 * scrollable [Column] means a longer option list - more than these three
 * pickers happen to have today - would still be fully reachable rather than
 * silently clipped, on any screen size.
 */
@Composable
fun <T> SingleChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (value, label) ->
                    val isSelected = value == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClickLabel = label, role = Role.RadioButton) {
                                onSelect(value)
                                onDismissRequest()
                            }
                            .clearAndSetSemantics {
                                contentDescription = label
                                this.selected = isSelected
                                role = Role.RadioButton
                                // clearAndSetSemantics wipes the clickable's
                                // own action above: re-expose it here, or
                                // TalkBack announces a radio button with no
                                // activate action at all.
                                onClick(label) {
                                    onSelect(value)
                                    onDismissRequest()
                                    true
                                }
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = isSelected, onClick = null, modifier = Modifier.clearAndSetSemantics {})
                        Text(label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) { Text("Close") }
        },
    )
}

/**
 * The button+dialog pair behind every "pick one of N" setting on this
 * screen (audio quality, number reading style, punctuation verbosity) -
 * pulled out once every place that needed one turned out to be the same
 * code with different titles. Opens a real [SingleChoiceDialog], not a
 * `DropdownMenu` popup - see that composable's own doc for why. TalkBack
 * hears "$title: $label. Tap to change."; the trailing arrow is a purely
 * visual echo of that same `Role.DropdownList` for sighted users, so the
 * button reads as "opens a list" rather than "acts immediately" the way a
 * filled preset button does.
 */
@Composable
fun <T> DropdownPickerButton(
    title: String,
    label: String,
    selected: T,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(
        onClick = { showDialog = true },
        modifier = Modifier.semantics {
            contentDescription = "$title: $label. Tap to change."
            role = Role.DropdownList
        },
    ) {
        Text(label, modifier = Modifier.semantics { hideFromAccessibility() })
        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.padding(start = 4.dp))
    }
    if (showDialog) {
        SingleChoiceDialog(
            title = title,
            options = options,
            selected = selected,
            onSelect = onSelect,
            onDismissRequest = { showDialog = false },
        )
    }
}

/**
 * A titled control row inside a section - the title [Text] is hidden from TalkBack
 * since [trailing] (a Switch/DropdownPickerButton/etc.) already carries an equivalent
 * contentDescription of its own, same "visible label, spoken once, off the control" pattern
 * [LabeledSlider] uses.
 */
@Composable
fun SectionRowHeading(title: String, trailing: (@Composable () -> Unit)? = null) {
    if (trailing == null) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { hideFromAccessibility() })
    } else {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).semantics { hideFromAccessibility() },
            )
            trailing()
        }
    }
}

@Composable
fun SectionDescription(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )
}

/**
 * One Speak/Stop toggle behind both the flat list's Preview button and
 * "Try your own text"'s Speak button - the same Play/Close icon swap and
 * label-hiding pattern in both places.
 */
@Composable
fun PreviewButton(
    isPreviewing: Boolean,
    idleLabel: String,
    idleDescription: String,
    stopDescription: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onToggle,
        modifier = modifier.semantics {
            contentDescription = if (isPreviewing) stopDescription else idleDescription
        },
    ) {
        Icon(if (isPreviewing) Icons.Filled.Close else Icons.Filled.PlayArrow, contentDescription = null)
        Text(
            if (isPreviewing) "Stop" else idleLabel,
            modifier = Modifier.padding(start = 8.dp).semantics { hideFromAccessibility() },
        )
    }
}
