package com.example.eventmanagementhub.ui.screens

import com.example.eventmanagementhub.data.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Merge rules for BitChat history + live socket echoes. These tests pin the
 * behaviour that used to wipe the thread on every tab switch: empty refreshes
 * must not clear existing rows, and reconnect must not duplicate.
 */
class ChatHistoryMergeTest {

    private fun msg(id: Long, author: String, text: String) =
        ChatMessage(id = id, author = author, text = text)

    @Test
    fun `empty incoming list leaves existing thread untouched`() {
        val existing = listOf("Alice" to "hi", "Bob" to "hello")
        val merged = ChatHistoryMerger.mergeIncoming(
            existing = existing,
            incoming = emptyList(),
            seenIds = mutableSetOf("1", "2"),
            ownIds = emptySet()
        )
        assertEquals(existing, merged)
    }

    @Test
    fun `history page is appended in order without duplicating seen ids`() {
        val seen = mutableSetOf("1")
        val existing = listOf("Alice" to "first")
        val incoming = listOf(
            msg(1, "Alice", "first"),
            msg(2, "Bob", "second"),
            msg(3, "Carol", "third")
        )
        val merged = ChatHistoryMerger.mergeIncoming(existing, incoming, seen, emptySet())
        assertEquals(
            listOf("Alice" to "first", "Bob" to "second", "Carol" to "third"),
            merged
        )
    }

    @Test
    fun `own messages render as You after ack stamps the id`() {
        val own = setOf("2")
        val merged = ChatHistoryMerger.mergeIncoming(
            existing = emptyList(),
            incoming = listOf(msg(1, "Alice", "hi"), msg(2, "Alice", "my reply")),
            seenIds = mutableSetOf(),
            ownIds = own
        )
        assertEquals("Alice" to "hi", merged[0])
        assertEquals("You" to "my reply", merged[1])
    }

    @Test
    fun `blank author falls back to Guest`() {
        val merged = ChatHistoryMerger.mergeIncoming(
            existing = emptyList(),
            incoming = listOf(msg(1, "", "anonymous hi")),
            seenIds = mutableSetOf(),
            ownIds = emptySet()
        )
        assertEquals("Guest" to "anonymous hi", merged.single())
    }

    @Test
    fun `blank text rows are skipped`() {
        val merged = ChatHistoryMerger.mergeIncoming(
            existing = emptyList(),
            incoming = listOf(msg(1, "Alice", "   "), msg(2, "Bob", "kept")),
            seenIds = mutableSetOf(),
            ownIds = emptySet()
        )
        assertEquals(listOf("Bob" to "kept"), merged)
    }

    @Test
    fun `reconnect resync does not duplicate a live echo already shown`() {
        val seen = mutableSetOf("10")
        val existing = listOf("Alice" to "live echo")
        // Same id arrives again via history after a reconnect.
        val merged = ChatHistoryMerger.mergeIncoming(
            existing = existing,
            incoming = listOf(msg(10, "Alice", "live echo")),
            seenIds = seen,
            ownIds = emptySet()
        )
        assertEquals(existing, merged)
    }

    @Test
    fun `admit returns true only once per id`() {
        val seen = mutableSetOf<String>()
        assertTrue(ChatHistoryMerger.admit("a", seen))
        assertFalse(ChatHistoryMerger.admit("a", seen))
        assertTrue(ChatHistoryMerger.admit("b", seen))
    }

    @Test
    fun `displayAuthor prefers You over the server name`() {
        assertEquals(
            "You",
            ChatHistoryMerger.displayAuthor(msg(5, "Alice", "x"), ownIds = setOf("5"))
        )
        assertEquals(
            "Alice",
            ChatHistoryMerger.displayAuthor(msg(5, "Alice", "x"), ownIds = emptySet())
        )
    }
}
