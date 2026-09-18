package com.eloquick.ui

import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * Live [AccessibilityManager.isTouchExplorationEnabled] - true while TalkBack (or any other
 * touch-exploration screen reader) is active. Registered with a real listener rather than read
 * once, since a user can turn TalkBack on or off without restarting this app. Internal (not
 * private) so other screens can reach for the same check without duplicating the listener
 * plumbing, the way [ExpandableSection] below does for its own expand/collapse animation.
 */
@Composable
internal fun rememberIsTouchExplorationEnabled(): Boolean {
    val context = LocalContext.current
    val accessibilityManager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var enabled by remember(accessibilityManager) { mutableStateOf(accessibilityManager?.isTouchExplorationEnabled == true) }
    DisposableEffect(accessibilityManager) {
        if (accessibilityManager == null) return@DisposableEffect onDispose {}
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        accessibilityManager.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibilityManager.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

/**
 * A collapsible header + content block, ported from Tarang's own Sound Zone screen - lets a
 * screen with many settings open on a short, scannable list of headers instead of every control
 * from every group stacked in one long scroll. Collapsed by default; each header's [summary] is
 * deliberately the "what's currently set" line (e.g. "Quiet" or "Natural, natural time reading")
 * so a listener can tell what's active without expanding anything - expanding is only needed to
 * *change* a setting, not to check its current value.
 *
 * No manual `contentDescription` here on purpose: [title] and [summary] are plain on-screen
 * [Text], and a clickable [Row] already merges its descendants' text into one TalkBack
 * announcement, so the natural reading ("Numbers, dates & times, Natural, natural time reading,
 * button") already says everything. [stateDescription] is the one thing that genuinely needs a
 * manual label, since "expanded/collapsed" isn't otherwise conveyed except by a chevron rotating,
 * which a screen reader user can't see.
 *
 * Not marked `heading()`: this component's only remaining caller ([PronunciationDictionaryScreen]'s
 * entry rows) can render hundreds of these at once (855 for its own bundled English list) - a real
 * heading is a landmark worth jumping to, not something every one of hundreds of list rows should
 * claim, and TalkBack announces "...Heading" after every node that does. `Role.Button` (already set
 * below) is the correct native role for a row that performs an action on tap.
 *
 * The expand/collapse animation itself is skipped entirely - both [EnterTransition.None] and
 * [ExitTransition.None] - while TalkBack is running. A screen-reader user never sees the spring
 * animate in the first place (they navigate by swiping to the next node, not by watching a
 * section grow), so animating it for them was pure cost with no benefit: every frame of that
 * animation is real main-thread work competing with TalkBack's own accessibility-tree queries for
 * the same thread, right at the moment - just after expanding a section - a user is most likely
 * to start swiping into its newly-revealed content.
 */
@Composable
fun ExpandableSection(
    title: String,
    summary: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val touchExplorationEnabled = rememberIsTouchExplorationEnabled()
    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (expanded) "collapse" else "expand",
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    onExpandedChange(!expanded)
                }
                .semantics {
                    role = Role.Button
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                // Decorative - the chevron's meaning (expanded/collapsed) is already spoken via
                // stateDescription above, so a second description here would just repeat it.
                contentDescription = null,
            )
        }
        // A touch of physical bounce on the expand/collapse rather than a flat, linear-feeling
        // resize - same spring spec Tarang's own ExpandableSection tuned for this. Skipped
        // entirely under TalkBack - see this function's own doc comment for why.
        val spatialSpec = spring<IntSize>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
        AnimatedVisibility(
            visible = expanded,
            enter = if (touchExplorationEnabled) EnterTransition.None else fadeIn() + expandVertically(spatialSpec),
            exit = if (touchExplorationEnabled) ExitTransition.None else fadeOut() + shrinkVertically(spatialSpec),
        ) {
            Column(content = { content() })
        }
    }
}
