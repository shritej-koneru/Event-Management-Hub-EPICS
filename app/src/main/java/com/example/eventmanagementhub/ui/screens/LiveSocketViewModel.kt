package com.example.eventmanagementhub.ui.screens

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.eventmanagementhub.data.HubRepository
import com.example.eventmanagementhub.data.LiveSignal
import com.example.eventmanagementhub.data.LiveUpdates
import com.example.eventmanagementhub.data.MapUpdates
import com.example.eventmanagementhub.data.Result
import com.example.eventmanagementhub.data.api.ApiClient
import com.example.eventmanagementhub.data.api.AuthProvider
import com.example.eventmanagementhub.data.offlineVenueOrder
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Session-scoped Socket.IO connection (Activity ViewModel, started at sign-in).
 *
 * It no longer carries any chat thread. It forwards:
 *  - `map:updated`     -> [MapUpdates] (Map screen refetches),
 *  - `event`           -> [LiveUpdates] ANNOUNCEMENT (announcement screens refetch),
 *  - `support:message` -> [LiveUpdates] SUPPORT_MESSAGE,
 *  - `support:ticket`  -> [LiveUpdates] SUPPORT_TICKET.
 * The socket handshake JWT lets the server put us in `user:<id>`; the client never
 * names those rooms. On every (re)connect a RECONNECTED signal makes open screens
 * refetch whatever they missed.
 */
class LiveSocketViewModel(
    private val repository: HubRepository = HubRepository()
) : ViewModel() {

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var socket: Socket? = null
    private var started = false
    private var startedWithToken: String? = null

    /** Called on sign-out / session expiry so the old identity's socket doesn't linger. */
    fun onSignedOut() {
        if (started) resetForIdentityChange()
        started = false
        startedWithToken = null
    }

    private fun resetForIdentityChange() {
        socket?.let { it.off(); it.disconnect() }
        socket = null
        _connected.value = false
    }

    /**
     * Idempotent for the same signed-in identity. If the auth token changed, the old
     * socket (which carries the old handshake token) is torn down and recreated.
     */
    fun start() {
        val token = AuthProvider.token
        if (started && token == startedWithToken) return
        if (started) resetForIdentityChange()
        started = true
        startedWithToken = token
        connectSocket()
    }

    private fun connectSocket() {
        viewModelScope.launch {
            try {
                val uri = parseBaseUri()
                Log.i(TAG, "connecting socket uri=$uri")
                val opts = IO.Options().apply {
                    path = "/socket.io"
                    forceNew = true
                    auth = mapOf("token" to (AuthProvider.token ?: ""))
                }
                val s = IO.socket(uri, opts)
                socket = s
                s.on(Socket.EVENT_CONNECT) {
                    Log.i(TAG, "socket connected id=${s.id()}")
                    viewModelScope.launch {
                        joinVenueRooms(s)
                        _connected.value = true
                        LiveUpdates.emit(LiveSignal(LiveSignal.Kind.RECONNECTED))
                    }
                }
                s.on("joined") { _connected.value = true }
                // Manager edited the campus map: tell the Map screen, and join any new venue rooms.
                s.on("map:updated") {
                    Log.i(TAG, "map:updated")
                    MapUpdates.notifyUpdated()
                    viewModelScope.launch { joinVenueRooms(s) }
                }
                s.on("event") { args ->
                    val signal = parseAnnouncementSignal(args.firstOrNull() as? JSONObject)
                    Log.i(TAG, "event broadcast eventId=${signal.eventId}")
                    LiveUpdates.emit(signal)
                }
                s.on("support:message") { args ->
                    val signal = parseSupportSignal(LiveSignal.Kind.SUPPORT_MESSAGE, args.firstOrNull() as? JSONObject)
                    Log.i(TAG, "support:message ticket=${signal.ticketId}")
                    LiveUpdates.emit(signal)
                }
                s.on("support:ticket") { args ->
                    val signal = parseSupportSignal(LiveSignal.Kind.SUPPORT_TICKET, args.firstOrNull() as? JSONObject)
                    Log.i(TAG, "support:ticket ticket=${signal.ticketId}")
                    LiveUpdates.emit(signal)
                }
                s.on(Socket.EVENT_CONNECT_ERROR) { args ->
                    Log.w(TAG, "socket connect_error: ${args.firstOrNull()}")
                    _connected.value = false
                }
                s.on(Socket.EVENT_DISCONNECT) {
                    Log.i(TAG, "socket disconnected")
                    _connected.value = false
                }
                s.connect()
            } catch (e: Exception) {
                Log.e(TAG, "socket setup failed", e)
                _connected.value = false
            }
        }
    }

    /** Rooms come from the backend venue catalogue; the offline list is only the fallback. */
    private suspend fun joinVenueRooms(s: Socket) {
        val rooms = when (val res = repository.venues()) {
            is Result.Success -> res.data.venues.map { "announce:${it.id}" }
            is Result.Error -> allVenueRooms()
        }
        rooms.forEach { room ->
            s.emit("join", room, Ack { ack ->
                val n = (ack.firstOrNull() as? JSONObject)?.optJSONArray("joined")?.length() ?: 0
                Log.i(TAG, "join ack for $room: $n room(s)")
            })
        }
    }

    override fun onCleared() {
        socket?.disconnect()
        socket = null
        super.onCleared()
    }

    companion object {
        private const val TAG = "LiveSocket"

        /** Fallback join list — used only when GET /api/navigation/venues is unreachable. */
        fun allVenueRooms(): List<String> = offlineVenueOrder.map { "announce:$it" }

        fun parseBaseUri(): String =
            ApiClient.baseUrl
                .replace("http://", "ws://")
                .replace("https://", "wss://")
                .trimEnd('/')

        private fun JSONObject.idOrNull(key: String): String? =
            opt(key)?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() }

        /** `event` payload: `eventId` may sit at the top level or inside `announcement`; absent = global. */
        private fun parseAnnouncementSignal(obj: JSONObject?): LiveSignal {
            val eventId = obj?.idOrNull("eventId") ?: obj?.optJSONObject("announcement")?.idOrNull("eventId")
            return LiveSignal(LiveSignal.Kind.ANNOUNCEMENT, eventId = eventId)
        }

        /** `support:message` / `support:ticket` payload: `{ ticket: { id, eventId, ... }, message? }`. */
        private fun parseSupportSignal(kind: LiveSignal.Kind, obj: JSONObject?): LiveSignal {
            val ticket = obj?.optJSONObject("ticket")
            val ticketId = ticket?.idOrNull("id") ?: obj?.optJSONObject("message")?.idOrNull("ticketId")
            return LiveSignal(kind, eventId = ticket?.idOrNull("eventId"), ticketId = ticketId)
        }
    }
}
