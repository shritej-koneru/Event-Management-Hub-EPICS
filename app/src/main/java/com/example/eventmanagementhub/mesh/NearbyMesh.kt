package com.example.eventmanagementhub.mesh

import android.content.Context
import android.content.Intent
import com.epics.bitchat.mesh.MeshEventListener
import com.epics.bitchat.mesh.MeshKind
import com.epics.bitchat.mesh.MeshLogEntry
import com.epics.bitchat.mesh.MeshManager
import com.epics.bitchat.mesh.MeshMessage
import com.epics.bitchat.mesh.PacketCodec
import com.epics.bitchat.mesh.Peer
import com.epics.bitchat.service.MeshTransportService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why nearby chat cannot run right now. */
enum class NearbyBlocked { BLUETOOTH_OFF, PERMISSIONS_MISSING, NOT_SUPPORTED }

/** Outcome of [NearbyMesh.send]. */
enum class NearbySendResult { SENT, EMPTY, TOO_LONG, NO_RECIPIENT, MESH_NOT_RUNNING }

data class NearbyPeer(val id: String, val name: String, val rssi: Int, val lastSeenEpochMs: Long)

data class NearbyState(
    val running: Boolean = false,
    val blocked: NearbyBlocked? = null,
    val myId: String = "",
    val myName: String = ""
)

/**
 * The app's connection to the offline Bluetooth mesh (the `:meshandroid` backend + the `:meshlib` engine).
 *
 * This is the BACKEND only: it starts the mesh as a foreground service, listens to it, and publishes state for the
 * app's own Compose screens. It never runs the simulator, so everything shown here came over real Bluetooth.
 * The mesh is anonymous (a device id, no account) and works with no internet.
 */
object NearbyMesh : MeshEventListener {

    private val book = NearbyMessageBook(PacketCodec.MAX_FRAGMENTED_TEXT)

    private val _state = MutableStateFlow(NearbyState())
    private val _peers = MutableStateFlow<List<NearbyPeer>>(emptyList())
    private val _messages = MutableStateFlow<List<NearbyMessage>>(emptyList())

    val state: StateFlow<NearbyState> = _state.asStateFlow()
    val peers: StateFlow<List<NearbyPeer>> = _peers.asStateFlow()
    val messages: StateFlow<List<NearbyMessage>> = _messages.asStateFlow()

    /** The longest text the mesh can carry (it is split into frames and reassembled on the other phone). */
    val maxTextLength: Int get() = PacketCodec.MAX_FRAGMENTED_TEXT

    @Volatile private var manager: MeshManager? = null
    @Volatile private var listening = false

    private fun manager(context: Context): MeshManager {
        val m = manager ?: MeshManager.get(context.applicationContext).also {
            it.setBleOnly(true) // never the simulator
            manager = it
        }
        if (!listening) {
            m.addListener(this)
            listening = true
        }
        return m
    }

    /** Re-reads what blocks the mesh (radio off, permissions) and whether it runs. Safe to call often. */
    fun refresh(context: Context) {
        val m = manager(context)
        val blocked = when (m.blockedReason) {
            "BLUETOOTH_OFF" -> NearbyBlocked.BLUETOOTH_OFF
            "PERMISSIONS_MISSING" -> NearbyBlocked.PERMISSIONS_MISSING
            else -> null
        }
        _state.value = NearbyState(running = m.isRunning, blocked = blocked, myId = m.deviceId, myName = m.nodeName)
        refreshPeers()
    }

    /** Starts the mesh (foreground service) when nothing blocks it. Returns whether it was started. */
    fun start(context: Context): Boolean {
        val m = manager(context)
        refresh(context)
        if (_state.value.blocked != null) return false
        MeshTransportService.start(context.applicationContext)
        return true
    }

    fun stop(context: Context) {
        context.applicationContext.stopService(Intent(context.applicationContext, MeshTransportService::class.java))
        _state.value = _state.value.copy(running = false)
        _peers.value = emptyList()
    }

    /** Refreshes the nearby-phone list from the mesh's neighbour table. */
    fun refreshPeers() {
        val node = manager?.meshNode
        val own = _state.value.myId
        _peers.value = node?.peers.orEmpty()
            .filter { !it.isBeacon && it.nodeId != own }
            .map { NearbyPeer(it.nodeId, it.displayName ?: it.nodeId, it.rssi, it.lastSeenEpochMs) }
            .sortedBy { it.name.lowercase() }
        // The mesh may have started after the last refresh.
        val m = manager
        if (m != null && m.isRunning != _state.value.running) _state.value = _state.value.copy(running = m.isRunning)
    }

    /**
     * Sends [text] to the phone [toId]. [NearbySendResult.SENT] only means the mesh accepted it: the message shows as
     * delivered later, when the recipient's acknowledgement arrives (see [onDeliveryStatus]).
     */
    fun send(toId: String?, toName: String, text: String): NearbySendResult {
        when (book.validate(toId, text)) {
            NearbyInvalid.EMPTY -> return NearbySendResult.EMPTY
            NearbyInvalid.TOO_LONG -> return NearbySendResult.TOO_LONG
            NearbyInvalid.NO_RECIPIENT -> return NearbySendResult.NO_RECIPIENT
            null -> Unit
        }
        val node = manager?.meshNode
        if (node == null || !node.isRunning) return NearbySendResult.MESH_NOT_RUNNING
        val st = _state.value
        book.addSent(st.myId, st.myName, toId!!, toName, text, System.currentTimeMillis())
        publishMessages()
        if (!node.send(toId, MeshKind.CHAT, text.trim())) {
            // The mesh could not even queue it: report failure at once, never "sending" forever.
            book.onDeliveryResult(toId, delivered = false)
            publishMessages()
            return NearbySendResult.MESH_NOT_RUNNING
        }
        return NearbySendResult.SENT
    }

    fun clearConversation() {
        book.clear()
        publishMessages()
    }

    private fun publishMessages() {
        _messages.value = book.snapshot()
    }

    // ---- MeshEventListener (called from mesh threads) --------------------------------------------------------

    // The mesh log also holds the raw Bluetooth frames of a long message; never read chat from it.
    override fun onLogEntry(entry: MeshLogEntry) = Unit

    /**
     * A COMPLETE message reached this phone: the engine calls this once per message (a long text only after every
     * frame arrived and was reassembled; the raw frames never get here) and sends the acknowledgement itself.
     */
    override fun onMessageDelivered(message: MeshMessage) {
        if (message.kind != MeshKind.CHAT) return
        val st = _state.value
        if (st.myId.isEmpty() || message.dstId != st.myId) return
        val key = "${message.msgId}|${message.srcId}|${message.payload}"
        val fromName = manager?.displayNameFor(message.srcId) ?: message.srcId
        if (book.onIncoming(key, st.myId, st.myName, message.srcId, fromName, message.payload, System.currentTimeMillis()) != null) {
            publishMessages()
        }
    }

    override fun onDeliveryStatus(token: String, dstId: String, delivered: Boolean) {
        if (book.onDeliveryResult(dstId, delivered) != null) publishMessages()
    }

    override fun onPeerDiscovered(peer: Peer) = refreshPeers()
    override fun onPeerLost(peer: Peer) = refreshPeers()
    override fun onPositionUpdated(graphNodeId: String) = Unit
}
