package com.eloquick.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.eloquick.data.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic export/share plus background reliability, in one screen - see
 * [DiagnosticLog]'s own doc comment for what actually gets captured and why
 * it's this app's own file rather than a logcat scrape. Reached the same
 * way About/Pronunciation dictionary are, from a flat-list row.
 */
@Composable
fun TroubleshootScreen(modifier: Modifier = Modifier, onBack: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Bumped after Clear (and after returning from the battery-optimization
    // system dialog) to force a re-read - DiagnosticLog.snapshot() always
    // reads fresh from disk (see its own doc comment), there's just nothing
    // else in this screen's Compose state for a plain file's own contents
    // changing to invalidate.
    var refreshToken by remember { mutableIntStateOf(0) }
    // The full snapshot - what Export/Share actually write - can be close to
    // 1MB (two 512KB-capped process files). Compose's own Text/Paragraph
    // layout is not built for laying out a blob that size on every
    // recomposition; [displayText] below is what the screen actually
    // renders, capped far smaller, so opening this screen after a busy
    // session can't itself become the sluggish/janky thing being diagnosed.
    var logText by remember { mutableStateOf("") }
    var displayText by remember { mutableStateOf("Loading…") }
    LaunchedEffect(refreshToken) {
        val full = withContext(Dispatchers.IO) { DiagnosticLog.snapshot(context) }
        logText = full
        displayText = if (full.length > MAX_DISPLAY_CHARS) {
            "(showing the most recent portion only - Export or Share for the full log)\n\n" +
                full.takeLast(MAX_DISPLAY_CHARS)
        } else {
            full
        }
    }

    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    var ignoringBatteryOptimizations by remember {
        mutableStateOf(powerManager?.isIgnoringBatteryOptimizations(context.packageName) != false)
    }
    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        // The system dialog's own result code isn't a reliable signal across
        // OEMs (some report a cancel even after the user actually granted
        // it) - re-checking the real, authoritative source instead of
        // trusting the result is the only way to show the true state after.
        ignoringBatteryOptimizations = powerManager?.isIgnoringBatteryOptimizations(context.packageName) != false
    }

    // CreateDocument, not a plain intent - the system picker it shows is
    // itself the user's own explicit "Save As" choice of location, same
    // pattern PronunciationDictionaryScreen's own export already uses.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val textToWrite = logText
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(textToWrite.toByteArray(Charsets.UTF_8))
                    }
                }.isSuccess
            }
            if (!ok) onError("Couldn't save the diagnostic log")
        }
    }

    fun share() {
        val textToShare = logText
        scope.launch {
            // A cache file behind a FileProvider content:// URI, not
            // EXTRA_TEXT: a real device's log can run well past what a
            // binder transaction comfortably carries as an extra, and a
            // file is what "share to email/Drive/Files" expects anyway.
            val uri = withContext(Dispatchers.IO) {
                runCatching {
                    val shareDir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
                    val shareFile = File(shareDir, "eloquence-revived-diagnostics.txt")
                    shareFile.writeText(textToShare)
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shareFile)
                }.getOrNull()
            }
            if (uri == null) {
                onError("Couldn't prepare the diagnostic log to share")
                return@launch
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                context.startActivity(Intent.createChooser(intent, "Share diagnostic log"))
            }.onFailure { onError("Couldn't open the share sheet") }
        }
    }

    // Shared DetailScaffold (see Components.kt), not another copy of the app-bar scaffold.
    DetailScaffold(title = "Troubleshoot", onBack = onBack, modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(Modifier.padding(top = 12.dp, bottom = 8.dp)) {
                Text(
                    "Background reliability",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    if (ignoringBatteryOptimizations) {
                        "This app is already exempt from battery optimization - the system " +
                            "won't defer or stop it while idle."
                    } else {
                        "Some devices delay or stop background apps to save battery, which can " +
                            "make speech start late or a long-running screen reader session " +
                            "eventually stop responding. Exempting this app keeps it consistently " +
                            "responsive."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (!ignoringBatteryOptimizations) {
                    Button(
                        onClick = {
                            @SuppressLint("BatteryLife")
                            val intent = Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                "package:${context.packageName}".toUri(),
                            )
                            // A handful of OEM builds strip this system
                            // action entirely - a plain runCatching around
                            // the launch, same defensive shape every other
                            // optional-system-surface call in this app
                            // already uses (onOpenSystemTtsSettings in
                            // MainActivity, About's own link handling).
                            runCatching { batteryLauncher.launch(intent) }
                                .onFailure { onError("Couldn't open the battery settings dialog") }
                        },
                        modifier = Modifier.padding(top = 12.dp),
                    ) { Text("Exempt from battery optimization") }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            Column(Modifier.padding(bottom = 8.dp)) {
                Text(
                    "Diagnostic log",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    "What this app itself has recorded recently - not your device's system log " +
                        "- from both this screen's process and the one that actually speaks. " +
                        "Useful to attach when reporting a problem.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(onClick = { share() }, modifier = Modifier.weight(1f)) {
                        Text("Share")
                    }
                    OutlinedButton(
                        onClick = {
                            val stamp = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())
                            exportLauncher.launch("eloquence-revived-diagnostics-$stamp.txt")
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Export") }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { DiagnosticLog.clear(context) }
                                refreshToken++
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Clear") }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            SelectionContainer {
                Text(
                    displayText,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
    }
}

// Comfortably below what Compose's Text/Paragraph layout handles smoothly on
// a mid-range device, while still showing thousands of recent log lines -
// see displayText's own comment above.
private const val MAX_DISPLAY_CHARS = 40_000
