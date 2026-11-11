package com.example.eventmanagementhub.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyMessageBookTest {

    private fun book() = NearbyMessageBook(maxTextLength = 20)

    @Test fun validation_rejectsEmptyWhitespaceTooLongAndMissingRecipient() {
        val b = book()
        assertEquals(NearbyInvalid.NO_RECIPIENT, b.validate(null, "hi"))
        assertEquals(NearbyInvalid.NO_RECIPIENT, b.validate("  ", "hi"))
        assertEquals(NearbyInvalid.EMPTY, b.validate("B1", ""))
        assertEquals(NearbyInvalid.EMPTY, b.validate("B1", "   \n "))
        assertEquals(NearbyInvalid.TOO_LONG, b.validate("B1", "x".repeat(21)))
        assertNull(b.validate("B1", "x".repeat(20)))
        assertNull("surrounding spaces do not count against the limit", b.validate("B1", "  " + "x".repeat(20) + "  "))
    }

    @Test fun sentMessage_isSendingUntilTheRecipientAcknowledges_neverDeliveredBeforehand() {
        val b = book()
        val m = b.addSent("A1", "Phone A", "B1", "Phone B", " hello ", now = 10)

        assertEquals("text is stored trimmed", "hello", m.text)
        assertEquals(NearbyStatus.SENDING, b.snapshot().single().status)

        val done = b.onDeliveryResult("B1", delivered = true)!!
        assertEquals(NearbyStatus.DELIVERED, done.status)
        assertEquals(NearbyStatus.DELIVERED, b.snapshot().single().status)
    }

    @Test fun missingAcknowledgement_marksNotDelivered_neverFalseSuccess() {
        val b = book()
        b.addSent("A1", "A", "B1", "B", "hello", 1)

        assertEquals(NearbyStatus.NOT_DELIVERED, b.onDeliveryResult("B1", delivered = false)!!.status)
    }

    @Test fun deliveryResults_applyToTheOldestPendingMessageForThatRecipientOnly() {
        val b = book()
        b.addSent("A1", "A", "B1", "B", "one", 1)
        b.addSent("A1", "A", "C1", "C", "to-c", 2)
        b.addSent("A1", "A", "B1", "B", "two", 3)

        b.onDeliveryResult("B1", true)
        val s = b.snapshot()
        assertEquals(NearbyStatus.DELIVERED, s[0].status)
        assertEquals("another recipient is untouched", NearbyStatus.SENDING, s[1].status)
        assertEquals("the later message to B still waits", NearbyStatus.SENDING, s[2].status)

        b.onDeliveryResult("B1", false)
        assertEquals(NearbyStatus.NOT_DELIVERED, b.snapshot()[2].status)
    }

    @Test fun deliveryResult_withNothingWaiting_isIgnored() {
        val b = book()
        assertNull(b.onDeliveryResult("B1", true))
        b.addSent("A1", "A", "B1", "B", "x", 1)
        b.onDeliveryResult("B1", true)
        assertNull("a second report has no message left to apply to", b.onDeliveryResult("B1", true))
    }

    @Test fun incomingMessage_isStoredOnce_evenWhenTheMeshReportsItRepeatedly() {
        val b = book()
        val first = b.onIncoming("k1", "A1", "Phone A", "B1", "Phone B", "ping", now = 5)
        val dup = b.onIncoming("k1", "A1", "Phone A", "B1", "Phone B", "ping", now = 6)
        val other = b.onIncoming("k2", "A1", "Phone A", "B1", "Phone B", "ping", now = 7)

        assertEquals(NearbyStatus.RECEIVED, first!!.status)
        assertEquals(false, first.mine)
        assertNull(dup)
        assertTrue(other != null)
        assertEquals("the same text sent twice is two messages, only the same frame is a duplicate", 2, b.snapshot().size)
    }

    @Test fun messagesKeepSendAndArrivalOrder_withUniqueIds() {
        val b = book()
        b.addSent("A1", "A", "B1", "B", "1", 1)
        b.onIncoming("k", "B1", "B", "A1", "A", "2", 2)
        b.addSent("A1", "A", "B1", "B", "3", 3)

        val s = b.snapshot()
        assertEquals(listOf("1", "2", "3"), s.map { it.text })
        assertEquals(3, s.map { it.id }.toSet().size)
    }

    @Test fun clear_forgetsEverythingIncludingDuplicateKeys() {
        val b = book()
        b.onIncoming("k", "B1", "B", "A1", "A", "hi", 1)
        b.clear()
        assertTrue(b.snapshot().isEmpty())
        assertTrue("after clearing, the same key is new again", b.onIncoming("k", "B1", "B", "A1", "A", "hi", 2) != null)
    }
}
