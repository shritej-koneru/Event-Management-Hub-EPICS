package com.example.eventmanagementhub.mesh

/** How a nearby message stands. A message only counts as delivered when the receiving phone acknowledged it. */
enum class NearbyStatus { SENDING, DELIVERED, NOT_DELIVERED, RECEIVED }

data class NearbyMessage(
    val id: Long,
    /** Mesh device id of the sender (this phone's own id for messages I sent). */
    val fromId: String,
    val fromName: String,
    /** Mesh device id of the recipient. */
    val toId: String,
    val toName: String,
    val text: String,
    val mine: Boolean,
    val status: NearbyStatus,
    val atEpochMs: Long
)

/** Why a message cannot be sent. */
enum class NearbyInvalid { EMPTY, TOO_LONG, NO_RECIPIENT }

/**
 * The conversation state for offline nearby chat, with no Android in it so the rules can be unit-tested:
 *  - a sent message starts as SENDING and becomes DELIVERED or NOT_DELIVERED only when the mesh reports the
 *    acknowledgement (or its absence) for that recipient; acknowledgements arrive per recipient in send order;
 *  - an incoming message is added once however many times the mesh reports it;
 *  - text is validated before anything is sent.
 */
class NearbyMessageBook(private val maxTextLength: Int = DEFAULT_MAX_TEXT) {

    private val messages = ArrayList<NearbyMessage>()
    private val seenIncoming = HashSet<String>()
    private var nextId = 1L

    /** Null when [text] may be sent to [toId]; otherwise the reason it may not. */
    fun validate(toId: String?, text: String): NearbyInvalid? = when {
        toId.isNullOrBlank() -> NearbyInvalid.NO_RECIPIENT
        text.isBlank() -> NearbyInvalid.EMPTY
        text.trim().length > maxTextLength -> NearbyInvalid.TOO_LONG
        else -> null
    }

    @Synchronized
    fun addSent(myId: String, myName: String, toId: String, toName: String, text: String, now: Long): NearbyMessage {
        val m = NearbyMessage(nextId++, myId, myName, toId, toName, text.trim(), true, NearbyStatus.SENDING, now)
        messages += m
        return m
    }

    /**
     * The mesh reported whether [toId] acknowledged. Applies to the OLDEST message still SENDING to that recipient.
     * Returns the updated message, or null when nothing was waiting (e.g. a late report for an old message).
     */
    @Synchronized
    fun onDeliveryResult(toId: String, delivered: Boolean): NearbyMessage? {
        val i = messages.indexOfFirst { it.mine && it.toId == toId && it.status == NearbyStatus.SENDING }
        if (i < 0) return null
        val updated = messages[i].copy(status = if (delivered) NearbyStatus.DELIVERED else NearbyStatus.NOT_DELIVERED)
        messages[i] = updated
        return updated
    }

    /**
     * A message addressed to this phone arrived. [key] identifies the mesh frame so the same message reported twice
     * (relay copies, log replays) is stored once. Returns the new message, or null for a duplicate.
     */
    @Synchronized
    fun onIncoming(key: String, myId: String, myName: String, fromId: String, fromName: String, text: String, now: Long): NearbyMessage? {
        if (!seenIncoming.add(key)) return null
        val m = NearbyMessage(nextId++, fromId, fromName, myId, myName, text, false, NearbyStatus.RECEIVED, now)
        messages += m
        return m
    }

    @Synchronized
    fun snapshot(): List<NearbyMessage> = messages.toList()

    @Synchronized
    fun clear() {
        messages.clear()
        seenIncoming.clear()
    }

    companion object {
        /** Texts longer than one Bluetooth advert are split into frames and reassembled; the mesh allows about this much. */
        const val DEFAULT_MAX_TEXT = 200
    }
}
