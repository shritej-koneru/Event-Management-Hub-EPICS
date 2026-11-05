package com.example.eventmanagementhub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import com.example.eventmanagementhub.data.HubRepository
import com.example.eventmanagementhub.data.Result
import com.example.eventmanagementhub.data.model.Event
import com.example.eventmanagementhub.ui.components.*
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
    ) {
        PageHeader(
            title = "Events",
            description = "Browse the campus programme or filter to the ones you saved"
        )
        CustomSearchBar(
            value = query,
            onValueChange = { query = it },
            onSearch = { refresh(it) },
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (loading) {
            Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent)
            }
        } else {
            if (isStale) {
                StatusBanner("Offline — showing saved events from this device", Modifier.padding(horizontal = 16.dp))
                Spacer(modifier = Modifier.height(8.dp))
            }
            errorMsg?.let {
                ErrorBanner(it, Modifier.padding(horizontal = 16.dp))
                Spacer(modifier = Modifier.height(8.dp))
            }

            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HubFilterChip("All", selected = !registeredOnly, onClick = { registeredOnly = false })
                HubFilterChip("Registered", selected = registeredOnly, onClick = { registeredOnly = true })
                Text(
                    text = "${bookmarks.size} bookmarked",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink3,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HubDivider()

            LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(shownEvents, key = { it.id }) { event ->
                    EventRow(event = event, onClick = { onEventClick(event.id) })
                }
            }

            if (shownEvents.isEmpty()) {
                Text(
                    text = if (registeredOnly) "No registered events yet — open an event and tap Save" else "No events found",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink2
                )
            }
        }
    }
}
