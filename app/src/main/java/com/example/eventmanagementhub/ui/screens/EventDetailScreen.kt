package com.example.eventmanagementhub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.eventmanagementhub.data.EventDetails
import com.example.eventmanagementhub.data.HubRepository
import com.example.eventmanagementhub.data.Result
import com.example.eventmanagementhub.data.model.Event
import com.example.eventmanagementhub.data.model.EventChart
import com.example.eventmanagementhub.ui.components.*
import com.example.eventmanagementhub.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun EventDetailScreen(
    eventId: String,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    repository: HubRepository = HubRepository()
) {
    var event by remember { mutableStateOf<Event?>(null) }
    var details by remember { mutableStateOf<EventDetails?>(null) }
    var loading by remember { mutableStateOf(true) }
    var isStale by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var bookmarked by remember { mutableStateOf(false) }
    var bookmarkError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(eventId) {
        loading = true
        errorMsg = null
        // The details aggregate already carries the event, venue, statistics,
        // charts, scoped announcements and navigation in one round trip.
        when (val res = repository.eventDetails(eventId)) {
            is Result.Success -> {
                details = res.data
                event = res.data.event
                isStale = res.isStale
                // Offline the cached aggregate can be older than the device's own saved
                // list (which includes queued offline changes), so trust the device then.
                bookmarked = if (res.isStale) repository.isRegistered(eventId)
                    else res.data.statistics.isBookmarked
            }
            is Result.Error -> {
                errorMsg = res.message
                bookmarked = repository.isRegistered(eventId)
                // Fall back to the plain event so the screen still shows something.
                when (val basic = repository.event(eventId)) {
                    is Result.Success -> event = basic.data
                    is Result.Error -> errorMsg = basic.message
                }
            }
        }
        loading = false
    }

    Column(Modifier.fillMaxSize().background(Paper)) {
        HubTopBar(title = "Event details", onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            if (loading) {
                SkeletonBlock(widthFraction = 0.3f, height = 12.dp)
                Spacer(Modifier.height(12.dp))
                SkeletonBlock(widthFraction = 0.8f, height = 28.dp)
                Spacer(Modifier.height(20.dp))
                SkeletonPanel(lines = 3)
                Spacer(Modifier.height(16.dp))
                SkeletonPanel(lines = 2)
            } else {
                val e = event
                if (e == null) {
                    EmptyHint(errorMsg ?: "Event not found.", Modifier.padding(top = 8.dp), "Go back", onBack)
                } else {
                    if (isStale) {
                        StatusBanner("Offline — showing saved details for this device")
                        Spacer(Modifier.height(12.dp))
                    }
                    errorMsg?.let {
                        ErrorBanner(it)
                        Spacer(Modifier.height(12.dp))
                    }

                    // ---- header block ----
                    CategoryTag(e.category)
                    Spacer(Modifier.height(8.dp))
                    Text(text = e.title, style = MaterialTheme.typography.headlineMedium, color = Ink)
                    if (e.host.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Hosted by ${e.host}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink2
                        )
                    }
                    if (e.tags.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            e.tags.forEach { tag -> HubChip(tag, ChipKind.Neutral) }
                        }
                    }
                    Spacer(Modifier.height(16.dp))

                    // ---- facts: icon / label / value rows ----
                    HubCard(Modifier.fillMaxWidth()) {
                        FactRow(Icons.Default.CalendarToday, "Date and time", HubFormat.dayTime(e.date, e.time))
                        HubDivider(inset = 16.dp)
                        FactRow(
                            Icons.Default.Schedule, "Duration",
                            EventLogic.duration(e.durationMin).ifBlank { "Not set" }
                        )
                        HubDivider(inset = 16.dp)
                        FactRow(Icons.Default.Place, "Venue", details?.venue?.name ?: e.venue)
                    }
                    Spacer(Modifier.height(12.dp))

                    // ---- capacity ----
                    if (e.capacity > 0) {
                        val left = details?.statistics?.seatsRemaining ?: EventLogic.seatsLeft(e.registered, e.capacity)
                        HubCard(Modifier.fillMaxWidth().testTag("capacity")) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.People, null, tint = Ink3, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Text("Capacity", style = MaterialTheme.typography.bodySmall, color = Ink3, modifier = Modifier.weight(1f))
                                    Text(
                                        "${e.registered} / ${e.capacity}",
                                        style = MaterialTheme.typography.titleSmall, color = Ink
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                CapacityBar(e.registered, e.capacity, EventLogic.capacityFraction(e.registered, e.capacity))
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    if (left == 0) "Full" else "$left seats left · ${EventLogic.capacityPercent(e.registered, e.capacity)}% registered",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Ink2
                                )
                            }
                        }
                    }

                    if (e.description.isNotBlank()) {
                        Spacer(Modifier.height(20.dp))
                        SectionLabel("About")
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = e.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink2
                        )
                    }
                    Spacer(Modifier.height(24.dp))

                    // ---- announcements ----
                    val scoped = details?.announcements.orEmpty()
                    SectionCard("Announcements") {
                        if (scoped.isEmpty()) {
                            Text(
                                "No announcements for this event yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Ink2
                            )
                        } else {
                            scoped.forEachIndexed { i, a ->
                                if (i > 0) {
                                    Spacer(Modifier.height(10.dp))
                                    HubDivider()
                                    Spacer(Modifier.height(10.dp))
                                }
                                Column {
                                    if (a.at.isNotBlank()) {
                                        Text(
                                            SupportLogic.formatTime(a.at).ifBlank { a.at.take(16).replace('T', ' ') },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Ink3
                                        )
                                        Spacer(Modifier.height(2.dp))
                                    }
                                    Text(a.message, style = MaterialTheme.typography.bodyMedium, color = Ink)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))

                    // ---- statistics (capacity already shown above) ----
                    details?.statistics?.let { stats ->
                        SectionCard("Statistics") {
                            StatRow("In this category", "${stats.categoryEventCount} of ${stats.totalEvents} events")
                            stats.daysUntilEvent?.let { days ->
                                StatRow(
                                    if (stats.isPast) "Status" else "Starts in",
                                    if (stats.isPast) "Already happened" else if (days == 1) "1 day" else "$days days"
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    // ---- charts ----
                    details?.charts?.takeIf { it.isNotEmpty() }?.let { charts ->
                        SectionCard("Charts") {
                            charts.forEachIndexed { i, chart ->
                                if (i > 0) {
                                    Spacer(Modifier.height(12.dp))
                                    HubDivider()
                                    Spacer(Modifier.height(12.dp))
                                }
                                ChartBlock(chart)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }

        // ---- bottom action bar (sits above the app's bottom nav) ----
        val e = event
        if (!loading && e != null) {
            Column(Modifier.fillMaxWidth().background(HubSurface)) {
                HubDivider()
                bookmarkError?.let {
                    Text(
                        it, style = MaterialTheme.typography.bodySmall, color = Ink3,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SecondaryButton(
                        text = if (bookmarked) "Saved" else "Save",
                        onClick = {
                            val next = !bookmarked
                            scope.launch {
                                // Server is authoritative; the local cache is
                                // updated optimistically and reconciled.
                                when (val res = repository.syncBookmark(e.id, next)) {
                                    is Result.Success -> {
                                        bookmarked = res.data.bookmarked
                                        bookmarkError = null
                                    }
                                    is Result.Error -> {
                                        bookmarked = next
                                        bookmarkError = res.message
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("bookmark-button"),
                        leadingIcon = {
                            Icon(
                                if (bookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = null,
                                tint = if (bookmarked) Accent else Ink2,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                    PrimaryButton(
                        text = "Get directions",
                        onClick = { onNavigate(e.venueId) },
                        modifier = Modifier
                            .weight(1.4f)
                            .testTag("navigate-button"),
                        trailingIcon = {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FactRow(icon: ImageVector, label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Ink3, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = Ink3)
            Text(value, style = MaterialTheme.typography.titleSmall, color = Ink)
        }
    }
}

/** Consistent panel: heading, hairline, then content. */
@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    HubCard(Modifier.fillMaxWidth()) {
        Text(
            title, style = MaterialTheme.typography.titleMedium, color = Ink,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        HubDivider()
        Column(Modifier.fillMaxWidth().padding(16.dp)) { content() }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Ink2, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall, color = Ink)
    }
}

/** Minimal horizontal bars: label, thin track, tabular value. No gridlines, no decoration. */
@Composable
private fun ChartBlock(chart: EventChart) {
    Column(Modifier.fillMaxWidth().testTag("chart-${chart.id}")) {
        Text(chart.title, style = MaterialTheme.typography.titleSmall, color = Ink)
        if (chart.total > 0) {
            Spacer(Modifier.height(8.dp))
            chart.data.forEach { slice ->
                val fraction = if (chart.total > 0) (slice.value / chart.total).toFloat().coerceIn(0f, 1f) else 0f
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        slice.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink2,
                        modifier = Modifier.width(104.dp)
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .height(6.dp)
                            .background(SurfaceSunken, RoundedCornerShape(3.dp))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(fraction.coerceAtLeast(if (slice.value > 0) 0.02f else 0f))
                                .height(6.dp)
                                .background(Accent, RoundedCornerShape(3.dp))
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${slice.value.toInt()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink,
                        modifier = Modifier.widthIn(min = 28.dp),
                        textAlign = TextAlign.End
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Total ${chart.total.toInt()} ${chart.unit}",
                style = MaterialTheme.typography.bodySmall,
                color = Ink3
            )
        } else {
            Text(
                "No chart data available.",
                style = MaterialTheme.typography.bodySmall,
                color = Ink3
            )
        }
    }
}
