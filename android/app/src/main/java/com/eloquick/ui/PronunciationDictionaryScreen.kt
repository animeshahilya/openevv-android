package com.eloquick.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.eloquick.tts.CommunityDictionaryUpdateCheck
import com.eloquick.tts.DictionaryKind
import com.eloquick.tts.DictionaryMergeResult
import com.eloquick.tts.PronunciationEntry
import com.eloquick.tts.countAcronymProtectedEntries
import com.eloquick.tts.decodeImportedDictionaryBytes
import com.eloquick.tts.decodePronunciationDictionary
import com.eloquick.tts.encodePronunciationDictionary
import com.eloquick.tts.mergeDictionaries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

/**
 * Stable identity for one row: the entry's own content plus which occurrence
 * of it this is ([index]'s count of equal predecessors), so exact duplicates
 * still get distinct keys while every other row's key survives siblings
 * being inserted or deleted around it.
 */
private fun entryKey(entries: List<PronunciationEntry>, index: Int, entry: PronunciationEntry): String {
    val occurrence = entries.subList(0, index).count { it == entry }
    return "${entry.find}\u0000${entry.replacement}\u0000${entry.caseSensitive}#$occurrence"
}

/**
 * The user-editable pronunciation dictionary: a plain find -> replacement
 * list, applied by the engine's own dictionary lookup at synthesis time
 * (see [com.eloquick.tts.dictionaryFileFor]) rather
 * than by anything on this screen - this list is just the data, storage and
 * import/export shape, unchanged by which mechanism ends up applying it. A
 * dedicated screen rather than another fixed-shape card on Reading - this
 * list is unbounded and each row needs its own edit/delete controls, which
 * doesn't fit that pattern. Reached the same way AboutScreen is.
 */
/**
 * A merge result, imported file, or export attempt all report back through
 * this one status line rather than each getting a separate ad-hoc Text -
 * [LiveRegionMode.Polite] so TalkBack announces the outcome ("Added 40 new
 * entries...") without a sighted user needing to scroll to it or a
 * screen-reader user needing to swipe to find it right after the tap that
 * caused it.
 */
/**
 * Shared by both dictionary screens (Pronunciation, Abbreviation) - same
 * data shape ([PronunciationEntry]), same storage/case-variant/import-export
 * rules, only the labels, screen title, and whether "Suggest phonetic
 * spelling" makes sense actually differ, configured declaratively by [DictionaryKind].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PronunciationDictionaryScreen(
    modifier: Modifier = Modifier,
    kind: DictionaryKind = DictionaryKind.MAIN,
    entries: List<PronunciationEntry>,
    onEntriesChange: (List<PronunciationEntry>) -> Unit,
    onSuggestPhoneticSpelling: suspend (String) -> String? = { null },
    // Pronunciation dictionary only (kind == MAIN) - see the button below.
    // Defaults are never reached there (MainActivity always passes real
    // ones for that kind), only used by the Abbreviation screen where
    // they're never called at all.
    onCheckForCommunityDictionaryUpdate: suspend () -> CommunityDictionaryUpdateCheck = { CommunityDictionaryUpdateCheck.UpToDate },
    onApplyCommunityDictionaryUpdate: suspend (String, String) -> DictionaryMergeResult =
        { _, _ -> DictionaryMergeResult(emptyList(), 0) },
    onBack: () -> Unit,
    screenTitle: String = kind.title,
    descriptionText: String = kind.description,
    findLabel: String = kind.findLabel,
    replaceLabel: String = kind.replaceLabel,
    emptyStateText: String = kind.emptyStateText,
    exportFileName: String = kind.exportFileName,
    showSuggestButton: Boolean = kind.supportsPhoneticSuggestions,
    // New: load dictionary file from text file (Windows-1252, tab-separated)
    onLoadDictFile: suspend (String, DictionaryKind) -> Int = { _, _ -> 6 },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Saveable, not just remembered: an import/export result must survive
    // rotation instead of vanishing mid-read.
    var statusMessage by rememberSaveable { mutableStateOf<String?>(null) }
    // Not saveable: losing this specific in-flight dialog state on rotation
    // just means re-tapping "Check for updates", the same as if the check
    // hadn't started yet - nothing is lost that a plain re-check doesn't recover.
    var updateAvailable by remember { mutableStateOf<CommunityDictionaryUpdateCheck.Available?>(null) }
    var checkingForUpdate by remember { mutableStateOf(false) }
    var downloadingUpdate by remember { mutableStateOf(false) }

    // Which entries are expanded into their editable form, keyed by content
    // (find + replacement + flags, disambiguated by occurrence for exact
    // duplicates) rather than by list index: index keys shift on every
    // insert/delete, leaving the wrong now-shifted row expanded. Content
    // keys survive siblings being added or removed. The row's own edits
    // migrate the key (see the onChange below): without that, typing the
    // first character would change the key out from under the set and the
    // row would collapse mid-word. (The LazyColumn slots themselves stay
    // index-keyed for the opposite reason: a content slot-key would remount
    // the row - and drop input focus - on that same first keystroke.)
    // Saveable as a List (Set has no built-in saver) so expanded rows
    // survive rotation, matching MainScreen's own expanded-sections state.
    var expandedEntries by rememberSaveable { mutableStateOf(emptyList<String>()) }

    fun mergeAndReport(incoming: List<PronunciationEntry>, source: String) {
        if (incoming.isEmpty()) {
            statusMessage = "No valid entries found in $source"
            return
        }
        val result = mergeDictionaries(entries, incoming)
        onEntriesChange(result.entries)
        val skipped = incoming.size - result.added
        // Told to the user as its own sentence rather than a parenthetical
        // aside - a message ending in two stacked "(...) (...)" clauses
        // reads badly for TalkBack, which is exactly who this status line
        // exists for (see its own liveRegion doc comment above). An
        // acronym-shaped import (WITH, AKA, LOL, ...) is kept case-sensitive
        // automatically so it can't also rewrite the ordinary lowercase word
        // - see countAcronymProtectedEntries's own doc comment.
        val protectedCount = countAcronymProtectedEntries(incoming)
        val protectedSentence = if (protectedCount > 0) {
            " $protectedCount ${if (protectedCount == 1) "entry looks" else "entries look"} like an acronym and " +
                "${if (protectedCount == 1) "was" else "were"} kept case-sensitive, so ${if (protectedCount == 1) "it" else "they"} " +
                "won't override an ordinary word spelled the same way."
        } else {
            ""
        }
        statusMessage = when {
            result.added == 0 -> "Nothing new from $source - every entry was already in your dictionary."
            skipped == 0 ->
                "Added ${result.added} new ${if (result.added == 1) "entry" else "entries"} from $source.$protectedSentence"
            else ->
                "Added ${result.added} new ${if (result.added == 1) "entry" else "entries"} from $source, " +
                    "$skipped already in your dictionary.$protectedSentence"
        }
    }

    // OpenDocument/CreateDocument rather than GetContent/plain intents: the
    // system picker they show is itself the user's explicit choice of file
    // and location, same as any other app's "Open"/"Save As" - nothing here
    // guesses a path or writes without the user picking exactly where.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val imported = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val text = decodeImportedDictionaryBytes(stream.readBytes())
                        decodePronunciationDictionary(text)
                    }
                }.getOrNull()
            }
            if (imported == null) {
                statusMessage = "Couldn't read that file"
            } else {
                mergeAndReport(imported, "the imported file")
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(encodePronunciationDictionary(entries).toByteArray(StandardCharsets.UTF_8))
                    }
                }.isSuccess
            }
            statusMessage = if (ok) "Dictionary exported" else "Couldn't save the file"
        }
    }
    val loadDictFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val kindForLoader = if (kind == DictionaryKind.ABBREVIATION) DictionaryKind.ABBREVIATION else DictionaryKind.MAIN
            val result = withContext(Dispatchers.IO) {
                onLoadDictFile(uri.toString(), kindForLoader)
            }
            val message = when (result) {
                0 -> "Dictionary loaded successfully"
                2 -> "File not found"
                6 -> "Couldn't load dictionary (access error or invalid format)"
                else -> "Dictionary load failed (error $result)"
            }
            statusMessage = message
        }
    }

    // Shared DetailScaffold (see Components.kt), not a third copy of the
    // app-bar scaffold.
    DetailScaffold(
        title = screenTitle,
        onBack = onBack,
        modifier = modifier,
    ) { padding ->
        // No manual collectionInfo here either (see MainScreen's own flat
        // list): description, status, import/export row, dividers, the Add
        // button, and the empty-state text are all items too, so a bare
        // entries.size count is wrong the same way.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            item {
                Text(
                    descriptionText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
                )
            }

            statusMessage?.let { message ->
                item {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(bottom = 12.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("text/*")) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Import file") }
                    OutlinedButton(
                        onClick = { exportLauncher.launch(exportFileName) },
                        enabled = entries.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Export file") }
                    OutlinedButton(
                        onClick = {
                            val kindForLoader = if (kind == DictionaryKind.ABBREVIATION) DictionaryKind.ABBREVIATION else DictionaryKind.MAIN
                            loadDictFileLauncher.launch(arrayOf("text/*"))
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Load dict file") }
                }
            }

            // Pronunciation only (kind == MAIN) - the bundled community
            // dictionary this checks against is a set of whole-word finds,
            // the shape this screen edits, not abbreviation expansions.
            // Network access only ever happens from this explicit tap - see
            // checkCommunityDictionaryUpdate's own doc comment.
            if (kind == DictionaryKind.MAIN) {
                item {
                    OutlinedButton(
                        onClick = {
                            checkingForUpdate = true
                            scope.launch {
                                when (val result = onCheckForCommunityDictionaryUpdate()) {
                                    is CommunityDictionaryUpdateCheck.Available -> updateAvailable = result
                                    CommunityDictionaryUpdateCheck.UpToDate ->
                                        statusMessage = "The bundled community dictionary is already up to date."
                                    is CommunityDictionaryUpdateCheck.CheckFailed ->
                                        statusMessage = "Couldn't check for a dictionary update: ${result.message}"
                                }
                                checkingForUpdate = false
                            }
                        },
                        enabled = !checkingForUpdate,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    ) { Text(if (checkingForUpdate) "Checking..." else "Check for community dictionary updates") }
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 14.dp)) }

            if (entries.isEmpty()) {
                item {
                    Text(
                        emptyStateText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }

            // Index-keyed (not content-keyed): the key must stay stable
            // while a row's own text fields are being edited - a content key
            // would change on the first keystroke, remounting the row and
            // dropping focus mid-word. Expansion state below is content-keyed
            // instead, so it (unlike the slot) survives siblings being
            // added or removed.
            itemsIndexed(entries, key = { index, _ -> index }) { index, entry ->
                val key = entryKey(entries, index, entry)
                PronunciationEntryRow(
                    entry = entry,
                    expanded = key in expandedEntries,
                    onExpandedChange = { isExpanded ->
                        expandedEntries = if (isExpanded) expandedEntries + key else expandedEntries - key
                    },
                    onChange = { updated ->
                        // Migrate the expansion key to the row's new
                        // content: without this, typing the first character
                        // changes the key out from under the set and the row
                        // collapses (and drops focus) mid-word.
                        val next = entries.toMutableList().also { it[index] = updated }
                        val newKey = entryKey(next, index, updated)
                        if (key in expandedEntries && newKey != key) {
                            expandedEntries = expandedEntries - key + newKey
                        }
                        onEntriesChange(next)
                    },
                    onDelete = { onEntriesChange(entries.toMutableList().also { it.removeAt(index) }) },
                    onSuggestPhoneticSpelling = onSuggestPhoneticSpelling,
                    findLabel = findLabel,
                    replaceLabel = replaceLabel,
                    showSuggestButton = showSuggestButton,
                    modifier = Modifier.semantics { collectionItemInfo = CollectionItemInfo(index, 1, 0, 1) },
                )
                HorizontalDivider()
            }

            item {
                OutlinedButton(
                    onClick = {
                        // Starts expanded - a brand new entry is always blank, and
                        // collapsed-by-default's whole point (see PronunciationEntryRow)
                        // is to skip showing edit fields for something the user isn't
                        // about to type into right now, which is exactly wrong here.
                        val blank = PronunciationEntry("", "")
                        expandedEntries = expandedEntries + entryKey(
                            entries + blank,
                            entries.count { it == blank },
                            blank,
                        )
                        onEntriesChange(entries + blank)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Add entry")
                }
            }

            item { Spacer(Modifier.padding(bottom = 24.dp)) }
        }
    }

    updateAvailable?.let { available ->
        val sizeMb = available.sizeBytes / (1024f * 1024f)
        AlertDialog(
            onDismissRequest = { if (!downloadingUpdate) updateAvailable = null },
            title = { Text("Community dictionary update available") },
            text = {
                Text(
                    "A newer version of the community dictionary is available " +
                        "(about ${"%.1f".format(sizeMb)} MB). Download and add its new entries to " +
                        "your Pronunciation dictionary? Nothing you've typed yourself will be changed.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        downloadingUpdate = true
                        scope.launch {
                            val result = runCatching {
                                onApplyCommunityDictionaryUpdate(available.downloadUrl, available.sha)
                            }
                            downloadingUpdate = false
                            updateAvailable = null
                            statusMessage = result.fold(
                                onSuccess = { merge ->
                                    if (merge.added == 0) {
                                        "Downloaded the update - every entry was already in your dictionary."
                                    } else {
                                        "Added ${merge.added} new ${if (merge.added == 1) "entry" else "entries"} " +
                                            "from the updated community dictionary."
                                    }
                                },
                                onFailure = { "Couldn't download the dictionary update." },
                            )
                        }
                    },
                    enabled = !downloadingUpdate,
                ) { Text(if (downloadingUpdate) "Downloading..." else "Download") }
            },
            dismissButton = {
                TextButton(onClick = { updateAvailable = null }, enabled = !downloadingUpdate) { Text("Not now") }
            },
        )
    }
}

/**
 * Collapsed by default, into one line (find -> replacement, plus a case-
 * sensitivity summary) rather than the two full text fields and a switch
 * this used to render unconditionally for every entry - the difference
 * between a screen that scrolls forever and one that doesn't the moment a
 * community dictionary gets merged in via Import file (hundreds of entries at once is routine for
 * a real-world word list). Reuses [ExpandableSection] rather than a second collapsible pattern - flat, no card,
 * matching the rest of this app's 2026-09-10 restyle to a native-Settings look.
 *
 * Local text-field state resyncs only on an *external* change to [entry]
 * (a different row's edit shouldn't stomp this one, and `remember(entry)`
 * naturally does nothing on the app's own echo of what was just typed) -
 * the same external-resync shape the punctuation screen's symbols field
 * uses so typing doesn't stall on a round trip through the config flow.
 */
@Composable
private fun PronunciationEntryRow(
    entry: PronunciationEntry,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onChange: (PronunciationEntry) -> Unit,
    onDelete: () -> Unit,
    onSuggestPhoneticSpelling: suspend (String) -> String?,
    modifier: Modifier = Modifier,
    findLabel: String = "Find",
    replaceLabel: String = "Replace with",
    showSuggestButton: Boolean = true,
) {
    var findText by remember(entry) { mutableStateOf(entry.find) }
    var replacementText by remember(entry) { mutableStateOf(entry.replacement) }
    val scope = rememberCoroutineScope()
    // "Suggesting..." while a request is in flight, "Couldn't suggest a
    // spelling" on a null result (a blank Find, or the engine refusing) -
    // transient, not persisted, and reset on every fresh tap so a stale
    // failure message doesn't linger next to a Find field someone has
    // since edited.
    var suggestStatus by remember(entry) { mutableStateOf<String?>(null) }

    val title = when {
        findText.isBlank() && replacementText.isBlank() -> "New entry"
        replacementText.isBlank() -> findText
        else -> "$findText → $replacementText"
    }
    val summary = if (entry.caseSensitive) "Case sensitive" else "Any case"

    ExpandableSection(title = title, summary = summary, expanded = expanded, onExpandedChange = onExpandedChange, modifier = modifier) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = findText,
                        onValueChange = { findText = it; onChange(entry.copy(find = it)) },
                        singleLine = true,
                        label = { Text(findLabel) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = replacementText,
                        onValueChange = { replacementText = it; onChange(entry.copy(replacement = it)) },
                        singleLine = true,
                        label = { Text(replaceLabel) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.semantics {
                        contentDescription = if (findText.isBlank()) "Delete entry" else "Delete pronunciation for $findText"
                    },
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                }
            }
            // Fills Replace with what the engine's own letter-to-sound rules
            // already say for Find, in the engine's own phonetic-annotation
            // syntax (dictionaryFileFor's writer passes it straight through
            // to eciSetDict untouched - no translation needed to use this
            // as-is, or as a base to hand-tune). Disabled on a blank Find:
            // nothing to ask the engine about yet. Omitted entirely on the
            // Abbreviation dictionary (showSuggestButton = false) - asking
            // the engine to pronounce "kg" as a whole word is not what a
            // caller of this row wants.
            if (showSuggestButton) {
                OutlinedButton(
                    onClick = {
                        suggestStatus = "Asking the engine..."
                        scope.launch {
                            val suggestion = onSuggestPhoneticSpelling(findText)
                            if (suggestion.isNullOrBlank()) {
                                suggestStatus = "Couldn't suggest a spelling for \"$findText\""
                            } else {
                                replacementText = suggestion
                                onChange(entry.copy(find = findText, replacement = suggestion))
                                suggestStatus = null
                            }
                        }
                    },
                    enabled = findText.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) { Text("Suggest phonetic spelling") }
                suggestStatus?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // hideFromAccessibility - the Switch right below already carries this
                // exact label as its own contentDescription, so without this TalkBack
                // read "Case sensitive" twice in a row for every entry in the list.
                Text(
                    "Case sensitive",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { hideFromAccessibility() },
                )
                Switch(
                    checked = entry.caseSensitive,
                    onCheckedChange = { onChange(entry.copy(caseSensitive = it)) },
                    modifier = Modifier.semantics {
                        contentDescription = if (findText.isBlank()) "Case sensitive" else "Case sensitive match for $findText"
                    },
                )
            }
        }
    }
}
