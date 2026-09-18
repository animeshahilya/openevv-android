package com.eloquick.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.eloquick.BuildConfig

/**
 * A credits screen, not a settings screen: nothing here is configurable.
 * Exists so a listener can find out what's actually speaking to them and
 * where its pieces came from, the way a book's colophon does - without
 * turning it into a wall of individual names. Reached from the Reading
 * screen, not the bottom bar: worth one tap away, not a fourth permanent tab.
 */
@Composable
fun AboutScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    // Shared DetailScaffold, not a local copy of the app-bar scaffold.
    DetailScaffold(
        title = "About",
        onBack = onBack,
        modifier = modifier,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            item {
                Column(Modifier.padding(top = 12.dp, bottom = 12.dp)) {
                    Text("Eloquence Revived", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Version ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { HorizontalDivider() }

            item {
                AboutSection(
                    title = "How this is built",
                    body = "An Android system text-to-speech engine wrapping OpenEVV, an open " +
                        "rebuild of IBM's Eloquence engine - the voice many blind screen-reader " +
                        "users relied on for its clarity and speed. This app ships no cloud calls " +
                        "and no downloads: every voice is compiled directly into the native " +
                        "library, and it runs entirely on-device.",
                )
            }

            item { HorizontalDivider() }

            item {
                AboutSection(
                    title = "Built on",
                    body = "OpenEVV - a from-scratch, MIT-licensed C rebuild of IBM's Embedded " +
                        "ViaVoice (Eloquence) engine. The actual synthesis, every voice preset, " +
                        "and every language rule set come from this project.",
                )
            }

            item { HorizontalDivider() }

            item {
                AboutSection(
                    title = "Community contributions",
                    body = "A number of fixes in this app were ported from two long-running " +
                        "NVDA screen-reader driver projects for this same engine family - " +
                        "NVDA-IBMTTS-Driver and eloquence_64 - each carrying years of real-world " +
                        "testing against IBM's original engine: crash prevention, mispronunciation " +
                        "fixes, and classic voice names among them. Used under their own " +
                        "open-source licenses.",
                )
            }

            item { HorizontalDivider() }

            item {
                Text(
                    "Compiled and built by Animesh Ahilya.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 14.dp),
                )
            }

            item { Spacer(Modifier.padding(bottom = 24.dp)) }
        }
    }
}

/** One flat, card-free section - a heading and a body paragraph, separated from its neighbors by
 * the same thin [HorizontalDivider] every list on this app now uses, matching [MainScreen]'s own
 * flat-list restyling (2026-09-10) rather than each section drawing its own rounded card. */
@Composable
private fun AboutSection(title: String, body: String) {
    Column(Modifier.padding(vertical = 14.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
