package com.example.eventmanagementhub.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.eventmanagementhub.ui.theme.*

// Layout-polish building blocks for the attendee screens (docs/DESIGN_SYSTEM.md): flat, hairline,
// one accent, no shimmer. Shared by Home / Events / Detail / Profile / Updates.

/** Overline day header on `paper` ("Today", "Tomorrow", "Sat 9 Nov"). */
@Composable
fun GroupHeader(text: String, modifier: Modifier = Modifier) {
    SectionLabel(
        text,
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)
    )
}

/** Section title row with an optional quiet link on the right and a hairline below. */
@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Ink, modifier = Modifier.weight(1f))
            if (actionLabel != null && onAction != null) QuietButton(actionLabel, onAction)
        }
        HubDivider()
    }
}

/** Static placeholder block (surface-sunken, no shimmer). */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, height: Dp = 14.dp, widthFraction: Float = 1f) {
    Box(
        modifier.fillMaxWidth(widthFraction).height(height)
            .clip(RoundedCornerShape(4.dp)).background(SurfaceSunken)
    )
}

/** List skeleton: [rows] placeholder rows shaped like [HubListRow]. */
@Composable
fun SkeletonRows(modifier: Modifier = Modifier, rows: Int = 6) {
    Column(
        modifier.fillMaxWidth().background(HubSurface).semantics { contentDescription = "Loading" }
    ) {
        repeat(rows) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(SurfaceSunken))
                Spacer(Modifier.width(34.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonBlock(widthFraction = 0.7f, height = 14.dp)
                    SkeletonBlock(widthFraction = 0.45f, height = 12.dp)
                }
            }
            HubDivider(inset = 16.dp)
        }
    }
}

/** Skeleton for a bordered panel (detail screen). */
@Composable
fun SkeletonPanel(modifier: Modifier = Modifier, lines: Int = 3) {
    HubCard(modifier.fillMaxWidth().semantics { contentDescription = "Loading" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SkeletonBlock(widthFraction = 0.4f, height = 16.dp)
            repeat(lines) { SkeletonBlock(widthFraction = if (it == lines - 1) 0.6f else 1f, height = 12.dp) }
        }
    }
}

/** Empty state: one ink-2 line + one quiet action. No illustrations. */
@Composable
fun EmptyHint(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Ink2)
        if (actionLabel != null && onAction != null) QuietButton(actionLabel, onAction)
    }
}

/** Segmented control (All / Saved): 6dp radius, strong hairline, accent-soft selection, 48dp tall. */
@Composable
fun HubSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier.fillMaxWidth(), shape = ControlShape, color = HubSurface, border = HairlineStrong) {
        Row(Modifier.height(48.dp)) {
            options.forEachIndexed { i, label ->
                val sel = i == selectedIndex
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .background(if (sel) AccentSoft else HubSurface)
                        .clickable { onSelect(i) }
                        .semantics { selected = sel; role = Role.Tab },
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = if (sel) Accent else Ink2)
                }
                if (i < options.lastIndex) Box(Modifier.width(1.dp).fillMaxHeight().background(LineStrong))
            }
        }
    }
}

/** Pull-to-refresh container with the flat accent indicator. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubPullRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = HubSurface,
                color = Accent
            )
        },
        content = content
    )
}

/** Gentle expand/collapse for sections (<= 200ms). */
fun Modifier.hubExpand(): Modifier = this.animateContentSize(animationSpec = tween(180))

/** Small unread dot (warning colour = needs attention). */
@Composable
fun UnreadDot(modifier: Modifier = Modifier) {
    Box(
        modifier.size(8.dp).clip(CircleShape).background(Warning)
            .semantics { contentDescription = "Unread" }
    )
}

/** Capacity bar: 6dp track on surface-sunken, accent fill, tabular numbers beside it. */
@Composable
fun CapacityBar(registered: Int, capacity: Int, fraction: Float, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(SurfaceSunken)
                .semantics { contentDescription = "Capacity $registered of $capacity" }
        ) {
            if (fraction > 0f) {
                Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).fillMaxHeight().background(Accent))
            }
        }
    }
}
