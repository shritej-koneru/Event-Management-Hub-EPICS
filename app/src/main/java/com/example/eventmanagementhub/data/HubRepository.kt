package com.example.eventmanagementhub.data

import com.example.eventmanagementhub.data.api.ApiClient
import com.example.eventmanagementhub.data.api.AuthProvider
import com.example.eventmanagementhub.data.api.HubApi
import com.example.eventmanagementhub.data.model.AiSearchRequest
import com.example.eventmanagementhub.data.model.AiSearchResponse
import com.example.eventmanagementhub.data.model.Announcement
import com.example.eventmanagementhub.data.model.AnnouncementsResponse
import com.example.eventmanagementhub.data.model.AuthResponse
import com.example.eventmanagementhub.data.model.BookmarkMutationResponse
import com.example.eventmanagementhub.data.model.CampusMapResponse
import com.example.eventmanagementhub.data.model.ChatMessagesResponse
import com.example.eventmanagementhub.data.model.ErrorBody
import com.example.eventmanagementhub.data.model.Event
import com.example.eventmanagementhub.data.model.EventChart
import com.example.eventmanagementhub.data.model.EventDetailsResponse
import com.example.eventmanagementhub.data.model.EventNavigation
import com.example.eventmanagementhub.data.model.EventStatistics
import com.example.eventmanagementhub.data.model.EventVenue
import com.example.eventmanagementhub.data.model.InterestsRequest
import com.example.eventmanagementhub.data.model.LoginRequest
import com.example.eventmanagementhub.data.model.PathRequest
import com.example.eventmanagementhub.data.model.PathResponse
import com.example.eventmanagementhub.data.model.RecommendationResponse
import com.example.eventmanagementhub.data.model.RegisterRequest
import com.example.eventmanagementhub.data.model.UserProfile
import com.example.eventmanagementhub.data.model.VenueMapResponse
import com.example.eventmanagementhub.data.model.VenuesResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import retrofit2.Response

/**
 * Outcome of a repository call.
 *
 * `isStale` marks a success that was served from the on-device cache because the
 * backend was unreachable. The UI must label this — silently showing cached data
 * as if it were live was the defect this flag exists to make visible.
 */
sealed interface Result<out T> {
    data class Success<T>(val data: T, val isStale: Boolean = false) : Result<T>
    data class Error(
        val message: String,
        val httpCode: Int? = null,
        val isAuthError: Boolean = false
    ) : Result<Nothing>
}

/** Aggregate Event Details payload assembled from one API call or the offline cache. */
data class EventDetails(
    val event: Event,
    val venue: EventVenue? = null,
    val statistics: EventStatistics = EventStatistics(),
    val charts: List<EventChart> = emptyList(),
    val announcements: List<Announcement> = emptyList(),
    val navigation: EventNavigation? = null
)

/**
 * Network-first data access.
 *
 * Every call attempts the live API. On success the result is returned fresh and
 * the offline cache is refreshed. When the backend is unreachable the cached (or
 * embedded) data is returned **flagged as stale** so screens can tell the user,
 * rather than passing it off as live.
 */
class HubRepository(
    private val api: HubApi = ApiClient.service
) {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var cachedUser: UserProfile? = null

    // ---- shared response plumbing -------------------------------------------------

    /** Parses the backend's flat `{ error, code }` body so messages are never raw JSON. */
    private fun errorMessageOf(resp: Response<*>, fallback: String): String {
        val raw = runCatching { resp.errorBody()?.string() }.getOrNull()
        if (raw.isNullOrBlank()) return fallback
        val parsed = runCatching { json.decodeFromString<ErrorBody>(raw) }.getOrNull()
        return parsed?.error?.takeIf { it.isNotBlank() } ?: fallback
    }

    /** Turns a non-2xx response into a typed error, expiring the session on 401. */
    private fun failure(resp: Response<*>, fallback: String): Result.Error {
        val isAuth = resp.code() == 401
        if (isAuth) {
            AuthProvider.onUnauthorized()
            AppContext.app?.let { ctx -> sessionScope.launch { SessionStore.clear(ctx) } }
        }
        return Result.Error(errorMessageOf(resp, fallback), resp.code(), isAuth)
    }

    private fun Result.Error.propagate(): Result<Nothing> = this

    // ---- auth (login/register stay online; the session is persisted on device) ----

    suspend fun register(name: String, email: String, password: String): Result<AuthResponse> =
        withContext(Dispatchers.IO) {
            try {
                val resp = api.register(RegisterRequest(name, email, password))
                val body = resp.body()
                if (resp.isSuccessful && body != null) {
                    persistSession(body)
                    Result.Success(body)
                } else failure(resp, "Register failed").propagate()
            } catch (e: Exception) {
                Result.Error(e.message ?: "Network error")
            }
        }

    suspend fun login(email: String, password: String): Result<AuthResponse> =
        withContext(Dispatchers.IO) {
            try {
                val resp = api.login(LoginRequest(email, password))
                val body = resp.body()
                if (resp.isSuccessful && body != null) {
                    persistSession(body)
                    Result.Success(body)
                } else failure(resp, "Login failed").propagate()
            } catch (e: Exception) {
                Result.Error(e.message ?: "Network error")
            }
        }

    private fun persistSession(resp: AuthResponse) {
        AuthProvider.token = resp.token
        cachedUser = resp.user
        OfflineStore.setUser(resp.user)
    }

    /** Restores the persisted user profile (token is restored separately via DataStore). */
    fun restoreSession() {
        cachedUser = OfflineStore.user()
    }

    /** Signs the local profile out: drops the auth token and the on-device profile state. */
    fun logout() {
        AuthProvider.token = null
        cachedUser = null
        OfflineStore.setUser(null)
        OfflineStore.clearRegistered()
    }

    suspend fun setToken(token: String?) {
        AuthProvider.token = token
    }

    // ---- events -------------------------------------------------------------------

    /**
     * Fetches events. On a network failure the cached/embedded catalogue is
     * returned with `isStale = true` so the UI can say so out loud.
     */
    suspend fun events(category: String? = null, query: String? = null): Result<List<Event>> =
        withContext(Dispatchers.IO) {
            OfflineStore.awaitReady()
            try {
                val resp = api.events(category, q = query)
                if (resp.isSuccessful) {
                    val fresh = resp.body()?.events.orEmpty()
                    // A full unfiltered fetch refreshes the catalogue cache.
                    if (category == null && query == null && fresh.isNotEmpty()) OfflineStore.cacheEvents(fresh)
                    Result.Success(if (fresh.isEmpty()) offlineFiltered(category, query) else fresh)
                } else failure(resp, "Could not load events").propagate()
            } catch (e: Exception) {
                Result.Success(offlineFiltered(category, query), isStale = true)
            }
        }

    /** Mirrors the backend /events filter for title/desc/host/category/tags + exact category. */
    private fun offlineFiltered(category: String?, query: String?): List<Event> {
        val needle = query?.trim()?.lowercase()
        return OfflineStore.allEvents().filter { e ->
            (category == null || e.category == category) &&
                (needle == null ||
                    listOf(e.title, e.description, e.host, e.category).plus(e.tags)
                        .joinToString(" ").lowercase().contains(needle))
        }
    }

    suspend fun event(id: String): Result<Event?> = withContext(Dispatchers.IO) {
        OfflineStore.awaitReady()
        try {
            val resp = api.event(id)
            if (resp.isSuccessful) {
                val e = resp.body()?.event
                if (e != null) OfflineStore.upsertEvent(e)
                Result.Success(e ?: OfflineStore.event(id))
            } else failure(resp, "Could not load event").propagate()
        } catch (e: Exception) {
            Result.Success(OfflineStore.event(id), isStale = true)
        }
    }

    /**
     * Event Details aggregate. Falls back to a cache-composed payload so the screen
     * keeps working offline, again flagged stale.
     */
    suspend fun eventDetails(id: String, from: String? = null): Result<EventDetails> =
        withContext(Dispatchers.IO) {
            OfflineStore.awaitReady()
            try {
                val resp = api.eventDetails(id, from)
                val body = resp.body()
                if (resp.isSuccessful && body != null) {
                    val details = body.toDomain()
                    details?.let {
                        it.event.let { ev -> OfflineStore.upsertEvent(ev) }
                        OfflineStore.cacheAnnouncements(body.announcements)
                        return@withContext Result.Success(it)
                    }
                    failure(resp, "Could not load event details").propagate()
                } else failure(resp, "Could not load event details").propagate()
            } catch (e: Exception) {
                val cached = OfflineStore.event(id)
                    ?.let { OfflineEngine.details(it, from, OfflineStore.announcements()) }
                if (cached != null) Result.Success(cached, isStale = true)
                else Result.Error(e.message ?: "Event not found")
            }
        }

    private fun EventDetailsResponse.toDomain(): EventDetails? {
        val ev = event ?: return null
        return EventDetails(
            event = ev,
            venue = venue,
            statistics = statistics ?: EventStatistics(),
            charts = charts,
            announcements = announcements,
            navigation = navigation
        )
    }

    // ---- AI -----------------------------------------------------------------------

    suspend fun aiSearch(query: String): Result<AiSearchResponse> = withContext(Dispatchers.IO) {
        OfflineStore.awaitReady()
        try {
            val resp = api.aiSearch(AiSearchRequest(query))
            val body = resp.body()
            if (resp.isSuccessful && body != null) {
                OfflineStore.addRecentQuery(query)
                Result.Success(body)
            } else failure(resp, "Search failed").propagate()
        } catch (e: Exception) {
            Result.Success(OfflineEngine.search(query), isStale = true)
        }
    }

    suspend fun recommendations(): Result<RecommendationResponse> = withContext(Dispatchers.IO) {
        OfflineStore.awaitReady()
        try {
            val resp = api.recommendations()
            val body = resp.body()
            if (resp.isSuccessful && body != null) Result.Success(body)
            else failure(resp, "Login required").propagate()
        } catch (e: Exception) {
            if (OfflineStore.user() != null) Result.Success(OfflineEngine.recommendations(), isStale = true)
            else Result.Error("Login required", 401)
        }
    }

    // ---- navigation ---------------------------------------------------------------

    suspend fun venues(): Result<VenuesResponse> = withContext(Dispatchers.IO) {
        try {
            val resp = api.venues()
            val body = resp.body()
            if (resp.isSuccessful && body != null) Result.Success(body)
            else failure(resp, "Could not load venues").propagate()
        } catch (e: Exception) {
            Result.Success(VenuesResponse(venues = offlineVenues), isStale = true)
        }
    }

    suspend fun campusMap(): Result<CampusMapResponse> = withContext(Dispatchers.IO) {
        try {
            val resp = api.campusMap()
            val body = resp.body()
            if (resp.isSuccessful && body != null) Result.Success(body)
            else failure(resp, "Could not load campus map").propagate()
        } catch (e: Exception) {
            Result.Success(offlineCampusMap(), isStale = true)
        }
    }

    suspend fun venueMap(venueRef: String): Result<VenueMapResponse> = withContext(Dispatchers.IO) {
        try {
            val resp = api.venueMap(venueRef)
            val body = resp.body()
            if (resp.isSuccessful && body != null) Result.Success(body)
            else failure(resp, "Could not load venue map").propagate()
        } catch (e: Exception) {
            val offline = offlineVenueMap(venueRef)
            if (offline != null) Result.Success(offline, isStale = true)
            else Result.Error("Unknown venue: $venueRef", 400)
        }
    }

    suspend fun path(from: String, to: String): Result<PathResponse> = withContext(Dispatchers.IO) {
        try {
            val resp = api.path(PathRequest(from, to))
            val body = resp.body()
            if (resp.isSuccessful && body != null) Result.Success(body)
            else failure(resp, "No path found").propagate()
        } catch (e: Exception) {
            runCatching { Result.Success(OfflineEngine.path(from, to), isStale = true) as Result<PathResponse> }
                .getOrElse { ex -> Result.Error(ex.message ?: "No path", 400) }
        }
    }

    // ---- chat announcements --------------------------------------------------------

    suspend fun announcements(eventId: String? = null): Result<AnnouncementsResponse> =
        withContext(Dispatchers.IO) {
            OfflineStore.awaitReady()
            try {
                val resp = api.announcements(eventId)
                val body = resp.body()
                if (resp.isSuccessful && body != null) {
                    val list = body.announcements
                    if (list.isNotEmpty()) OfflineStore.cacheAnnouncements(list)
                    Result.Success(AnnouncementsResponse(list))
                } else failure(resp, "Could not load announcements").propagate()
            } catch (e: Exception) {
                val cached = if (eventId != null) {
                    OfflineStore.announcements().filter { it.eventId == eventId }
                } else OfflineStore.announcements()
                Result.Success(AnnouncementsResponse(cached), isStale = true)
            }
        }

    /**
     * Persisted chat history (GET /api/chat/messages). A failed request never
     * replaces the caller's existing thread with an empty list — the error is
     * returned so the UI can keep what it already has.
     */
    suspend fun chatMessages(
        room: String? = null,
        before: String? = null,
        limit: Int? = null
    ): Result<ChatMessagesResponse> = withContext(Dispatchers.IO) {
        try {
            val resp = api.chatMessages(room = room, before = before, limit = limit)
            val body = resp.body()
            if (resp.isSuccessful && body != null) Result.Success(body)
            else failure(resp, "Could not load chat history").propagate()
        } catch (e: Exception) {
            Result.Error(e.message ?: "Could not load chat history", 0)
        }
    }

    // ---- bookmarks: server is the source of truth, mirrored on device ---------------

    suspend fun bookmarks(): Result<List<String>> = withContext(Dispatchers.IO) {
        OfflineStore.awaitReady()
        try {
            val resp = api.bookmarks()
            val body = resp.body()
            if (resp.isSuccessful && body != null) {
                val ids = body.bookmarks
                OfflineStore.replaceRegistered(ids)
                cachedUser = cachedUser?.copy(bookmarks = ids)
                Result.Success(ids)
            } else failure(resp, "Could not load bookmarks").propagate()
        } catch (e: Exception) {
            Result.Success(OfflineStore.registeredIds(), isStale = true)
        }
    }

    /** Adds/removes a server bookmark and mirrors the authoritative result on device. */
    suspend fun syncBookmark(eventId: String, bookmarked: Boolean): Result<BookmarkMutationResponse> =
        withContext(Dispatchers.IO) {
            OfflineStore.awaitReady()
            try {
                val resp = if (bookmarked) api.addBookmark(eventId) else api.removeBookmark(eventId)
                val body = resp.body()
                if (resp.isSuccessful && body != null) {
                    OfflineStore.replaceRegistered(body.bookmarks)
                    cachedUser = cachedUser?.copy(bookmarks = body.bookmarks)
                    Result.Success(body)
                } else failure(resp, "Could not update bookmark").propagate()
            } catch (e: Exception) {
                // Offline: apply locally so the tap still feels responsive.
                if (bookmarked) OfflineStore.register(eventId) else OfflineStore.unregister(eventId)
                cachedUser = cachedUser?.copy(bookmarks = OfflineStore.registeredIds())
                Result.Error(e.message ?: "Saved on device only")
            }
        }

    suspend fun updateInterests(interests: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val resp = api.updateInterests(InterestsRequest(interests))
            if (resp.isSuccessful) {
                cachedUser = cachedUser?.copy(interests = interests)
                OfflineStore.setUser(cachedUser)
                Result.Success(Unit)
            } else failure(resp, "Could not save interests").propagate()
        } catch (e: Exception) {
            cachedUser = cachedUser?.copy(interests = interests)
            OfflineStore.setUser(cachedUser)
            Result.Error(e.message ?: "Saved on device only")
        }
    }

    // ---- tracked (registered) events -------------------------------------------------

    fun isRegistered(id: String): Boolean = OfflineStore.isRegistered(id)

    fun registerToEvent(id: String) {
        OfflineStore.register(id)
    }

    fun unregisterFromEvent(id: String) {
        OfflineStore.unregister(id)
    }

    fun registeredIds(): List<String> = OfflineStore.registeredIds()

    fun loggedInUser(): UserProfile? = cachedUser ?: OfflineStore.user()
}