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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.eventmanagementhub.data.HubRepository
import com.example.eventmanagementhub.data.Result
import com.example.eventmanagementhub.data.model.Event
import com.example.eventmanagementhub.ui.components.*
import com.example.eventmanagementhub.ui.theme.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

/** A recommended event plus the server's reason line (may be blank). */
private data class Recommended(val event: Event, val reason: String)

@Composable
fun HomeScreen(
    onEventClick: (String) -> Unit,
    repository: HubRepository = HubRepository(),
    /** Opens the Map tab with the event's venue as destination; defaults to the event details. */
    onDirections: (Event) -> Unit = { onEventClick(it.id) },
    onSeeAll: (() -> Unit)? = null,
    /** Opens the campus assistant. Shown only when the server says AI is on. */
    onAssistant: (() -> Unit)? = null
) {
    var events by remember { mutableStateOf<List<Event>>(emptyList()) }
    var recommendations by remember { mutableStateOf<List<Recommended>>(emptyList()) }
    var registeredIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
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
            is Result.Success -> recommendations = recs.data.recommendations.map { Recommended(it.event, it.reason) }
            is Result.Error -> recommendations = emptyList()
        }
        registeredIds = when (val b = repository.bookmarks()) {
            is Result.Success -> b.data
            is Result.Error -> repository.registeredIds()
        }
    }

    LaunchedEffect(Unit) {
        load()
        loading = false
    }

    fun pullRefresh() {
        scope.launch {
            refreshing = true
            reason = ""
            query = ""
            load()
            refreshing = false
        }
    }

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

    val now = remember(events) { LocalDateTime.now() }
    val nextUp = remember(events, registeredIds) { EventLogic.nextUp(events, registeredIds, now) }
    val thisWeek = remember(events) { EventLogic.upcomingThisWeek(events, now, max = 5) }
    val searchMode = query.isNotBlank()

    HubPullRefresh(
        refreshing = refreshing,
        onRefresh = { pullRefresh() },
        modifier = Modifier.fillMaxSize().background(Paper)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                PageHeader(
                    title = EventLogic.greeting(repository.loggedInUser()?.name),
                    description = EventLogic.todayLabel(LocalDate.now())
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
                if (onAssistant != null && rememberAiStatus().enabled) {
                    QuietButton("Ask the campus assistant", onAssistant, Modifier.padding(horizontal = 8.dp).testTag("open-assistant"))
                }
                Spacer(Modifier.height(16.dp))
            }
            if (searching) {
                item { SkeletonRows(rows = 3) }
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

            if (loading) {
                item { SkeletonRows(rows = 5) }
            } else if (searchMode) {
                // While a query is active the results must sit directly under the search box.
                item { SectionTitle("Results") }
                items(events, key = { "ev-${it.id}" }) { e ->
                    EventRow(event = e, onClick = { onEventClick(e.id) })
                }
                if (events.isEmpty() && !searching && errorMsg == null) {
                    item {
                        EmptyHint(
                            "No events matched. Try a different search.",
                            actionLabel = "Clear search",
                            onAction = { pullRefresh() }
                        )
                    }
                }
            } else {
                nextUp?.let { e ->
                    item(key = "next-up") {
                        NextUpPanel(
                            event = e,
                            onDirections = { onDirections(e) },
                            onDetails = { onEventClick(e.id) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                if (recommendations.isNotEmpty()) {
                    item { SectionTitle("Recommended for you") }
                    items(recommendations.take(3), key = { "rec-${it.event.id}" }) { r ->
                        EventRow(event = r.event, reason = r.reason.takeIf { it.isNotBlank() }, onClick = { onEventClick(r.event.id) })
                    }
                }
                item {
                    SectionTitle(
                        "Upcoming this week",
                        actionLabel = if (onSeeAll != null) "See all" else null,
                        onAction = onSeeAll
                    )
                }
                items(thisWeek, key = { "wk-${it.id}" }) { e ->
                    EventRow(event = e, onClick = { onEventClick(e.id) })
                }
                if (thisWeek.isEmpty() && errorMsg == null) {
                    item {
                        EmptyHint(
                            "Nothing scheduled in the next 7 days.",
                            actionLabel = if (onSeeAll != null) "Browse all events" else null,
                            onAction = onSeeAll
                        )
                    }
                }
            }
        }
    }
}

/** "Next up": date block + title + time · venue, with Directions and Details as quiet actions. */
@Composable
private fun NextUpPanel(
    event: Event,
    onDirections: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        SectionLabel("Next up", Modifier.padding(bottom = 6.dp))
        HubCard(Modifier.fillMaxWidth().testTag("next-up")) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.width(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        EventLogic.dayNumber(event.date),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Accent
                    )
                    Text(EventLogic.weekday(event.date), style = MaterialTheme.typography.labelMedium, color = Ink3)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        event.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    MetaText(EventLogic.timeVenue(event))
                }
            }
            HubDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                QuietButton("Directions", onDirections, Modifier.testTag("next-up-directions"))
                QuietButton("Details", onDetails, Modifier.testTag("next-up-details"))
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

/** Stable test hook so UI tests can target elements by name. */
internal fun Modifier.semanticsForTest(tag: String): Modifier =
    this.testTag(tag)
