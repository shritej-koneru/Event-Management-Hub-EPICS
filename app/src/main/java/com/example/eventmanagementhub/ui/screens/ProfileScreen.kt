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

@OptIn(ExperimentalLayoutApi::class)
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
    // Last value known to be on the server; Save is offered only when [interests] differs.
    var savedInterests by remember { mutableStateOf(interests) }
    var savingInterests by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
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
        val fresh = repository.loggedInUser()?.interests.orEmpty()
        // Do not clobber unsaved edits when the profile is refreshed.
        if (interests == savedInterests) interests = fresh
        savedInterests = fresh
    }

    // Pull the authoritative bookmark list and profile from the server.
    LaunchedEffect(Unit) { load() }

    fun toggleInterest(tag: String) {
        interests = if (interests.contains(tag)) interests - tag else interests + tag
    }

    fun saveInterests() {
        val next = interests
        scope.launch {
            savingInterests = true
            when (val res = repository.updateInterests(next)) {
                is Result.Success -> { savedInterests = next; status = "Interests saved" }
                is Result.Error -> status = res.message
            }
            savingInterests = false
        }
    }

    val changed = interests.toSet() != savedInterests.toSet()

    HubPullRefresh(
        refreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; load(); refreshing = false } },
        modifier = Modifier.fillMaxSize().background(Paper)
    ) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            PageHeader(
                title = "Profile",
                description = "Your account, interests and saved events"
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
                offline = isStale,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "My interests",
                        style = MaterialTheme.typography.titleMedium,
                        color = Ink,
                        modifier = Modifier.weight(1f)
                    )
                    if (changed) {
                        QuietButton(
                            text = if (savingInterests) "Saving…" else "Save",
                            onClick = { saveInterests() },
                            enabled = !savingInterests,
                            modifier = Modifier.testTag("save-interests")
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    INTEREST_OPTIONS.forEach { tag ->
                        HubFilterChip(
                            label = tag,
                            selected = interests.contains(tag),
                            onClick = { toggleInterest(tag) }
                        )
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            SectionTitle("Saved events (${bookmarks.size})")
        }

        if (bookmarks.isEmpty()) {
            item {
                EmptyHint("No saved events yet.", actionLabel = null)
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
            TextButton(
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
                    .heightIn(min = 48.dp)
                    .testTag("sign-out"),
                shape = ControlShape,
                colors = ButtonDefaults.textButtonColors(contentColor = Danger)
            ) { Text("Sign out", style = MaterialTheme.typography.labelLarge) }
        }
    }
    }
}

/** Resolves a bookmarked id to its cached event, falling back to the embedded catalogue. */
private fun eventFor(bookmarks: List<String>, id: String): Event? = OfflineStore.event(id)

@Composable
private fun ProfileHeaderCard(user: UserProfile?, offline: Boolean, modifier: Modifier = Modifier) {
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
                HubChip(if (offline) "Offline" else "Synced", if (offline) ChipKind.Warning else ChipKind.Success)
            }
        }
    }
}
