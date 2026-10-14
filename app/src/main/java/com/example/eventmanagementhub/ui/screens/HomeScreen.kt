package com.example.eventmanagementhub.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.eventmanagementhub.data.HubRepository
import com.example.eventmanagementhub.data.Result
import com.example.eventmanagementhub.data.model.Event
import com.example.eventmanagementhub.ui.components.CustomSearchBar
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

    Box(Modifier.fillMaxSize().background(BodyBackground)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp)
        ) {
            item {
                Text(
                    text = "Event Hub",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Find your next campus event with AI search",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedText
                )
            }
            item {
                CustomSearchBar(
                    value = query,
                    onValueChange = { query = it },
                    onSearch = { runSearch() },
                    placeholder = "Ask: android workshop, music, ai…"
                )
            }
            if (searching) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = PrimaryPurple)
                    }
                }
            }
            // Cached data is labelled explicitly instead of being presented as live.
            if (isStale && !searching) {
                item { StatusBanner("Offline — showing saved events from this device") }
            }
            errorMsg?.let { msg ->
                item { ErrorBanner(msg) }
            }
            if (reason.isNotBlank() && !searching) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, CardBorder)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Why these results", fontWeight = FontWeight.Bold, color = NavyText)
                            Spacer(Modifier.height(8.dp))
                            Text(reason, style = MaterialTheme.typography.bodySmall, color = MutedText)
                        }
                    }
                }
            }
            // While a query is active the results must sit near the search box;
            // otherwise the three recommendation cards push them off the bottom
            // of the screen and the search looks like it returned nothing.
            if (recommendations.isNotEmpty() && query.isBlank()) {
                item {
                    SectionHeader("Recommended for you") {
                        scope.launch { load() }
                    }
                }
                items(recommendations.take(3), key = { "rec-${it.id}" }) { e ->
                    EventListCard(event = e, onClick = { onEventClick(e.id) })
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
                EventListCard(event = e, onClick = { onEventClick(e.id) })
            }
            if (events.isEmpty() && !searching && errorMsg == null) {
                item {
                    Text(
                        "No events matched. Try a different search.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedText
                    )
                }
            }
        }
    }
}

@Composable
internal fun StatusBanner(text: String) {
    Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFFFFF4E5)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFB26A00),
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        )
    }
}

@Composable
internal fun ErrorBanner(message: String) {
    Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFFFDECEC)) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        )
    }
}

@Composable
private fun SectionHeader(title: String, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = NavyText,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            onClick = onRefresh,
            modifier = Modifier.semanticsForTest("refresh-$title")
        ) {
            Text("Refresh", color = PrimaryPurple)
        }
    }
}

/** Stable test hook so UI tests can target section refresh buttons by name. */
  internal fun Modifier.semanticsForTest(tag: String): Modifier =
      this.testTag(tag)