package com.example.eventmanagementhub.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.eventmanagementhub.data.EventDetails
import com.example.eventmanagementhub.data.HubRepository
import com.example.eventmanagementhub.data.Result
import com.example.eventmanagementhub.data.model.Event
import com.example.eventmanagementhub.data.model.EventChart
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

    Box(Modifier.fillMaxSize().background(BodyBackground)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = NavyText)
                }
                Text(
                    "Event Details",
                    style = MaterialTheme.typography.titleMedium,
                    color = NavyText,
                    fontWeight = FontWeight.Bold
                )
            }

            if (loading) {
                Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PrimaryPurple)
                }
            } else {
                val e = event
                if (e == null) {
                    Text(
                        errorMsg ?: "Event not found",
                        color = MutedText,
                        modifier = Modifier.padding(top = 40.dp)
                    )
                } else {
                    Spacer(Modifier.height(12.dp))

                    if (isStale) {
                        StatusBanner("Offline — showing saved details for this device")
                        Spacer(Modifier.height(12.dp))
                    }
                    errorMsg?.let {
                        ErrorBanner(it)
                        Spacer(Modifier.height(12.dp))
                    }

                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                        border = BorderStroke(1.dp, CardBorder)
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(EventCardWhiteGradient)
                                .padding(20.dp)
                        ) {
                            Text(
                                text = e.title,
                                style = MaterialTheme.typography.headlineMedium,
                                color = NavyText,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "${e.host} · ${e.category}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MutedText
                            )
                            Spacer(Modifier.height(16.dp))

                            InfoRow(Icons.Default.Schedule, "${e.date} · ${e.time} · ${e.durationMin} min")
                            InfoRow(Icons.Default.LocationOn, details?.venue?.name ?: e.venue)
                            InfoRow(Icons.Default.People, "${e.registered} / ${e.capacity} registered")

                            if (e.tags.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    e.tags.forEach { tag -> TagChip(tag) }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))

                    if (e.description.isNotBlank()) {
                        Text(
                            text = e.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF4A4863)
                        )
                        Spacer(Modifier.height(16.dp))
                    }

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
                            charts.forEach { chart ->
                                ChartBlock(chart)
                                Spacer(Modifier.height(12.dp))
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
                                color = MutedText
                            )
                        } else {
                            scoped.forEach { a ->
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    modifier = Modifier.padding(bottom = 10.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Campaign,
                                        contentDescription = null,
                                        tint = PrimaryPurple,
                                        modifier = Modifier
                                            .padding(end = 10.dp)
                                            .size(18.dp)
                                    )
                                    Column {
                                        Text(
                                            a.message,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = NavyText
                                        )
                                        if (a.at.isNotBlank()) {
                                            Text(
                                                a.at.take(16).replace('T', ' '),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MutedText
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onNavigate(e.venueId) },
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("navigate-button"),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple)
                        ) {
                            Text("Navigate to ${details?.venue?.name ?: e.venue}", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                        }

                        OutlinedButton(
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
                                .height(52.dp)
                                .testTag("bookmark-button"),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, if (bookmarked) PrimaryPurple else CardBorder),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (bookmarked) PrimaryPurple else Color(0xFF3A3850)
                            )
                        ) {
                            Icon(
                                if (bookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (bookmarked) "Saved" else "Save", fontWeight = FontWeight.Bold)
                        }
                    }
                    bookmarkError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MutedText)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(EventCardWhiteGradient)
                .padding(16.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = NavyText, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MutedText, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = NavyText, fontWeight = FontWeight.SemiBold)
    }
}

/** Minimal horizontal bar rendering — enough to make the aggregate data visible. */
@Composable
private fun ChartBlock(chart: EventChart) {
    Column(Modifier.fillMaxWidth().testTag("chart-${chart.id}")) {
        Text(chart.title, style = MaterialTheme.typography.bodyMedium, color = NavyText, fontWeight = FontWeight.Medium)
        if (chart.total > 0) {
            Spacer(Modifier.height(8.dp))
            chart.data.forEach { slice ->
                val fraction = if (chart.total > 0) (slice.value / chart.total).toFloat().coerceIn(0f, 1f) else 0f
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                    Text(
                        slice.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedText,
                        modifier = Modifier.width(120.dp)
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .height(10.dp)
                            .background(Color(0xFFEDE9FF), RoundedCornerShape(5.dp))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(fraction.coerceAtLeast(if (slice.value > 0) 0.02f else 0f))
                                .height(10.dp)
                                .background(PrimaryPurple, RoundedCornerShape(5.dp))
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${slice.value.toInt()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = NavyText
                    )
                }
            }
            Text(
                "Total ${chart.total.toInt()} ${chart.unit}",
                style = MaterialTheme.typography.bodySmall,
                color = MutedText
            )
        } else {
            Text(
                "No chart data available.",
                style = MaterialTheme.typography.bodySmall,
                color = MutedText
            )
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = PrimaryPurple, modifier = Modifier.padding(end = 12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF3A3850))
    }
}

@Composable
private fun TagChip(label: String) {
    Surface(shape = RoundedCornerShape(50), color = Color(0xFFEDE9FF)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = PrimaryPurple,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}