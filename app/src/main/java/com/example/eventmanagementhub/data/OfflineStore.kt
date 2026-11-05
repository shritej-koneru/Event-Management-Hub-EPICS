package com.example.eventmanagementhub.data

import android.content.Context
import com.example.eventmanagementhub.data.model.Announcement
import com.example.eventmanagementhub.data.model.CampusMapResponse
import com.example.eventmanagementhub.data.model.Event
import com.example.eventmanagementhub.data.model.UserProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class OfflineCacheData(
    val events: List<Event> = emptyList(),
    val registered: List<String> = emptyList(),
    val user: UserProfile? = null,
    val announcements: List<Announcement> = emptyList(),
    val recentQueries: List<String> = emptyList(),
    /** Unsent bookmark edits, oldest first, one per eventId (latest wins). */
    val pendingBookmarks: List<PendingBookmark> = emptyList(),
    /** Unsent interests edit (null = nothing pending). */
    val pendingInterests: List<String>? = null,
    /** Last campus map fetched from the server (manager edits); null = never fetched. */
    val campusMap: CampusMapResponse? = null
)

/**
 * Persists everything the app needs for a fully offline experience after login:
 * cached events, the logged-in profile, registered (bookmarked) event ids, chat
 * announcements and the user's recent AI queries (for offline recommendations).
 *
 * Backed by a single JSON file in the app's private files dir — no database, so the
 * app keeps working with zero internet AND zero backend running.
 */
object OfflineStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private const val FILE_NAME = "offline_cache.json"

    @Volatile private var data: OfflineCacheData = OfflineCacheData()
    @Volatile private var file: File? = null
    private val ready = CompletableDeferred<Unit>()

    /**
     * Points the store at its file and parses any existing cache on a background
     * thread — reading the JSON off the main thread keeps startup smooth. Until the
     * parse finishes, reads fall back to the embedded catalogue.
     */
    fun init(context: Context) {
        AppContext.install(context)
        synchronized(this) {
            if (file != null) return
            val target = File(context.applicationContext.filesDir, FILE_NAME)
            file = target
            Thread({
                try {
                    data = load(target)
                } finally {
                    ready.complete(Unit)
                }
            }, "offline-store-load").apply { isDaemon = true }.start()
        }
    }

    /** Suspends until the background cache parse has completed. No-op if never initialised. */
    suspend fun awaitReady() {
        if (file == null) return
        ready.await()
    }

    private fun load(f: File): OfflineCacheData =
        try {
            if (f.exists()) {
                json.decodeFromString(OfflineCacheData.serializer(), f.readText())
            } else {
                OfflineCacheData(events = offlineEvents)
            }
        } catch (e: Exception) {
            OfflineCacheData(events = offlineEvents)
        }

    private fun persist() {
        val f = file ?: return
        try {
            f.writeText(json.encodeToString(OfflineCacheData.serializer(), data))
        } catch (e: Exception) {
            // Cache write failure must never crash the app — worst case the cache is stale.
        }
    }

    // ---- events ----

    @Synchronized
    fun cacheEvents(events: List<Event>) {
        if (events.isEmpty()) return
        data = data.copy(events = events)
        persist()
    }

    @Synchronized
    fun upsertEvent(event: Event) {
        val cleaned = data.events.filterNot { it.id == event.id } + event
        data = data.copy(events = cleaned)
        persist()
    }

    @Synchronized
    fun allEvents(): List<Event> = data.events.ifEmpty { offlineEvents }

    @Synchronized
    fun event(id: String): Event? = data.events.firstOrNull { it.id == id } ?: offlineEvents.firstOrNull { it.id == id }

    // ---- registered (tracked) events ----

    @Synchronized
    fun register(id: String): Boolean {
        if (data.registered.contains(id)) return false
        data = data.copy(registered = data.registered + id)
        persist()
        return true
    }

    @Synchronized
    fun unregister(id: String) {
        data = data.copy(registered = data.registered.filterNot { it == id })
        persist()
    }

    /** Replaces the local set with the server's authoritative bookmark list. */
    @Synchronized
    fun replaceRegistered(ids: List<String>) {
        data = data.copy(registered = ids)
        persist()
    }

    // ---- outbox (offline bookmark / interest edits) ----

    /** Applies the edit locally right away and queues it for replay to the server. */
    @Synchronized
    fun queueBookmark(eventId: String, add: Boolean) {
        val op = PendingBookmark(eventId, add)
        data = data.copy(
            pendingBookmarks = Outbox.enqueue(data.pendingBookmarks, op),
            registered = Outbox.mergeBookmarks(data.registered, listOf(op))
        )
        persist()
    }

    @Synchronized
    fun pendingBookmarks(): List<PendingBookmark> = data.pendingBookmarks

    @Synchronized
    fun ackBookmark(op: PendingBookmark) {
        data = data.copy(pendingBookmarks = Outbox.ack(data.pendingBookmarks, op))
        persist()
    }

    /** Server bookmark list with any still-pending local edits layered on top. */
    @Synchronized
    fun mergePending(server: List<String>): List<String> =
        Outbox.mergeBookmarks(server, data.pendingBookmarks)

    @Synchronized
    fun queueInterests(interests: List<String>) {
        data = data.copy(pendingInterests = interests, user = data.user?.copy(interests = interests))
        persist()
    }

    @Synchronized
    fun pendingInterests(): List<String>? = data.pendingInterests

    /** Clears the pending interests only if they have not been replaced by a newer edit. */
    @Synchronized
    fun ackInterests(sent: List<String>) {
        if (data.pendingInterests == sent) {
            data = data.copy(pendingInterests = null)
            persist()
        }
    }

    @Synchronized
    fun clearOutbox() {
        data = data.copy(pendingBookmarks = emptyList(), pendingInterests = null)
        persist()
    }

    @Synchronized
    fun isRegistered(id: String): Boolean = data.registered.contains(id)

    @Synchronized
    fun registeredIds(): List<String> = data.registered

    // ---- profile ----

    @Synchronized
    fun clearRegistered() {
        data = data.copy(registered = emptyList(), pendingBookmarks = emptyList(), pendingInterests = null)
        persist()
    }

    @Synchronized
    fun setUser(user: UserProfile?) {
        data = data.copy(user = user)
        persist()
    }

    @Synchronized
    fun user(): UserProfile? = data.user

    // ---- campus map ----

    @Synchronized
    fun cacheCampusMap(map: CampusMapResponse) {
        if (map.venues.isEmpty()) return
        data = data.copy(campusMap = map)
        persist()
    }

    /** Last server map, or null if none was ever cached. */
    @Synchronized
    fun cachedCampusMap(): CampusMapResponse? = data.campusMap

    // ---- announcements ----

    @Synchronized
    fun cacheAnnouncements(list: List<Announcement>) {
        if (list.isEmpty()) return
        val byId = (data.announcements + list).distinctBy { it.id to it.message }
        data = data.copy(announcements = byId)
        persist()
    }

    @Synchronized
    fun announcements(): List<Announcement> = data.announcements.ifEmpty { offlineAnnouncements }

    // ---- recent AI queries (personalization signal) ----

    @Synchronized
    fun addRecentQuery(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val updated = (listOf(q) + data.recentQueries.filterNot { it.equals(q, ignoreCase = true) }).take(5)
        data = data.copy(recentQueries = updated)
        persist()
    }

    @Synchronized
    fun recentQueries(): List<String> = data.recentQueries
}