package com.example.eventmanagementhub.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.eventmanagementhub.data.HubRepository
import com.example.eventmanagementhub.data.Result
import com.example.eventmanagementhub.data.model.Event
import com.example.eventmanagementhub.ui.components.CustomSearchBar
import com.example.eventmanagementhub.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun EventsScreen(
    onEventClick: (String) -> Unit,
    repository: HubRepository = HubRepository()
) {
    var events by remember { mutableStateOf<List<Event>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var registeredOnly by remember { mutableStateOf(false) }
    var isStale by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var bookmarks by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        when (val res = repository.events()) {
            is Result.Success -> {
                events = res.data
                isStale = res.isStale
            }
            is Result.Error -> errorMsg = res.message
        }
        // Server bookmarks are the source of truth for the "Registered" filter.
        when (val res = repository.bookmarks()) {
            is Result.Success -> bookmarks = res.data
            is Result.Error -> bookmarks = repository.registeredIds()
        }
        query = ""
        loading = false
    }

    val shownEvents = if (registeredOnly) events.filter { bookmarks.contains(it.id) } else events

    fun refresh(q: String? = null) {
        scope.launch {
            loading = true
            errorMsg = null
            when (val res = repository.events(query = q?.takeIf { it.isNotBlank() })) {
                is Result.Success -> {
                    events = res.data
                    isStale = res.isStale
                }
                is Result.Error -> errorMsg = res.message
            }
            loading = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BodyBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            CustomSearchBar(
                value = query,
                onValueChange = { query = it },
                onSearch = { refresh(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Registered Events",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.Black,
                fontWeight = FontWeight.Bold
            )

            if (loading) {
                Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PrimaryPurple)
                }
            } else {
                Spacer(modifier = Modifier.height(12.dp))

                if (isStale) {
                    StatusBanner("Offline — showing saved events from this device")
                    Spacer(modifier = Modifier.height(12.dp))
                }
                errorMsg?.let {
                    ErrorBanner(it)
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !registeredOnly,
                        onClick = { registeredOnly = false },
                        label = { Text("All") }
                    )
                    FilterChip(
                        selected = registeredOnly,
                        onClick = { registeredOnly = true },
                        label = { Text("Registered") }
                    )
                    Text(
                        text = "${bookmarks.size} bookmarked",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedText,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(15.dp),
                    contentPadding = PaddingValues(bottom = 100.dp)
                ) {
                    items(shownEvents, key = { it.id }) { event ->
                        EventListCard(event = event, onClick = { onEventClick(event.id) })
                    }
                }

                if (shownEvents.isEmpty()) {
                    Text(
                        text = if (registeredOnly) "No registered events yet — open an event and tap Save" else "No events found",
                        modifier = Modifier.padding(top = 40.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedText
                    )
                }
            }
        }
    }
}

@Composable
fun EventListCard(event: Event, onClick: () -> Unit) {
    val (icon, gradient) = when (event.venueId) {
        "auditorium" -> (event.icon.takeIf { it.isNotBlank() } ?: "\uD83C\uDFA4") to IconPurpleGradient
        "room-204", "room-205", "lab-a", "lab-b" -> (event.icon.takeIf { it.isNotBlank() } ?: "\uD83E\uDD16") to IconTealGradient
        "main-hall", "cafeteria" -> (event.icon.takeIf { it.isNotBlank() } ?: "\uD83C\uDF89") to IconOrangeGradient
        else -> (event.icon.takeIf { it.isNotBlank() } ?: "\uD83C\uDFAB") to IconPurpleGradient
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(105.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(EventCardWhiteGradient)
                .padding(17.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon Box
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(gradient),
                contentAlignment = Alignment.Center
            ) {
                Text(text = icon, style = MaterialTheme.typography.headlineSmall, color = Color.White)
            }

            Spacer(modifier = Modifier.width(15.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = NavyText,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = "${event.venue} · ${event.date} ${event.time}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText
                )
                Text(
                    text = if (event.description.isNotBlank()) event.description else "View event details",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText,
                    maxLines = 1
                )
            }

            // Arrow
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = PrimaryPurple,
                modifier = Modifier.size(30.dp)
            )
        }
    }
}