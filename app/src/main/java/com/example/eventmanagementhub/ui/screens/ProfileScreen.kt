package com.example.eventmanagementhub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.eventmanagementhub.data.HubRepository
import com.example.eventmanagementhub.data.OfflineStore
import com.example.eventmanagementhub.data.Result
import com.example.eventmanagementhub.data.SessionStore
import com.example.eventmanagementhub.data.model.Event
import com.example.eventmanagementhub.data.model.UserProfile
import com.example.eventmanagementhub.ui.components.*
import com.example.eventmanagementhub.ui.theme.*
import kotlinx.coroutines.launch

private val INTEREST_OPTIONS = listOf("tech", "ai", "android", "music", "arts", "wellness", "sports", "career")

@Composable
fun ProfileScreen(
    repository: HubRepository = HubRepository(),
    onEventClick: (String) -> Unit,
    onSignOut: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var bookmarks by remember { mutableStateOf(repository.registeredIds()) }
    var isStale by remember { mutableStateOf(false) }
    var interests by remember { mutableStateOf(repository.loggedInUser()?.interests.orEmpty()) }
    var status by remember { mutableStateOf<String?>(null) }

    // Pull the authoritative bookmark list and profile from the server.
    LaunchedEffect(Unit) {
        when (val res = repository.bookmarks()) {
            is Result.Success -> {
                bookmarks = res.data
                isStale = res.isStale
            }
            is Result.Error -> {
                isStale = true
                status = res.message
            }
        }
        repository.restoreSession()
        interests = repository.loggedInUser()?.interests.orEmpty()
    }

    fun toggleInterest(tag: String) {
        val next = if (interests.contains(tag)) interests - tag else interests + tag
        interests = next
        scope.launch {
            when (val res = repository.updateInterests(next)) {
                is Result.Success -> status = "Interests saved"
                is Result.Error -> status = res.message
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            PageHeader(
                title = "Profile",
                description = "Your profile is synced with the server"
            )
        }

        if (isStale) {
            item {
                StatusBanner(
                    "Offline — showing the profile saved on this device",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
        status?.let {
            item {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink3,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }

        item {
            ProfileHeaderCard(
                user = repository.loggedInUser(),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = "My interests",
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink
                )
                Spacer(Modifier.height(8.dp))
                // Interests are editable and persisted to the server.
                FlowRowSimple(INTEREST_OPTIONS) { tag ->
                    HubFilterChip(
                        label = tag,
                        selected = interests.contains(tag),
                        onClick = { toggleInterest(tag) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Bookmarked events (${bookmarks.size})",
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            HubDivider()
        }

        if (bookmarks.isEmpty()) {
            item {
                Text(
                    text = "No bookmarks yet — open an event and tap Save.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink2,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            items(bookmarks, key = { "bm-$it" }) { id ->
                eventFor(bookmarks, id)?.let { e ->
                    EventRow(event = e, onClick = { onEventClick(e.id) })
                }
            }
        }

        item {
            Spacer(Modifier.height(24.dp))
            SecondaryButton(
                text = "Sign out",
                danger = true,
                onClick = {
                    repository.logout()
                    scope.launch {
                        SessionStore.clear(context)
                        onSignOut()
                    }
                },
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .testTag("sign-out")
            )
        }
    }
}

/** Resolves a bookmarked id to its cached event, falling back to the embedded catalogue. */
private fun eventFor(bookmarks: List<String>, id: String): Event? = OfflineStore.event(id)

/** Small wrapping row helper — avoids depending on an experimental FlowRow API. */
@Composable
private fun FlowRowSimple(
    options: List<String>,
    content: @Composable (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { item ->
                    Box(Modifier.weight(1f)) { content(item) }
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) {} }
            }
        }
    }
}

@Composable
private fun ProfileHeaderCard(user: UserProfile?, modifier: Modifier = Modifier) {
    HubCard(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InitialsAvatar(user?.name, size = 56.dp)

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user?.name ?: "Guest",
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink
                )
                Text(
                    text = user?.email ?: "No email",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink3
                )
                Spacer(Modifier.height(6.dp))
                HubChip("Synced with server", ChipKind.Success)
            }
        }
    }
}
