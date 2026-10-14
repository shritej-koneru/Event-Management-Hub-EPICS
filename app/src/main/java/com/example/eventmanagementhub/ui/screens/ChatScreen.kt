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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.ComponentActivity
import com.example.eventmanagementhub.data.HubRepository
import com.example.eventmanagementhub.data.model.Announcement
import com.example.eventmanagementhub.ui.theme.*

/**
 * BitChat (Person 2 integration, Wk 3) — history-preserving edition.
 *
 * State lives in [ChatViewModel], which is scoped to the Activity so the thread
 * survives Home/Events/Map tab switches. The ViewModel loads persisted history
 * from GET /api/chat/messages on first start and again after every reconnect;
 * Socket.IO `chat:message` events merge in without duplicates.
 */
@Composable
fun ChatScreen(
    repository: HubRepository = HubRepository()
) {
    val activity = LocalContext.current as ComponentActivity
    val viewModel: ChatViewModel = viewModel(
        viewModelStoreOwner = activity,
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(repository) as T
        }
    )

    val messages by viewModel.messages.collectAsState()
    val announcements by viewModel.announcements.collectAsState()
    val draft by viewModel.draft.collectAsState()
    val connected by viewModel.connected.collectAsState()
    val isStale by viewModel.isStale.collectAsState()
    val isLoadingHistory by viewModel.isLoadingHistory.collectAsState()

    LaunchedEffect(Unit) { viewModel.start() }

    Column(
        Modifier
            .fillMaxSize()
            .background(BodyBackground)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "BitChat",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = RoundedCornerShape(50),
                color = if (connected) Color(0xFF34D399) else Color(0xFFF0F0F0)
            ) {
                Text(
                    if (connected) "Live" else "Offline",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connected) Color.White else MutedText,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Live venue announcements & chat via Socket.io",
            style = MaterialTheme.typography.bodySmall,
            color = MutedText
        )

        Spacer(Modifier.height(16.dp))

        if (isStale && !connected) {
            StatusBanner("Offline — showing saved announcements")
            Spacer(Modifier.height(12.dp))
        }

        if (announcements.isNotEmpty()) {
            Text("Announcements", style = MaterialTheme.typography.titleSmall, color = NavyText, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.heightIn(max = 160.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(announcements.take(5), key = { "${it.id}-${it.message.hashCode()}" }) { a ->
                    Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFFEDE9FF)) {
                        Text(
                            a.message.ifBlank { a.room },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF4A4863),
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Card(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(EventCardWhiteGradient)
                    .padding(12.dp)
            ) {
                if (isLoadingHistory && messages.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = PrimaryPurple, strokeWidth = 2.dp)
                    }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages) { (author, text) -> MessageBubble(author, text) }
                    if (messages.isEmpty() && !isLoadingHistory) {
                        item {
                            Text(
                                "No messages yet. Say hello below — every connected client sees your message the moment you send it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedText,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { viewModel.onDraftChange(it) },
                        placeholder = { Text("Type a message…", color = MutedText, style = MaterialTheme.typography.bodyMedium) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryPurple,
                            unfocusedBorderColor = CardBorder,
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White
                        ),
                        singleLine = true
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { viewModel.sendChat() },
                        enabled = connected && draft.isNotBlank(),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryPurple,
                            contentColor = Color.White,
                            disabledContainerColor = Color(0xFFE3E0F5),
                            disabledContentColor = MutedText
                        )
                    ) {
                        Text("Send", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(author: String, text: String) {
    val mine = author == "You"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(shape = RoundedCornerShape(16.dp), color = if (mine) PrimaryPurple else Color(0xFFF1EEFE)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    author,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (mine) Color(0xFFD9D2FF) else PrimaryPurple,
                    fontWeight = FontWeight.Bold
                )
                Text(text, style = MaterialTheme.typography.bodyMedium, color = if (mine) Color.White else Color(0xFF3A3850))
            }
        }
    }
}
