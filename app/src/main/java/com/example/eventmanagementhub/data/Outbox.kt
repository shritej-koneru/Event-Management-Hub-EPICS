package com.example.eventmanagementhub.data

import kotlinx.serialization.Serializable

/** A bookmark change made while (or before) the backend was reachable, not yet acked. */
@Serializable
data class PendingBookmark(val eventId: String, val add: Boolean)

/** What happened when one queued op was sent. */
enum class SendOutcome {
    /** Server accepted it: drop the op. */
    ACKED,

    /** Server permanently rejected it (4xx other than 401): drop it, it can never succeed. */
    REJECTED,

    /** Offline / 5xx / 401: keep this op and everything after it, retry later (order is preserved). */
    RETRY_LATER
}

/**
 * Pure logic of the client outbox for bookmark/interest edits. The server endpoints are
 * idempotent set operations, so replaying an op twice is harmless; ordering plus
 * latest-wins-per-eventId is all that matters.
 */
object Outbox {

    /** Appends [op]; any older op for the same event is superseded (latest wins). */
    fun enqueue(ops: List<PendingBookmark>, op: PendingBookmark): List<PendingBookmark> =
        ops.filterNot { it.eventId == op.eventId } + op

    /** Applies still-pending ops, in order, on top of an authoritative server list. */
    fun mergeBookmarks(server: List<String>, ops: List<PendingBookmark>): List<String> {
        var result = server
        for (op in ops) {
            result = if (op.add) {
                if (op.eventId in result) result else result + op.eventId
            } else {
                result.filterNot { it == op.eventId }
            }
        }
        return result
    }

    /** Removes [op] only if it is still the queued op for its event (it may have been superseded). */
    fun ack(ops: List<PendingBookmark>, op: PendingBookmark): List<PendingBookmark> =
        ops.filterNot { it == op }

    /**
     * Sends [ops] in order. Stops at the first RETRY_LATER so later ops never overtake it.
     * Returns the ops that were resolved (ACKED or REJECTED) and whether the queue fully drained.
     */
    suspend fun replay(
        ops: List<PendingBookmark>,
        send: suspend (PendingBookmark) -> SendOutcome
    ): ReplayResult {
        val resolved = mutableListOf<PendingBookmark>()
        for (op in ops) {
            when (send(op)) {
                SendOutcome.ACKED, SendOutcome.REJECTED -> resolved += op
                SendOutcome.RETRY_LATER -> return ReplayResult(resolved, drained = false)
            }
        }
        return ReplayResult(resolved, drained = true)
    }

    data class ReplayResult(val resolved: List<PendingBookmark>, val drained: Boolean)
}
