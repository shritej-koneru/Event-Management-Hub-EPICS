package com.example.eventmanagementhub.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxTest {

    private fun add(id: String) = PendingBookmark(id, true)
    private fun rem(id: String) = PendingBookmark(id, false)

    @Test fun enqueue_latestWinsPerEvent() {
        var ops = Outbox.enqueue(emptyList(), add("a"))
        ops = Outbox.enqueue(ops, add("b"))
        ops = Outbox.enqueue(ops, rem("a"))
        assertEquals(listOf(add("b"), rem("a")), ops)
    }

    @Test fun merge_appliesPendingOverServerList() {
        val merged = Outbox.mergeBookmarks(listOf("a", "b"), listOf(rem("a"), add("c")))
        assertEquals(listOf("b", "c"), merged)
    }

    @Test fun merge_addIsIdempotent_removeOfMissingIsNoop() {
        assertEquals(listOf("a"), Outbox.mergeBookmarks(listOf("a"), listOf(add("a"), rem("zzz"))))
    }

    @Test fun merge_noPending_returnsServerList() {
        assertEquals(listOf("x", "y"), Outbox.mergeBookmarks(listOf("x", "y"), emptyList()))
    }

    @Test fun ack_doesNotDropSupersedingOp() {
        val queued = Outbox.enqueue(listOf(add("a")), rem("a"))
        // The add was in flight when the user un-bookmarked; its ack must not remove the remove.
        assertEquals(listOf(rem("a")), Outbox.ack(queued, add("a")))
        assertEquals(emptyList<PendingBookmark>(), Outbox.ack(queued, rem("a")))
    }

    @Test fun replay_sendsInOrder_andDrains() = runTest {
        val sent = mutableListOf<PendingBookmark>()
        val ops = listOf(add("a"), rem("b"), add("c"))
        val r = Outbox.replay(ops) { sent += it; SendOutcome.ACKED }
        assertEquals(ops, sent)
        assertTrue(r.drained)
        assertEquals(ops, r.resolved)
    }

    @Test fun replay_stopsAtFirstRetry_keepingOrder() = runTest {
        val sent = mutableListOf<PendingBookmark>()
        val ops = listOf(add("a"), add("b"), add("c"))
        val r = Outbox.replay(ops) {
            sent += it
            if (it.eventId == "b") SendOutcome.RETRY_LATER else SendOutcome.ACKED
        }
        assertEquals(listOf(add("a"), add("b")), sent)
        assertFalse(r.drained)
        assertEquals(listOf(add("a")), r.resolved)
    }

    @Test fun replay_permanentRejectionIsDroppedAndDoesNotBlock() = runTest {
        val ops = listOf(add("gone"), add("ok"))
        val r = Outbox.replay(ops) {
            if (it.eventId == "gone") SendOutcome.REJECTED else SendOutcome.ACKED
        }
        assertTrue(r.drained)
        assertEquals(ops, r.resolved)
    }
}
