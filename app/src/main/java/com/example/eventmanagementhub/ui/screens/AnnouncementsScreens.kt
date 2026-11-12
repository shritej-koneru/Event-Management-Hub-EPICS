package com.example.eventmanagementhub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.eventmanagementhub.data.HubRepository
import com.example.eventmanagementhub.data.LiveUpdates
import com.example.eventmanagementhub.data.Result
import com.example.eventmanagementhub.data.model.Announcement
import com.example.eventmanagementhub.data.model.Event
import com.example.eventmanagementhub.data.model.SupportTicket
import com.example.eventmanagementhub.ui.components.*
import com.example.eventmanagementhub.ui.theme.*
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextAlign
import java.time.Instant
import java.util.UUID

// ---------------------------------------------------------------------------------
// Shared bits
// ---------------------------------------------------------------------------------

/** Flat top bar with an optional Back and a Refresh action (spinner while refreshing). */
@Composable
private fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    refreshing: Boolean = false
) {
    HubTopBar(
        title = title,
        subtitle = subtitle,
        onBack = onBack,
        actions = if (onRefresh == null) null else {
            {
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(12.dp)
                            .size(24.dp)
                            .semantics { contentDescription = "Refreshing" },
                        strokeWidth = 2.dp, color = Accent
                    )
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Ink2)
                    }
                }
            }
        }
    )
}

/** Empty / loading state: one line of Ink2 (plus an optional quiet action). No illustrations. */
@Composable
private fun Centered(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content
    )
}

@Composable
private fun EmptyState(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Centered {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Ink2, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) QuietButton(actionLabel, onAction)
    }
}

/** List-screen loading placeholder (static blocks, no shimmer). */
@Composable
private fun ListSkeleton() = SkeletonRows(rows = 6)

@Composable
private fun ErrorWithRetry(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ErrorBanner(message)
        SecondaryButton("Try again", onRetry)
    }
}

@Composable
private fun StatusChip(status: String) {
    val closed = status.equals("closed", true)
    HubChip(SupportLogic.statusLabel(status), if (closed) ChipKind.Neutral else ChipKind.Success)
}

// ---------------------------------------------------------------------------------
// Screen A: registered events
// ---------------------------------------------------------------------------------

@Composable
fun AnnouncementsEventsScreen(
    repository: HubRepository = HubRepository(),
    onEventClick: (String) -> Unit,
    onBrowseEvents: (() -> Unit)? = null,
    /** Opens nearby chat (offline, over Bluetooth). */
    onOpenNearby: (() -> Unit)? = null
) {
    var events by remember { mutableStateOf<List<Event>?>(null) }
    var latest by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pulling by remember { mutableStateOf(false) }
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
        // One extra call for the "latest update" line; absence just hides the line.
        (repository.announcements() as? Result.Success)?.let {
            latest = EventLogic.latestAnnouncementByEvent(it.data.announcements)
        }
        loading = false
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().background(Paper)) {
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
        // Always reachable, including when there are no saved events and when the phone is offline.
        if (onOpenNearby != null) NearbyEntryRow(onOpenNearby)
        val list = events
        when {
            list == null -> ListSkeleton()
            else -> HubPullRefresh(
                refreshing = pulling,
                onRefresh = { scope.launch { pulling = true; load(); pulling = false } },
                modifier = Modifier.weight(1f)
            ) {
                if (list.isEmpty()) {
                    EmptyState(
                        "No saved events yet. Save an event to see its announcements and help chats here.",
                        "Browse events".takeIf { onBrowseEvents != null }, onBrowseEvents
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        item { HubDivider() }
                        items(list, key = { it.id }) { e ->
                            val now = Instant.now()
                            EventRow(
                                event = e,
                                extra = latest[e.id]?.let { EventLogic.relativeTime(it, now) }
                                    ?.takeIf { it.isNotBlank() }?.let { "Latest update $it" },
                                onClick = { onEventClick(e.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Row that opens nearby chat: messaging over Bluetooth that needs no internet. */
@Composable
internal fun NearbyEntryRow(onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        SecondaryButton(
            text = "Nearby chat (offline)",
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth().testTag("open-nearby-chat")
        )
        MetaText("Message phones around you over Bluetooth, no internet needed", Modifier.padding(top = 4.dp))
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
    var pulling by remember { mutableStateOf(false) }
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

    Column(Modifier.fillMaxSize().background(Paper)) {
        ScreenHeader(
            title ?: "Announcements", "Announcements",
            onBack = onBack, onRefresh = { scope.launch { load() } }, refreshing = loading && items != null
        )
        Box(Modifier.weight(1f)) {
            val list = items
            when {
                list == null && error != null -> ErrorWithRetry(error!!) { scope.launch { load() } }
                list == null -> ListSkeleton()
                else -> HubPullRefresh(
                    refreshing = pulling,
                    onRefresh = { scope.launch { pulling = true; load(); pulling = false } },
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (list.isEmpty()) {
                        EmptyState("No announcements yet. Updates from the organizers will appear here.", "Refresh") {
                            scope.launch { load() }
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                            if (error != null) item { ErrorBanner(error!!, Modifier.padding(16.dp)) }
                            items(list, key = { "${it.id}-${it.message.hashCode()}" }) { a -> AnnouncementItem(a) }
                        }
                    }
                }
            }
        }
        // Sticky action with a top hairline.
        Column(Modifier.fillMaxWidth().background(HubSurface)) {
            HubDivider()
            PrimaryButton(
                text = "Help",
                onClick = onHelp,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            )
        }
    }
}

/** Announcement entry: roomy text on Surface, separated by hairlines (not a card per item). */
@Composable
private fun AnnouncementItem(a: Announcement) {
    Column(Modifier.fillMaxWidth().background(HubSurface)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (SupportLogic.isForAll(a)) {
                    HubChip("All attendees", ChipKind.Info)
                    Spacer(Modifier.width(8.dp))
                }
                Text(SupportLogic.formatTime(a.at), style = MaterialTheme.typography.labelSmall, color = Ink3)
            }
            Spacer(Modifier.height(6.dp))
            Text(a.message.ifBlank { a.room }, style = MaterialTheme.typography.bodyMedium, color = Ink)
            if (a.message.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                AiAnnouncementTools(a.id) // hidden unless the server has AI on; the original above is never replaced
            }
            if (!a.sentByName.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("— ${a.sentByName}", style = MaterialTheme.typography.bodySmall, color = Ink3)
            }
        }
        HubDivider()
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
    var pulling by remember { mutableStateOf(false) }
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

    Box(Modifier.fillMaxSize().background(Paper)) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                "Help", "Your chats with the event team",
                onBack = onBack, onRefresh = { scope.launch { load() } }, refreshing = loading && tickets != null
            )
            val list = tickets
            when {
                list == null && error != null -> ErrorWithRetry(error!!) { scope.launch { load() } }
                list == null -> ListSkeleton()
                else -> HubPullRefresh(
                    refreshing = pulling,
                    onRefresh = { scope.launch { pulling = true; load(); pulling = false } },
                    modifier = Modifier.weight(1f)
                ) {
                    if (list.isEmpty()) {
                        EmptyState("No help chats yet.", "Start a chat", onNewChat)
                    } else {
                        val now = Instant.now()
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                            if (error != null) item { ErrorBanner(error!!, Modifier.padding(16.dp)) }
                            items(list, key = { it.id }) { t -> TicketRow(t, now) { onOpenTicket(t.id) } }
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = onNewChat,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            shape = ControlShape,
            containerColor = Accent,
            contentColor = OnAccent,
            elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Start a new help chat")
        }
    }
}

@Composable
private fun TicketRow(t: SupportTicket, now: Instant, onClick: () -> Unit) {
    val unread = ChatReadState.isUnread(t)
    val whenText = EventLogic.relativeTime(t.lastMessageAt.ifBlank { t.createdAt }, now)
    HubListRow(
        title = t.subject,
        onClick = onClick,
        leading = { InitialsAvatar(t.subject) },
        meta = {
            Text(
                SupportLogic.preview(t),
                style = MaterialTheme.typography.bodySmall, color = if (unread) Ink else Ink2, maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            MetaText(whenText)
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (unread) {
                    UnreadDot()
                    Spacer(Modifier.width(8.dp))
                }
                StatusChip(t.status)
            }
        }
    )
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

    Column(Modifier.fillMaxSize().background(Paper)) {
        ScreenHeader("New help chat", "Tell the event team what you need", onBack = onBack)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (error != null) ErrorBanner(error!!)
            LabeledField("Subject") {
                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it.take(120) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Subject" },
                    shape = ControlShape,
                    colors = hubFieldColors(),
                    enabled = !sending
                )
            }
            LabeledField("Your message") {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it.take(1000) },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Your message" },
                    shape = ControlShape,
                    colors = hubFieldColors(),
                    enabled = !sending
                )
            }
            AiRewriteSuggestion(text = message, kind = "message", onUse = { message = it.take(1000) })
            PrimaryButton(
                text = "Start chat",
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
                enabled = SupportLogic.isValidNewChat(subject, message),
                loading = sending,
                modifier = Modifier.fillMaxWidth()
            )
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
                ChatReadState.markViewed(ticketId)
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
    // Leaving the thread counts as having seen everything in it (clears the list unread dot).
    DisposableEffect(ticketId) { onDispose { ChatReadState.markViewed(ticketId) } }
    LaunchedEffect(thread.size) {
        if (thread.isNotEmpty()) listState.animateScrollToItem(thread.lastIndex)
    }

    Column(Modifier.fillMaxSize().background(Paper)) {
        ScreenHeader(
            ticket?.subject ?: "Help chat",
            ticket?.let { "${SupportLogic.statusLabel(it.status)} · ${it.eventTitle}" },
            onBack = onBack,
            onRefresh = { scope.launch { load() } }
        )
        if (error != null) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { ErrorBanner(error!!) }
        }
        Box(Modifier.weight(1f)) {
            if (!loaded && thread.isEmpty()) {
                if (error == null) ListSkeleton()
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(thread, key = { _, it -> it.clientId ?: it.id }) { index, item ->
                        Column {
                            if (EventLogic.startsNewDay(thread.getOrNull(index - 1)?.createdAt, item.createdAt)) {
                                DaySeparator(EventLogic.separatorLabel(item.createdAt, Instant.now()).orEmpty())
                            }
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
        }
        // Sticky composer with a top hairline.
        Column(Modifier.fillMaxWidth().background(HubSurface).imePadding()) {
            HubDivider()
            if (ticket?.status.equals("closed", true)) {
                Text(
                    "This chat is closed. Sending a message reopens it.",
                    style = MaterialTheme.typography.bodySmall, color = Ink3,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
                AiRewriteSuggestion(text = draft, kind = "message", onUse = { draft = it.take(1000) })
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(1000) },
                    placeholder = { Text("Type a message…", color = Ink3) },
                    modifier = Modifier.weight(1f),
                    shape = ControlShape,
                    maxLines = 4,
                    colors = hubFieldColors()
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = ::send,
                    enabled = draft.isNotBlank(),
                    modifier = Modifier.size(56.dp),
                    shape = ControlShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Accent, contentColor = OnAccent,
                        disabledContainerColor = SurfaceSunken, disabledContentColor = Ink3
                    )
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send message")
                }
            }
        }
    }
}

/**
 * Chat bubble: others = Surface + Line border, own = AccentSoft + Ink. 8dp radius with a 2dp
 * tail corner (bottom-end for own, bottom-start for others). Sender + time sit above as Caption.
 */
@Composable
private fun Bubble(item: ThreadItem, onRetry: () -> Unit) {
    val mine = !item.fromTeam
    val bubbleShape = RoundedCornerShape(
        topStart = 8.dp, topEnd = 8.dp,
        bottomStart = if (mine) 8.dp else 2.dp,
        bottomEnd = if (mine) 2.dp else 8.dp
    )
    val meta = when (item.state) {
        SendState.PENDING -> "Sending…"
        SendState.FAILED -> "Not sent — tap to retry"
        SendState.SENT -> SupportLogic.formatTime(item.createdAt)
    }
    val sender = if (mine) "You" else "Event team"
    val caption = if (meta.isNotEmpty()) "$sender · $meta" else sender
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start
    ) {
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = if (item.state == SendState.FAILED) Danger else Ink3
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .background(if (mine) AccentSoft else HubSurface, bubbleShape)
                .then(if (mine) Modifier else Modifier.border(1.dp, Line, bubbleShape))
                .then(if (item.state == SendState.FAILED) Modifier.clickable(onClick = onRetry) else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(item.body, style = MaterialTheme.typography.bodyMedium, color = Ink)
        }
    }
}

/** Centered day label between message groups ("Today", "Yesterday", "Sat 9 Nov"). */
@Composable
private fun DaySeparator(label: String) {
    if (label.isBlank()) return
    Box(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentAlignment = Alignment.Center) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Ink3,
            modifier = Modifier
                .background(SurfaceSunken, ControlShape)
                .padding(horizontal = 10.dp, vertical = 3.dp)
        )
    }
}
