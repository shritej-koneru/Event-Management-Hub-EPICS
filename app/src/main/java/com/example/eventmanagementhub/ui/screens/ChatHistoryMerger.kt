package com.example.eventmanagementhub.ui.screens

import com.example.eventmanagementhub.data.model.ChatMessage

/**
 * Pure merge rules for the BitChat thread. Kept free of Android types so the
 * unit tests can exercise reconnect / history / socket-echo behaviour without
 * a device or Socket.IO connection.
 */
object ChatHistoryMerger {

    /**
     * Appends [incoming] rows that are not already in [seenIds]. Rows whose id
     * is in [ownIds] render as "You". Existing order is preserved; new rows are
     * appended (chronological, matching the server page).
     *
     * Returns [existing] unchanged when [incoming] is empty, so a failed or
     * empty history refresh never wipes the thread.
     */
    fun mergeIncoming(
        existing: List<Pair<String, String>>,
        incoming: List<ChatMessage>,
        seenIds: MutableSet<String>,
        ownIds: Set<String>
    ): List<Pair<String, String>> {
        if (incoming.isEmpty()) return existing
        var result = existing
        for (message in incoming) {
            val text = message.text
            if (text.isBlank()) continue
            if (!seenIds.add(message.id.toString())) continue
            result = result + (displayAuthor(message, ownIds) to text)
        }
        return result
    }

    /** Display name for a stored/broadcast message. */
    fun displayAuthor(message: ChatMessage, ownIds: Set<String>): String =
        if (ownIds.contains(message.id.toString())) "You" else message.author.ifBlank { "Guest" }

    /** True when this id has not been rendered yet. Adds it to [seenIds] when new. */
    fun admit(id: String, seenIds: MutableSet<String>): Boolean = seenIds.add(id)
}
