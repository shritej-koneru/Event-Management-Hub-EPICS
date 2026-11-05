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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
                bookmarked = res.data.statistics.isBookmarked
            }
            is Result.Error -> {
                errorMsg = res.message
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
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent)
                }
            } else {
                val e = event
                if (e == null) {
                    Text(
                        errorMsg ?: "Event not found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink2,
                        modifier = Modifier.padding(top = 24.dp)
                    )
                } else {
                    if (isStale) {
                        StatusBanner("Offline — showing saved details for this device")
                        Spacer(Modifier.height(12.dp))
                    }
                    errorMsg?.let {
                        ErrorBanner(it)
                        Spacer(Modifier.height(12.dp))
                    }

                    // ---- header ----
                    CategoryTag(e.category)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = e.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = Ink
                    )
                    if (e.host.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
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

                    // ---- facts: label / value rows ----
                    HubCard(Modifier.fillMaxWidth()) {
                        FactRow("When", "${HubFormat.dayTime(e.date, e.time)} · ${e.durationMin} min")
                        HubDivider(inset = 16.dp)
                        FactRow("Where", details?.venue?.name ?: e.venue)
                        HubDivider(inset = 16.dp)
                        FactRow("Registered", "${e.registered} / ${e.capacity}")
                    }

                    if (e.description.isNotBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = e.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink2
                        )
                    }
                    Spacer(Modifier.height(16.dp))

                    // ---- primary actions ----
                    PrimaryButton(
                        text = "Navigate to ${details?.venue?.name ?: e.venue}",
                        onClick = { onNavigate(e.venueId) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("navigate-button"),
                        trailingIcon = {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    )
                    Spacer(Modifier.height(8.dp))
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
                            .fillMaxWidth()
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
                    bookmarkError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Ink3)
                    }
                    Spacer(Modifier.height(24.dp))

                    // ---- statistics ----
                    details?.statistics?.let { stats ->
                        SectionCard("Statistics") {
                            StatRow("Seats remaining", "${stats.seatsRemaining}")
                            StatRow(
                                "Occupancy",
                                if (stats.occupancyPercent % 1.0 == 0.0) "${stats.occupancyPercent.toLong()}%"
                                else "${stats.occupancyPercent}%"
                            )
                            StatRow("Duration", "${stats.durationMin} min")
                            StatRow(
                                "In this category",
                                "${stats.categoryEventCount} of ${stats.totalEvents} events"
                            )
                            stats.daysUntilEvent?.let { days ->
                                StatRow(
                                    if (stats.isPast) "Status" else "Starts in",
                                    if (stats.isPast) "Already happened" else "$days day(s)"
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    // ---- charts ----
                    details?.charts?.takeIf { it.isNotEmpty() }?.let { charts ->
                        SectionCard("Charts") {
                            charts.forEachIndexed { i, chart ->
                                if (i > 0) Spacer(Modifier.height(16.dp))
                                ChartBlock(chart)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    // ---- event-scoped announcements ----
                    val scoped = details?.announcements.orEmpty()
                    SectionCard("Announcements for this event") {
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
                                    Text(
                                        a.message,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Ink
                                    )
                                    if (a.at.isNotBlank()) {
                                        Text(
                                            a.at.take(16).replace('T', ' '),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Ink3
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun FactRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Ink3, modifier = Modifier.width(88.dp))
        Text(value, style = MaterialTheme.typography.titleSmall, color = Ink, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    HubCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Ink)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Ink2, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall, color = Ink)
    }
}

/** Minimal horizontal bar rendering — enough to make the aggregate data visible. */
@Composable
private fun ChartBlock(chart: EventChart) {
    Column(Modifier.fillMaxWidth().testTag("chart-${chart.id}")) {
        Text(chart.title, style = MaterialTheme.typography.titleSmall, color = Ink)
        if (chart.total > 0) {
            Spacer(Modifier.height(8.dp))
            chart.data.forEach { slice ->
                val fraction = if (chart.total > 0) (slice.value / chart.total).toFloat().coerceIn(0f, 1f) else 0f
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                    Text(
                        slice.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink2,
                        modifier = Modifier.width(120.dp)
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .height(8.dp)
                            .background(SurfaceSunken, RoundedCornerShape(2.dp))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(fraction.coerceAtLeast(if (slice.value > 0) 0.02f else 0f))
                                .height(8.dp)
                                .background(Accent, RoundedCornerShape(2.dp))
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${slice.value.toInt()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink,
                        modifier = Modifier.widthIn(min = 24.dp),
                        textAlign = TextAlign.End
                    )
                }
            }
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
