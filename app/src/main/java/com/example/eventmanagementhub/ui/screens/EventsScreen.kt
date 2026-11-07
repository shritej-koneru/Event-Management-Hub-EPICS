package com.example.eventmanagementhub.ui.screens

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import java.time.LocalDateTime

@Composable
fun EventsScreen(
    onEventClick: (String) -> Unit,
    repository: HubRepository = HubRepository()
) {
    var events by remember { mutableStateOf<List<Event>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var registeredOnly by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf<String?>(null) }
    var pastExpanded by remember { mutableStateOf(false) }
    var isStale by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var bookmarks by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()

    suspend fun loadBookmarks() {
        // Server bookmarks are the source of truth for the "Saved" filter.
        when (val res = repository.bookmarks()) {
            is Result.Success -> bookmarks = res.data
            is Result.Error -> bookmarks = repository.registeredIds()
        }
    }

    suspend fun loadEvents(q: String?) {
        errorMsg = null
        when (val res = repository.events(query = q?.takeIf { it.isNotBlank() })) {
            is Result.Success -> {
                events = res.data
                isStale = res.isStale
            }
            is Result.Error -> errorMsg = res.message
        }
    }

    LaunchedEffect(Unit) {
        loadEvents(null)
        loadBookmarks()
        query = ""
        loading = false
    }

    fun refresh(q: String? = null) {
        scope.launch {
            loading = true
            loadEvents(q)
            loading = false
        }
    }

    fun pullRefresh() {
        scope.launch {
            refreshing = true
            loadEvents(query)
            loadBookmarks()
            refreshing = false
        }
    }

    val now = remember(events) { LocalDateTime.now() }
    val categories = remember(events) { EventLogic.categories(events) }
    // A selected category that vanished after a refresh must not leave an empty list behind.
    val activeCategory = category?.takeIf { c -> categories.any { it.equals(c, ignoreCase = true) } }
    val shownEvents = EventLogic
        .filterByCategory(if (registeredOnly) events.filter { bookmarks.contains(it.id) } else events, activeCategory)
    val sections = remember(shownEvents, now) { EventLogic.sections(shownEvents, now) }

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
        Spacer(Modifier.height(12.dp))
        HubSegmented(
            options = listOf("All", "Saved (${bookmarks.size})"),
            selectedIndex = if (registeredOnly) 1 else 0,
            onSelect = { registeredOnly = it == 1 },
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        if (categories.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { HubFilterChip("All", selected = activeCategory == null, onClick = { category = null }) }
                items(categories, key = { it }) { c ->
                    HubFilterChip(c, selected = activeCategory.equals(c, ignoreCase = true), onClick = { category = c })
                }
            }
        } else {
            Spacer(Modifier.height(8.dp))
        }
        HubDivider()

        if (loading) {
            SkeletonRows(rows = 6)
        } else {
            HubPullRefresh(
                refreshing = refreshing,
                onRefresh = { pullRefresh() },
                modifier = Modifier.weight(1f)
            ) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (isStale) {
                        item {
                            StatusBanner("Offline — showing saved events from this device", Modifier.padding(16.dp))
                        }
                    }
                    errorMsg?.let {
                        item { ErrorBanner(it, Modifier.padding(16.dp)) }
                    }
                    sections.upcoming.forEach { group ->
                        item(key = "h-${group.label}") { GroupHeader(group.label) }
                        items(group.events, key = { "e-${it.id}" }) { event ->
                            EventRow(event = event, onClick = { onEventClick(event.id) })
                        }
                    }
                    if (sections.past.isNotEmpty()) {
                        item(key = "past-header") {
                            PastHeader(sections.past.size, pastExpanded) { pastExpanded = !pastExpanded }
                        }
                        if (pastExpanded) {
                            items(sections.past, key = { "p-${it.id}" }) { event ->
                                EventRow(
                                    event = event,
                                    dimmed = true,
                                    onClick = { onEventClick(event.id) },
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = tween(180), placementSpec = null, fadeOutSpec = tween(120)
                                    )
                                )
                            }
                        }
                    }
                    if (shownEvents.isEmpty()) {
                        item {
                            when {
                                registeredOnly -> EmptyHint(
                                    "No saved events yet. Open an event and tap Save.",
                                    actionLabel = "Browse all events",
                                    onAction = { registeredOnly = false }
                                )
                                activeCategory != null -> EmptyHint(
                                    "No events in this category.",
                                    actionLabel = "Show all categories",
                                    onAction = { category = null }
                                )
                                else -> EmptyHint(
                                    "No events found.",
                                    actionLabel = "Refresh",
                                    onAction = { query = ""; refresh(null) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PastHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        HubDivider()
        Row(
            Modifier.fillMaxWidth().background(HubSurface).clickable(onClick = onToggle)
                .heightIn(min = 48.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Past events ($count)",
                style = MaterialTheme.typography.titleSmall,
                color = Ink2,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse past events" else "Expand past events",
                tint = Ink3
            )
        }
        HubDivider()
    }
}
