package com.example.eventmanagementhub.ui.screens

import com.example.eventmanagementhub.data.LiveSignal
import com.example.eventmanagementhub.data.SupportErrors
import com.example.eventmanagementhub.data.model.Announcement
import com.example.eventmanagementhub.data.model.Event
import com.example.eventmanagementhub.data.model.LastMessage
import com.example.eventmanagementhub.data.model.SupportMessage
import com.example.eventmanagementhub.data.model.SupportTicket
import com.example.eventmanagementhub.data.model.TicketMessagesResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportLogicTest {

    private fun ann(id: Long, at: String, global: Boolean = false, eventId: String? = "e1", msg: String = "m$id") =
        Announcement(id = id, message = msg, at = at, eventId = eventId, global = global)

    private fun ticket(id: String, last: String) = SupportTicket(id = id, lastMessageAt = last)

    private fun msg(id: String, at: String, role: String = "user", clientId: String? = null) =
        SupportMessage(id = id, ticketId = "t", senderRole = role, body = "b$id", createdAt = at, clientId = clientId)

    private fun event(id: String, date: String, time: String = "10:00") =
        Event(id = id, title = id, category = "c", venue = "v", venueId = "vid", date = date, time = time)

    // ---- announcements ------------------------------------------------------------

    @Test fun announcementsAreNewestFirst() {
        val sorted = SupportLogic.sortAnnouncements(
            listOf(ann(1, "2026-01-01T10:00:00Z"), ann(3, "2026-01-03T10:00:00Z"), ann(2, "2026-01-02T10:00:00Z"))
        )
        assertEquals(listOf(3L, 2L, 1L), sorted.map { it.id })
    }

    @Test fun announcementTieAndBadTimeFallBackToId() {
        val sorted = SupportLogic.sortAnnouncements(listOf(ann(1, "garbage"), ann(2, "garbage"), ann(5, "2026-01-01T00:00:00Z")))
        assertEquals(listOf(5L, 2L, 1L), sorted.map { it.id })
    }

    @Test fun duplicateAnnouncementsCollapse() {
        val a = ann(1, "2026-01-01T10:00:00Z")
        assertEquals(1, SupportLogic.sortAnnouncements(listOf(a, a.copy())).size)
    }

    @Test fun globalAnnouncementsAreMarkedForAll() {
        assertTrue(SupportLogic.isForAll(ann(1, "", global = true)))
        assertTrue(SupportLogic.isForAll(ann(1, "", eventId = null)))
        assertFalse(SupportLogic.isForAll(ann(1, "", eventId = "e1")))
    }

    // ---- registered events ---------------------------------------------------------

    @Test fun registeredEventsFilterAndSortByDate() {
        val all = listOf(event("b", "2026-03-02"), event("a", "2026-03-01"), event("x", "2026-01-01"))
        assertEquals(listOf("a", "b"), SupportLogic.registeredEvents(all, listOf("b", "a", "missing")).map { it.id })
        assertTrue(SupportLogic.registeredEvents(all, emptyList()).isEmpty())
    }

    // ---- tickets -------------------------------------------------------------------

    @Test fun ticketsOrderedByLastActivityDesc() {
        val sorted = SupportLogic.sortTickets(
            listOf(ticket("1", "2026-01-01T00:00:00Z"), ticket("3", "2026-01-03T00:00:00Z"), ticket("2", "2026-01-02T00:00:00Z"))
        )
        assertEquals(listOf("3", "2", "1"), sorted.map { it.id })
    }

    @Test fun ticketPreviewAndStatus() {
        val t = SupportTicket(id = "1", lastMessage = LastMessage("hello\nworld", "manager", ""))
        assertEquals("Event team: hello world", SupportLogic.preview(t))
        assertEquals("No messages yet", SupportLogic.preview(SupportTicket(id = "2")))
        assertEquals("Closed", SupportLogic.statusLabel("closed"))
        assertEquals("Open", SupportLogic.statusLabel("open"))
    }

    @Test fun newChatValidation() {
        assertTrue(SupportLogic.isValidNewChat(" Hi ", " there "))
        assertFalse(SupportLogic.isValidNewChat("   ", "x"))
        assertFalse(SupportLogic.isValidNewChat("s", ""))
        assertFalse(SupportLogic.isValidNewChat("s".repeat(121), "x"))
    }

    // ---- optimistic merge ----------------------------------------------------------

    @Test fun optimisticBubbleIsReplacedByServerRowWithSameClientId() {
        val pending = SupportLogic.optimistic("c1", "hi", "2026-01-01T10:00:00Z")
        val merged = SupportLogic.mergeThread(listOf(pending), listOf(msg("7", "2026-01-01T10:00:01Z", clientId = "c1")))
        assertEquals(1, merged.size)
        assertEquals("7", merged[0].id)
        assertEquals(SendState.SENT, merged[0].state)
    }

    @Test fun unconfirmedOptimisticStaysAfterServerRows() {
        val pending = SupportLogic.optimistic("c2", "later", "2026-01-01T10:05:00Z")
        val merged = SupportLogic.mergeThread(listOf(pending), listOf(msg("1", "2026-01-01T10:00:00Z", "manager")))
        assertEquals(listOf("1", "local-c2"), merged.map { it.id })
        assertTrue(merged[0].fromTeam)
        assertEquals(SendState.PENDING, merged[1].state)
    }

    @Test fun failedBubbleSurvivesRefetchAndCanRetry() {
        val failed = SupportLogic.markFailed(listOf(SupportLogic.optimistic("c3", "x", "2026-01-01T10:00:00Z")), "c3")
        assertEquals(SendState.FAILED, failed[0].state)
        val after = SupportLogic.mergeThread(failed, emptyList())
        assertEquals(SendState.FAILED, after[0].state)
        assertEquals(SendState.PENDING, SupportLogic.markPending(after, "c3")[0].state)
    }

    @Test fun serverRowsAreSortedAndDeduplicated() {
        val merged = SupportLogic.mergeThread(
            emptyList(),
            listOf(msg("2", "2026-01-01T10:00:02Z"), msg("1", "2026-01-01T10:00:01Z"), msg("2", "2026-01-01T10:00:02Z"))
        )
        assertEquals(listOf("1", "2"), merged.map { it.id })
    }

    @Test fun acknowledgeThenRefetchDoesNotDuplicate() {
        val pending = listOf(SupportLogic.optimistic("c4", "hi", "2026-01-01T10:00:00Z"))
        val server = msg("9", "2026-01-01T10:00:01Z", clientId = "c4")
        val acked = SupportLogic.acknowledge(pending, "c4", server)
        assertEquals(listOf("9"), acked.map { it.id })
        assertEquals(listOf("9"), SupportLogic.mergeThread(acked, listOf(server)).map { it.id })
    }

    // ---- live signal routing --------------------------------------------------------

    @Test fun announcementSignalsRouteByEvent() {
        val global = LiveSignal(LiveSignal.Kind.ANNOUNCEMENT)
        val mine = LiveSignal(LiveSignal.Kind.ANNOUNCEMENT, eventId = "e1")
        val other = LiveSignal(LiveSignal.Kind.ANNOUNCEMENT, eventId = "e2")
        assertTrue(SupportLogic.shouldRefreshAnnouncements(global, "e1"))
        assertTrue(SupportLogic.shouldRefreshAnnouncements(mine, "e1"))
        assertFalse(SupportLogic.shouldRefreshAnnouncements(other, "e1"))
        assertTrue(SupportLogic.shouldRefreshAnnouncements(LiveSignal(LiveSignal.Kind.RECONNECTED), "e1"))
        assertFalse(SupportLogic.shouldRefreshAnnouncements(LiveSignal(LiveSignal.Kind.SUPPORT_MESSAGE), "e1"))
    }

    @Test fun supportSignalsRouteByEventAndTicket() {
        val sig = LiveSignal(LiveSignal.Kind.SUPPORT_MESSAGE, eventId = "e1", ticketId = "5")
        assertTrue(SupportLogic.shouldRefreshTickets(sig, "e1"))
        assertFalse(SupportLogic.shouldRefreshTickets(sig, "e2"))
        assertTrue(SupportLogic.shouldRefreshThread(sig, "5"))
        assertFalse(SupportLogic.shouldRefreshThread(sig, "6"))
        assertTrue(SupportLogic.shouldRefreshThread(LiveSignal(LiveSignal.Kind.SUPPORT_TICKET), "6"))
        assertFalse(SupportLogic.shouldRefreshThread(LiveSignal(LiveSignal.Kind.ANNOUNCEMENT), "6"))
    }

    // ---- error mapping -------------------------------------------------------------

    @Test fun errorCodesMapToReadableMessages() {
        assertTrue(SupportErrors.message(403, "NOT_REGISTERED", "x", "f").contains("Register"))
        assertTrue(SupportErrors.message(404, "EVENT_NOT_FOUND", "x", "f").contains("no longer exists"))
        assertTrue(SupportErrors.message(429, "RATE_LIMITED", null, "f").contains("too fast"))
        assertTrue(SupportErrors.message(500, null, null, "f").contains("server"))
        assertEquals("Subject is required", SupportErrors.message(400, "VALIDATION", "Subject is required", "f"))
        assertEquals("fallback", SupportErrors.message(400, null, null, "fallback"))
    }

    // ---- wire decoding -------------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    @Test fun bigintIdsDecodeFromNumbersOrStrings() {
        val numeric = json.decodeFromString<TicketMessagesResponse>(
            """{"ticket":{"id":12,"eventId":"e1","subject":"s","status":"open","lastMessage":null},
                "messages":[{"id":5,"ticketId":12,"senderRole":"manager","body":"hi","createdAt":"2026-01-01T00:00:00Z"}]}"""
        )
        assertEquals("12", numeric.ticket?.id)
        assertEquals("5", numeric.messages[0].id)
        assertEquals("12", numeric.messages[0].ticketId)
        val stringy = json.decodeFromString<TicketMessagesResponse>(
            """{"ticket":{"id":"12"},"messages":[{"id":"5","ticketId":"12","clientId":"c","senderId":null}]}"""
        )
        assertEquals("12", stringy.ticket?.id)
        assertEquals("c", stringy.messages[0].clientId)
        assertNull(stringy.messages[0].senderId)
    }
}
