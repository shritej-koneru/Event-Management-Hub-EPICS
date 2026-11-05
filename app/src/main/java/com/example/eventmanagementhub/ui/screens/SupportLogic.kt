package com.example.eventmanagementhub.ui.screens

import com.example.eventmanagementhub.data.LiveSignal
import com.example.eventmanagementhub.data.model.Announcement
import com.example.eventmanagementhub.data.model.Event
import com.example.eventmanagementhub.data.model.SupportMessage
import com.example.eventmanagementhub.data.model.SupportTicket
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class SendState { SENT, PENDING, FAILED }

/** One bubble in a help-chat thread. [clientId] ties an optimistic bubble to the server row. */
data class ThreadItem(
    val id: String,
    val clientId: String?,
    val body: String,
    /** True for "Event team" (manager) messages, false for the attendee's own. */
    val fromTeam: Boolean,
    val createdAt: String,
    val state: SendState = SendState.SENT
)

/** Pure rules behind the Announcements / Help screens (no Android types, unit tested). */
object SupportLogic {

    fun parseMillis(iso: String?): Long? =
        iso?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun numericId(id: String): Long = id.toLongOrNull() ?: Long.MIN_VALUE

    /** Newest first; duplicate ids collapse; ties and unparseable times fall back to id. */
    fun sortAnnouncements(list: List<Announcement>): List<Announcement> =
        list.distinctBy { it.id to it.message }
            .sortedWith(
                compareByDescending<Announcement> { parseMillis(it.at) ?: Long.MIN_VALUE }
                    .thenByDescending { it.id }
            )

    /** An announcement addressed to everyone (shown with the "All attendees" tag). */
    fun isForAll(a: Announcement): Boolean = a.global || a.eventId.isNullOrBlank()

    /** Newest activity first. */
    fun sortTickets(list: List<SupportTicket>): List<SupportTicket> =
        list.sortedWith(
            compareByDescending<SupportTicket> {
                parseMillis(it.lastMessageAt) ?: parseMillis(it.createdAt) ?: Long.MIN_VALUE
            }.thenByDescending { numericId(it.id) }
        )

    /** Registered events (bookmarked ids that exist in [all]), soonest first. */
    fun registeredEvents(all: List<Event>, ids: List<String>): List<Event> {
        val wanted = ids.toSet()
        return all.filter { it.id in wanted }.distinctBy { it.id }
            .sortedWith(compareBy<Event>({ it.date }, { it.time }, { it.title }))
    }

    // ---- thread merge ---------------------------------------------------------------

    fun fromServer(m: SupportMessage): ThreadItem =
        ThreadItem(m.id, m.clientId, m.body, fromTeam = m.senderRole == "manager", createdAt = m.createdAt)

    fun optimistic(clientId: String, body: String, nowIso: String): ThreadItem =
        ThreadItem("local-$clientId", clientId, body, fromTeam = false, createdAt = nowIso, state = SendState.PENDING)

    /**
     * Merges a server snapshot into the current thread. Server rows win; an optimistic
     * bubble disappears once the server row with the same clientId (or the same id)
     * arrives, so a send acknowledged twice (REST reply + refetch) never shows twice.
     * Optimistic bubbles the server has not seen yet stay at the end.
     */
    fun mergeThread(existing: List<ThreadItem>, server: List<SupportMessage>): List<ThreadItem> {
        val serverItems = server.map(::fromServer)
            .distinctBy { it.id }
            .sortedWith(compareBy<ThreadItem>({ parseMillis(it.createdAt) ?: Long.MAX_VALUE }, { numericId(it.id) }))
        val serverClientIds = serverItems.mapNotNull { it.clientId }.toSet()
        val serverIds = serverItems.map { it.id }.toSet()
        val leftovers = existing.filter {
            it.state != SendState.SENT && it.clientId !in serverClientIds && it.id !in serverIds
        }
        return serverItems + leftovers
    }

    fun markFailed(items: List<ThreadItem>, clientId: String): List<ThreadItem> =
        items.map { if (it.clientId == clientId && it.state == SendState.PENDING) it.copy(state = SendState.FAILED) else it }

    fun markPending(items: List<ThreadItem>, clientId: String): List<ThreadItem> =
        items.map { if (it.clientId == clientId && it.state == SendState.FAILED) it.copy(state = SendState.PENDING) else it }

    /** Replaces the optimistic bubble with the server's acknowledged row (matched by clientId). */
    fun acknowledge(items: List<ThreadItem>, clientId: String, server: SupportMessage): List<ThreadItem> {
        val ack = fromServer(server).copy(clientId = server.clientId ?: clientId)
        val rest = items.filterNot { it.clientId == clientId || it.id == ack.id }
        val sent = (rest.filter { it.state == SendState.SENT } + ack)
            .sortedWith(compareBy<ThreadItem>({ parseMillis(it.createdAt) ?: Long.MAX_VALUE }, { numericId(it.id) }))
        return sent + rest.filter { it.state != SendState.SENT }
    }

    // ---- live signal routing ---------------------------------------------------------

    /** Announcement screens refetch for this event's broadcasts, global ones, and after a reconnect. */
    fun shouldRefreshAnnouncements(signal: LiveSignal, eventId: String): Boolean = when (signal.kind) {
        LiveSignal.Kind.ANNOUNCEMENT -> signal.eventId == null || signal.eventId == eventId
        LiveSignal.Kind.RECONNECTED -> true
        else -> false
    }

    /** The ticket list for [eventId] refetches on any support signal that may concern it. */
    fun shouldRefreshTickets(signal: LiveSignal, eventId: String): Boolean = when (signal.kind) {
        LiveSignal.Kind.SUPPORT_MESSAGE, LiveSignal.Kind.SUPPORT_TICKET ->
            signal.eventId == null || signal.eventId == eventId
        LiveSignal.Kind.RECONNECTED -> true
        else -> false
    }

    /** The open thread refetches when a message/ticket update names it (or does not name any). */
    fun shouldRefreshThread(signal: LiveSignal, ticketId: String): Boolean = when (signal.kind) {
        LiveSignal.Kind.SUPPORT_MESSAGE, LiveSignal.Kind.SUPPORT_TICKET ->
            signal.ticketId == null || signal.ticketId == ticketId
        LiveSignal.Kind.RECONNECTED -> true
        else -> false
    }

    // ---- display helpers -----------------------------------------------------------------

    fun statusLabel(status: String): String = if (status.equals("closed", true)) "Closed" else "Open"

    fun isValidNewChat(subject: String, message: String): Boolean =
        subject.trim().length in 1..120 && message.trim().length in 1..1000

    fun formatTime(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String {
        val ms = parseMillis(iso) ?: return ""
        return DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.getDefault())
            .withZone(zone).format(Instant.ofEpochMilli(ms))
    }

    fun preview(ticket: SupportTicket): String {
        val last = ticket.lastMessage ?: return "No messages yet"
        val prefix = if (last.senderRole == "manager") "Event team: " else "You: "
        return prefix + last.body.replace('\n', ' ')
    }
}
