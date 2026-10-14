package com.example.eventmanagementhub.ui.screens

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.eventmanagementhub.data.HubRepository
import com.example.eventmanagementhub.data.Result
import com.example.eventmanagementhub.data.api.ApiClient
import com.example.eventmanagementhub.data.api.AuthProvider
import com.example.eventmanagementhub.data.model.Announcement
import com.example.eventmanagementhub.data.model.ChatMessage
import com.example.eventmanagementhub.data.offlineVenueOrder
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Activity-scoped holder for the BitChat thread.
 *
 * Chat messages used to live in `remember` inside ChatScreen, so every tab
 * switch disposed the composition, disconnected the socket, and dropped the
 * list. This ViewModel is retained by the Activity's ViewModelStore, so the
 * thread (and the socket) survive Home/Events/Map navigation. Closing the app
 * clears the ViewModel; reopening reloads history from GET /api/chat/messages.
 */
class ChatViewModel(
    private val repository: HubRepository = HubRepository()
) : ViewModel() {

    private val _messages = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val messages: StateFlow<List<Pair<String, String>>> = _messages.asStateFlow()

    private val _announcements = MutableStateFlow<List<Announcement>>(emptyList())
    val announcements: StateFlow<List<Announcement>> = _announcements.asStateFlow()

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _isStale = MutableStateFlow(false)
    val isStale: StateFlow<Boolean> = _isStale.asStateFlow()

    private val _isLoadingHistory = MutableStateFlow(false)
    val isLoadingHistory: StateFlow<Boolean> = _isLoadingHistory.asStateFlow()

    private val seenMessageKeys = mutableSetOf<String>()
    private val seenChatIds = mutableSetOf<String>()
    private val ownChatIds = mutableSetOf<String>()

    private var socket: Socket? = null
    private var started = false

    fun onDraftChange(value: String) {
        _draft.value = value.take(500)
    }

    /** Idempotent: first call loads history + connects; later calls are no-ops. */
    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            loadHistory()
            connectSocket()
        }
    }

    fun sendChat() {
        val text = _draft.value.trim()
        if (text.isEmpty() || text.length > 500) return
        val s = socket
        if (s == null || !s.connected()) {
            Log.w(TAG, "sendChat blocked: socket=${s != null} connected=${s?.connected()}")
            return
        }
        s.emit("chat:message", JSONObject().put("text", text), Ack { args ->
            val ackObj = args.firstOrNull() as? JSONObject
            val msgId = ackObj?.optJSONObject("message")?.optString("id")
            if (ackObj?.optBoolean("ok") == true && !msgId.isNullOrEmpty()) {
                synchronized(ownChatIds) { ownChatIds.add(msgId) }
                Log.i(TAG, "chat:message acked id=$msgId")
            } else {
                Log.w(TAG, "chat:message nack: $ackObj")
            }
        })
        _draft.value = ""
    }

    /**
     * Loads persisted history from the backend. On success it MERGES into the
     * existing thread (never replaces it with an empty array). On failure the
     * current list is left untouched.
     */
    suspend fun loadHistory() {
        _isLoadingHistory.value = true
        try {
            when (val rest = repository.chatMessages(limit = HISTORY_LIMIT)) {
                is Result.Success -> {
                    val page = rest.data.messages
                    _messages.value = ChatHistoryMerger.mergeIncoming(
                        existing = _messages.value,
                        incoming = page,
                        seenIds = synchronized(seenChatIds) { seenChatIds },
                        ownIds = synchronized(ownChatIds) { ownChatIds.toSet() }
                    )
                    // Seed announcement dedupe from nothing; history is chat-only.
                }
                is Result.Error -> {
                    // Keep whatever we already show; do not blank the thread.
                    Log.w(TAG, "chat history load failed: ${rest.message}")
                }
            }
        } finally {
            _isLoadingHistory.value = false
        }
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
                    allVenueRooms().forEach { room ->
                        s.emit("join", room, Ack { ack ->
                            val n = (ack.firstOrNull() as? JSONObject)?.optJSONArray("joined")?.length() ?: 0
                            Log.i(TAG, "join ack for $room: $n room(s)")
                        })
                    }
                    _connected.value = true
                    // Pull anything we missed while disconnected.
                    viewModelScope.launch { loadHistory() }
                }
                s.on("joined") { _connected.value = true }
                s.on("event") { args ->
                    if (args.isEmpty()) return@on
                    val obj = args[0] as? JSONObject
                    val title = obj?.optString("title")?.takeIf { it.isNotBlank() } ?: "Event update"
                    val venue = obj?.optString("venue")?.takeIf { it.isNotBlank() } ?: "Unknown venue"
                    val body = "🔔 $title — $venue"
                    Log.i(TAG, "event broadcast: $title @ $venue")
                    pushAnnouncement(Announcement(id = 0, room = "", message = body))
                    prependSystemMessage("New announcement at $venue: $title")
                }
                s.on("chat:message") { args ->
                    if (args.isEmpty()) return@on
                    val obj = args[0] as? JSONObject ?: return@on
                    val id = obj.optString("id")
                    val text = obj.optString("text").takeIf { it.isNotBlank() } ?: return@on
                    val author = obj.optString("author").ifBlank { "Guest" }
                    Log.i(TAG, "chat:message from $author: $text")
                    appendChatMessage(id, author, text)
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

    private fun appendChatMessage(id: String, author: String, text: String) {
        val isNew = synchronized(seenChatIds) { ChatHistoryMerger.admit(id, seenChatIds) }
        if (!isNew) return
        val mine = synchronized(ownChatIds) { ownChatIds.contains(id) }
        _messages.value = _messages.value + ((if (mine) "You" else author) to text)
    }

    private fun prependSystemMessage(text: String) {
        _messages.value = listOf("BitChat" to text) + _messages.value
    }

    private fun pushAnnouncement(a: Announcement) {
        val key = "${a.id}|${a.message}"
        val isNew = synchronized(seenMessageKeys) { seenMessageKeys.add(key) }
        if (!isNew) return
        _announcements.value = (listOf(a) + _announcements.value).take(50)
    }

    override fun onCleared() {
        socket?.disconnect()
        socket = null
        super.onCleared()
    }

    companion object {
        private const val TAG = "BitChatSocket"
        private const val HISTORY_LIMIT = 100

        fun allVenueRooms(): List<String> = offlineVenueOrder.map { "announce:$it" }

        fun parseBaseUri(): String =
            ApiClient.baseUrl
                .replace("http://", "ws://")
                .replace("https://", "wss://")
                .trimEnd('/')
    }
}
