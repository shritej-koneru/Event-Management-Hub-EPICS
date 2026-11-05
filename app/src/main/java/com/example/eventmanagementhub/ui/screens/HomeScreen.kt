package com.example.eventmanagementhub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.eventmanagementhub.data.HubRepository
import com.example.eventmanagementhub.data.Result
import com.example.eventmanagementhub.data.model.Event
import com.example.eventmanagementhub.ui.components.*
import com.example.eventmanagementhub.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onEventClick: (String) -> Unit,
    repository: HubRepository = HubRepository()
) {
    var events by remember { mutableStateOf<List<Event>>(emptyList()) }
    var recommendations by remember { mutableStateOf<List<Event>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    var isStale by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        errorMsg = null
        when (val res = repository.events()) {
            is Result.Success -> {
                events = res.data
                isStale = res.isStale
            }
            is Result.Error -> errorMsg = res.message
        }
        when (val recs = repository.recommendations()) {
            is Result.Success -> recommendations = recs.data.recommendations.map { it.event }
            is Result.Error -> recommendations = emptyList()
        }
    }

    LaunchedEffect(Unit) { load() }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) {
            // Clearing the box restores the full catalogue and drops stale reasons.
            reason = ""
            scope.launch { load() }
            return
        }
        scope.launch {
            searching = true
            errorMsg = null
            when (val res = repository.aiSearch(q)) {
                is Result.Success -> {
                    events = res.data.results.map { it.event }
                    reason = res.data.results.map { it.reason }.distinct().joinToString("\n") { "- ${it}" }
                    isStale = res.isStale
                }
                is Result.Error -> errorMsg = res.message
            }
            searching = false
        }
    }

    Box(Modifier.fillMaxSize().background(Paper)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                PageHeader(
                    title = "Event Hub",
                    description = "Find your next campus event"
                )
            }
            item {
                CustomSearchBar(
                    value = query,
                    onValueChange = { query = it },
                    onSearch = { runSearch() },
                    placeholder = "Ask: android workshop, music, ai…",
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(16.dp))
            }
            if (searching) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Accent)
                    }
                }
            }
            // Cached data is labelled explicitly instead of being presented as live.
            if (isStale && !searching) {
                item {
                    StatusBanner("Offline — showing saved events from this device", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
            errorMsg?.let { msg ->
                item { ErrorBanner(msg, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            }
            if (reason.isNotBlank() && !searching) {
                item {
                    HubCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Why these results", style = MaterialTheme.typography.titleSmall, color = Ink)
                            Spacer(Modifier.height(6.dp))
                            Text(reason, style = MaterialTheme.typography.bodySmall, color = Ink2)
                        }
                    }
                }
            }
            // While a query is active the results must sit near the search box;
            // otherwise the recommendation rows push them off the bottom
            // of the screen and the search looks like it returned nothing.
            if (recommendations.isNotEmpty() && query.isBlank()) {
                item {
                    SectionHeader("Recommended for you") {
                        scope.launch { load() }
                    }
                }
                items(recommendations.take(3), key = { "rec-${it.id}" }) { e ->
                    EventRow(event = e, onClick = { onEventClick(e.id) })
                }
            }
            item {
                // "All events" Refresh must actually re-query the backend.
                SectionHeader("All events") {
                    reason = ""
                    query = ""
                    scope.launch { load() }
                }
            }
            items(events, key = { "ev-${it.id}" }) { e ->
                EventRow(event = e, onClick = { onEventClick(e.id) })
            }
            if (events.isEmpty() && !searching && errorMsg == null) {
                item {
                    Text(
                        "No events matched. Try a different search.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink2,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun StatusBanner(text: String, modifier: Modifier = Modifier) {
    NoticeBanner(text, ChipKind.Warning, modifier)
}

@Composable
internal fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    NoticeBanner(message, ChipKind.Danger, modifier)
}

/** Heading with a quiet Refresh action; a hairline closes the section above its first row. */
@Composable
private fun SectionHeader(title: String, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
                modifier = Modifier.weight(1f)
            )
            QuietButton(
                text = "Refresh",
                onClick = onRefresh,
                modifier = Modifier.semanticsForTest("refresh-$title")
            )
        }
        HubDivider()
    }
}

/** Stable test hook so UI tests can target section refresh buttons by name. */
  internal fun Modifier.semanticsForTest(tag: String): Modifier =
      this.testTag(tag)
