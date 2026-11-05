package com.example.eventmanagementhub.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.eventmanagementhub.data.HubRepository
import com.example.eventmanagementhub.data.LiveUpdates
import com.example.eventmanagementhub.data.Result
import com.example.eventmanagementhub.data.model.Announcement
import com.example.eventmanagementhub.data.model.Event
import com.example.eventmanagementhub.data.model.SupportTicket
import com.example.eventmanagementhub.ui.theme.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

private val Tint = Color(0xFFEDE9FF)

// ---------------------------------------------------------------------------------
// Shared bits
// ---------------------------------------------------------------------------------

@Composable
private fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    refreshing: Boolean = false
) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
        Column(Modifier.weight(1f).padding(start = if (onBack == null) 16.dp else 0.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MutedText, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        if (onRefresh != null) {
            if (refreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(12.dp).size(24.dp).semantics { contentDescription = "Refreshing" },
                    strokeWidth = 2.dp, color = PrimaryPurple
                )
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content
    )
}

@Composable
private fun ErrorWithRetry(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ErrorBanner(message)
        OutlinedButton(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun StatusChip(status: String) {
    val closed = status.equals("closed", true)
    Surface(
        shape = RoundedCornerShape(50),
        color = if (closed) Color(0xFFF0F0F0) else Color(0xFFD7F5E6)
    ) {
        Text(
            SupportLogic.statusLabel(status),
            style = MaterialTheme.typography.labelSmall,
            color = if (closed) MutedText else Color(0xFF14724A),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
        )
    }
}

// ---------------------------------------------------------------------------------
// Screen A: registered events
// ---------------------------------------------------------------------------------

@Composable
fun AnnouncementsEventsScreen(
    repository: HubRepository = HubRepository(),
    onEventClick: (String) -> Unit
) {
    var events by remember { mutableStateOf<List<Event>?>(null) }
    var stale by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        loading = true
        val ids = repository.bookmarks()
        val idList = when (ids) {
            is Result.Success -> ids.data
            is Result.Error -> repository.registeredIds()
        }
        stale = (ids as? Result.Success)?.isStale == true || ids is Result.Error
        when (val all = repository.events()) {
            is Result.Success -> {
                stale = stale || all.isStale
                events = SupportLogic.registeredEvents(all.data, idList)
            }
            is Result.Error -> if (events == null) events = emptyList()
        }
        loading = false
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().background(BodyBackground)) {
        ScreenHeader(
            "Announcements",
            "Pick an event you registered for",
            onRefresh = { scope.launch { load() } },
            refreshing = loading && events != null
        )
        if (stale) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                StatusBanner("Offline — showing your saved registered events")
            }
        }
        val list = events
        when {
            list == null -> Centered { CircularProgressIndicator(color = PrimaryPurple) }
            list.isEmpty() -> Centered {
                Text("No registered events yet", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = NavyText)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Register for an event from the Events tab and its announcements and help chats will show up here.",
                    style = MaterialTheme.typography.bodyMedium, color = MutedText
                )
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(list, key = { it.id }) { e ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onEventClick(e.id) },
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, CardBorder)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(e.title, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold, color = NavyText)
                            Spacer(Modifier.height(4.dp))
                            Text("${e.date} · ${e.time}", style = MaterialTheme.typography.bodySmall, color = MutedText)
                            Text(e.venue, style = MaterialTheme.typography.bodySmall, color = MutedText)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------
// Screen B: one event's announcements (read-only) + Help button
// ---------------------------------------------------------------------------------

@Composable
fun EventAnnouncementsScreen(
    eventId: String,
    repository: HubRepository = HubRepository(),
    onBack: () -> Unit,
    onHelp: () -> Unit
) {
    var items by remember { mutableStateOf<List<Announcement>?>(null) }
    var title by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        loading = true
        when (val res = repository.eventAnnouncements(eventId)) {
            is Result.Success -> { items = SupportLogic.sortAnnouncements(res.data); error = null }
            is Result.Error -> error = res.message
        }
        loading = false
    }
    LaunchedEffect(eventId) {
        load()
        (repository.event(eventId) as? Result.Success)?.data?.let { title = it.title }
    }
    // Live: this event's broadcast, a global one, or a reconnect -> refetch.
    LaunchedEffect(eventId) {
        LiveUpdates.signals.collect { if (SupportLogic.shouldRefreshAnnouncements(it, eventId)) load() }
    }

    Column(Modifier.fillMaxSize().background(BodyBackground)) {
        ScreenHeader(
            title ?: "Announcements", "Announcements",
            onBack = onBack, onRefresh = { scope.launch { load() } }, refreshing = loading && items != null
        )
        Box(Modifier.weight(1f)) {
            val list = items
            when {
                list == null && error != null -> ErrorWithRetry(error!!) { scope.launch { load() } }
                list == null -> Centered { CircularProgressIndicator(color = PrimaryPurple) }
                list.isEmpty() -> Centered {
                    Text("No announcements yet", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, color = NavyText)
                    Spacer(Modifier.height(8.dp))
                    Text("Organizers' updates for this event will appear here.",
                        style = MaterialTheme.typography.bodyMedium, color = MutedText)
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (error != null) item { ErrorBanner(error!!) }
                    items(list, key = { "${it.id}-${it.message.hashCode()}" }) { a -> AnnouncementCard(a) }
                }
            }
        }
        Button(
            onClick = onHelp,
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple, contentColor = Color.White)
        ) {
            Text("Help", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AnnouncementCard(a: Announcement) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (SupportLogic.isForAll(a)) {
                    Surface(shape = RoundedCornerShape(50), color = Tint) {
                        Text("All attendees", style = MaterialTheme.typography.labelSmall, color = PrimaryPurple,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Text(SupportLogic.formatTime(a.at), style = MaterialTheme.typography.bodySmall, color = MutedText)
            }
            Spacer(Modifier.height(6.dp))
            Text(a.message.ifBlank { a.room }, style = MaterialTheme.typography.bodyMedium, color = NavyText)
            if (!a.sentByName.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("— ${a.sentByName}", style = MaterialTheme.typography.bodySmall, color = MutedText)
            }
        }
    }
}

// ---------------------------------------------------------------------------------
// Screen C: help chats for the event
// ---------------------------------------------------------------------------------

@Composable
fun HelpChatsScreen(
    eventId: String,
    repository: HubRepository = HubRepository(),
    onBack: () -> Unit,
    onOpenTicket: (String) -> Unit,
    onNewChat: () -> Unit
) {
    var tickets by remember { mutableStateOf<List<SupportTicket>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        loading = true
        when (val res = repository.supportTickets(eventId)) {
            is Result.Success -> { tickets = SupportLogic.sortTickets(res.data); error = null }
            is Result.Error -> error = res.message
        }
        loading = false
    }
    LaunchedEffect(eventId) { load() }
    LaunchedEffect(eventId) {
        LiveUpdates.signals.collect { if (SupportLogic.shouldRefreshTickets(it, eventId)) load() }
    }

    Box(Modifier.fillMaxSize().background(BodyBackground)) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                "Help", "Your chats with the event team",
                onBack = onBack, onRefresh = { scope.launch { load() } }, refreshing = loading && tickets != null
            )
            val list = tickets
            when {
                list == null && error != null -> ErrorWithRetry(error!!) { scope.launch { load() } }
                list == null -> Centered { CircularProgressIndicator(color = PrimaryPurple) }
                list.isEmpty() -> Centered {
                    Text("No help chats yet", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, color = NavyText)
                    Spacer(Modifier.height(8.dp))
                    Text("Tap + to start a chat with the event team.",
                        style = MaterialTheme.typography.bodyMedium, color = MutedText)
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (error != null) item { ErrorBanner(error!!) }
                    items(list, key = { it.id }) { t -> TicketRow(t) { onOpenTicket(t.id) } }
                }
            }
        }
        FloatingActionButton(
            onClick = onNewChat,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = PrimaryPurple,
            contentColor = Color.White
        ) {
            Icon(Icons.Default.Add, contentDescription = "Start a new help chat")
        }
    }
}

@Composable
private fun TicketRow(t: SupportTicket, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.subject, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    color = NavyText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                StatusChip(t.status)
            }
            Spacer(Modifier.height(4.dp))
            Text(SupportLogic.preview(t), style = MaterialTheme.typography.bodyMedium, color = MutedText,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(SupportLogic.formatTime(t.lastMessageAt), style = MaterialTheme.typography.bodySmall, color = MutedText)
        }
    }
}

// ---------------------------------------------------------------------------------
// Screen D: new chat
// ---------------------------------------------------------------------------------

@Composable
fun NewChatScreen(
    eventId: String,
    repository: HubRepository = HubRepository(),
    onBack: () -> Unit,
    onCreated: (String) -> Unit
) {
    var subject by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // One clientId per form so a retried submit cannot create two chats.
    val clientId = remember { UUID.randomUUID().toString() }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(BodyBackground)) {
        ScreenHeader("New help chat", "Tell the event team what you need", onBack = onBack)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (error != null) ErrorBanner(error!!)
            OutlinedTextField(
                value = subject,
                onValueChange = { subject = it.take(120) },
                label = { Text("Subject") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !sending
            )
            OutlinedTextField(
                value = message,
                onValueChange = { message = it.take(1000) },
                label = { Text("Your message") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
                enabled = !sending
            )
            Button(
                onClick = {
                    sending = true
                    error = null
                    scope.launch {
                        when (val res = repository.createTicket(eventId, subject, message, clientId)) {
                            is Result.Success -> onCreated(res.data.ticket!!.id)
                            is Result.Error -> { error = res.message; sending = false }
                        }
                    }
                },
                enabled = !sending && SupportLogic.isValidNewChat(subject, message),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple, contentColor = Color.White)
            ) {
                if (sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                else Text("Start chat", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ---------------------------------------------------------------------------------
// Screen E: chat thread
// ---------------------------------------------------------------------------------

@Composable
fun ChatThreadScreen(
    ticketId: String,
    repository: HubRepository = HubRepository(),
    onBack: () -> Unit
) {
    var ticket by remember { mutableStateOf<SupportTicket?>(null) }
    var thread by remember { mutableStateOf<List<ThreadItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    suspend fun load() {
        when (val res = repository.ticketMessages(ticketId)) {
            is Result.Success -> {
                ticket = res.data.ticket ?: ticket
                thread = SupportLogic.mergeThread(thread, res.data.messages)
                loaded = true
                error = null
            }
            is Result.Error -> error = res.message
        }
    }

    fun deliver(clientId: String, body: String) {
        scope.launch {
            when (val res = repository.sendTicketMessage(ticketId, body, clientId)) {
                is Result.Success -> {
                    thread = SupportLogic.acknowledge(thread, clientId, res.data)
                    // A message to a closed chat reopens it; pick up the new status.
                    load()
                }
                is Result.Error -> {
                    thread = SupportLogic.markFailed(thread, clientId)
                    error = res.message
                }
            }
        }
    }

    fun send() {
        val body = draft.trim()
        if (body.isEmpty()) return
        val clientId = UUID.randomUUID().toString()
        thread = thread + SupportLogic.optimistic(clientId, body, Instant.now().toString())
        draft = ""
        error = null
        deliver(clientId, body)
    }

    LaunchedEffect(ticketId) { load() }
    LaunchedEffect(ticketId) {
        LiveUpdates.signals.collect { if (SupportLogic.shouldRefreshThread(it, ticketId)) load() }
    }
    LaunchedEffect(thread.size) {
        if (thread.isNotEmpty()) listState.animateScrollToItem(thread.lastIndex)
    }

    Column(Modifier.fillMaxSize().background(BodyBackground)) {
        ScreenHeader(
            ticket?.subject ?: "Help chat",
            ticket?.let { "${SupportLogic.statusLabel(it.status)} · ${it.eventTitle}" },
            onBack = onBack,
            onRefresh = { scope.launch { load() } }
        )
        if (error != null) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { ErrorBanner(error!!) }
        }
        Box(Modifier.weight(1f)) {
            if (!loaded && thread.isEmpty()) {
                if (error == null) Centered { CircularProgressIndicator(color = PrimaryPurple) }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(thread, key = { it.clientId ?: it.id }) { item ->
                        Bubble(item) {
                            item.clientId?.let { cid ->
                                thread = SupportLogic.markPending(thread, cid)
                                error = null
                                deliver(cid, item.body)
                            }
                        }
                    }
                }
            }
        }
        if (ticket?.status.equals("closed", true)) {
            Text(
                "This chat is closed. Sending a message reopens it.",
                style = MaterialTheme.typography.bodySmall, color = MutedText,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(1000) },
                placeholder = { Text("Type a message…", color = MutedText) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryPurple,
                    unfocusedBorderColor = CardBorder,
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White
                )
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = ::send,
                enabled = draft.isNotBlank(),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = PrimaryPurple)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send message")
            }
        }
    }
}

@Composable
private fun Bubble(item: ThreadItem, onRetry: () -> Unit) {
    val mine = !item.fromTeam
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (mine) PrimaryPurple else Color(0xFFF1EEFE),
            modifier = Modifier.widthIn(max = 300.dp).then(
                if (item.state == SendState.FAILED) Modifier.clickable(onClick = onRetry) else Modifier
            )
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (!mine) {
                    Text("Event team", style = MaterialTheme.typography.bodySmall, color = PrimaryPurple,
                        fontWeight = FontWeight.Bold)
                }
                Text(item.body, style = MaterialTheme.typography.bodyMedium,
                    color = if (mine) Color.White else Color(0xFF3A3850))
                val meta = when (item.state) {
                    SendState.PENDING -> "Sending…"
                    SendState.FAILED -> "Not sent — tap to retry"
                    SendState.SENT -> SupportLogic.formatTime(item.createdAt)
                }
                if (meta.isNotEmpty()) {
                    Text(meta, style = MaterialTheme.typography.labelSmall,
                        color = if (mine) Color(0xFFD9D2FF) else MutedText)
                }
            }
        }
    }
}
