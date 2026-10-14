package com.example.eventmanagementhub.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.eventmanagementhub.data.HubRepository
import com.example.eventmanagementhub.data.OfflineStore
import com.example.eventmanagementhub.data.Result
import com.example.eventmanagementhub.data.SessionStore
import com.example.eventmanagementhub.data.model.Event
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
            .background(BodyBackground)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp)
    ) {
        item {
            Text(
                text = "My Profile",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.Black,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Your profile is synced with the server",
                style = MaterialTheme.typography.bodyMedium,
                color = MutedText
            )
        }

        if (isStale) {
            item { StatusBanner("Offline — showing the profile saved on this device") }
        }
        status?.let {
            item { Text(it, style = MaterialTheme.typography.bodySmall, color = MutedText) }
        }

        item {
            ProfileHeaderCard(user = repository.loggedInUser())
        }

        item {
            Text(
                text = "My interests",
                style = MaterialTheme.typography.titleMedium,
                color = NavyText,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            // Interests are now editable and persisted to the server.
            FlowRowSimple(INTEREST_OPTIONS) { tag ->
                FilterChip(
                    selected = interests.contains(tag),
                    onClick = { toggleInterest(tag) },
                    label = { Text(tag) }
                )
            }
        }

        item {
            Text(
                text = "Bookmarked events (${bookmarks.size})",
                style = MaterialTheme.typography.titleMedium,
                color = NavyText,
                fontWeight = FontWeight.Bold
            )
        }

        if (bookmarks.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, CardBorder)
                ) {
                    Text(
                        text = "No bookmarks yet — open an event and tap Save.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedText,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(bookmarks, key = { "bm-$it" }) { id ->
                eventFor(bookmarks, id)?.let { e ->
                    EventListCard(event = e, onClick = { onEventClick(e.id) })
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    repository.logout()
                    scope.launch {
                        SessionStore.clear(context)
                        onSignOut()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("sign-out"),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0xFFE04A5A)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE04A5A))
            ) {
                Text("Sign out", fontWeight = FontWeight.Bold)
            }
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
private fun ProfileHeaderCard(user: com.example.eventmanagementhub.data.model.UserProfile?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(EventCardWhiteGradient)
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(IconPurpleGradient),
                contentAlignment = Alignment.Center
            ) {
                val initials = user?.name
                    ?.split(" ")
                    ?.filter { it.isNotBlank() }
                    ?.take(2)
                    ?.map { it.first().uppercase() }
                    ?.joinToString("")
                    ?: ""
                Text(
                    text = if (initials.isNotBlank()) initials else "U",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user?.name ?: "Guest",
                    style = MaterialTheme.typography.titleLarge,
                    color = NavyText,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = user?.email ?: "No email",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedText
                )
                Surface(shape = RoundedCornerShape(50), color = Color(0xFFE9F7EF)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDone,
                            contentDescription = null,
                            tint = Color(0xFF2F9E63),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Synced with server",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF2F9E63)
                        )
                    }
                }
            }
        }
    }
}