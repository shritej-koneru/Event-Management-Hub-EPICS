package com.example.eventmanagementhub.data

import com.example.eventmanagementhub.data.api.ApiClient
import com.example.eventmanagementhub.data.api.AuthProvider
import com.example.eventmanagementhub.data.api.HubApi
import com.example.eventmanagementhub.data.model.AiSearchFilters
import com.example.eventmanagementhub.data.model.AiSearchRequest
import com.example.eventmanagementhub.data.model.AiSearchResponse
import com.example.eventmanagementhub.data.model.Announcement
import com.example.eventmanagementhub.data.model.AnnouncementsResponse
import com.example.eventmanagementhub.data.model.AuthResponse
import com.example.eventmanagementhub.data.model.BookmarkMutationResponse
import com.example.eventmanagementhub.data.model.CampusMapResponse
import com.example.eventmanagementhub.data.model.CreateTicketRequest
import com.example.eventmanagementhub.data.model.CreateTicketResponse
import com.example.eventmanagementhub.data.model.ErrorBody
import com.example.eventmanagementhub.data.model.SendMessageRequest
import com.example.eventmanagementhub.data.model.SupportMessage
import com.example.eventmanagementhub.data.model.SupportTicket
import com.example.eventmanagementhub.data.model.TicketMessagesResponse
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
        val isAuthError: Boolean = false,
        /** Machine-readable backend code (e.g. NOT_REGISTERED), when the server sent one. */
        val code: String? = null
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
        // A 401 only means "session expired" when we actually presented a token.
        // Wrong-password login/register (no Authorization header) is a plain error.
        val path = resp.raw().request.url.encodedPath
        val expired = isAuth &&
            resp.raw().request.header("Authorization") != null &&
            !path.endsWith("/auth/login") && !path.endsWith("/auth/register")
        if (expired) {
            AuthProvider.onUnauthorized()
            AppContext.app?.let { ctx -> sessionScope.launch { SessionStore.clear(ctx) } }
        }
        return Result.Error(errorMessageOf(resp, fallback), resp.code(), expired)
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
        // Queued edits belong to whoever made them; never replay them as another user.
        if (OfflineStore.user()?.id != resp.user.id) OfflineStore.clearOutbox()
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

    /**
     * [filters] are explicit and strict, so they only work against the server: offline we return an error instead of
     * an unfiltered list that would look filtered.
     */
    suspend fun aiSearch(query: String, filters: AiSearchFilters? = null): Result<AiSearchResponse> = withContext(Dispatchers.IO) {
        OfflineStore.awaitReady()
        try {
            val resp = api.aiSearch(AiSearchRequest(query, filters = filters?.takeUnless { it.isEmpty() }))
            val body = resp.body()
            if (resp.isSuccessful && body != null) {
                OfflineStore.addRecentQuery(query)
                Result.Success(body)
            } else failure(resp, "Search failed").propagate()
        } catch (e: Exception) {
            if (filters != null && !filters.isEmpty()) Result.Error("Filters need a connection.", null)
            else Result.Success(OfflineEngine.search(query), isStale = true)
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
            val cached = OfflineStore.cachedCampusMap()
            Result.Success(
                VenuesResponse(venues = cached?.let { venuesFromMap(it) } ?: offlineVenues),
                isStale = true
            )
        }
    }

    suspend fun campusMap(): Result<CampusMapResponse> = withContext(Dispatchers.IO) {
        try {
            val resp = api.campusMap()
            val body = resp.body()
            if (resp.isSuccessful && body != null) {
                OfflineStore.cacheCampusMap(body)
                Result.Success(body)
            } else failure(resp, "Could not load campus map").propagate()
        } catch (e: Exception) {
            Result.Success(OfflineStore.cachedCampusMap() ?: offlineCampusMap(), isStale = true)
        }
    }

    suspend fun venueMap(venueRef: String): Result<VenueMapResponse> = withContext(Dispatchers.IO) {
        try {
            val resp = api.venueMap(venueRef)
            val body = resp.body()
            if (resp.isSuccessful && body != null) Result.Success(body)
            else failure(resp, "Could not load venue map").propagate()
        } catch (e: Exception) {
            val offline = OfflineStore.cachedCampusMap()?.let { venueMapFromMap(it, venueRef) }
                ?: offlineVenueMap(venueRef)
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

    // ---- event announcements + help desk (contract 1.6.0) ---------------------------

    /** Error for a support/announcement call: keeps 401 expiry handling, maps codes to readable text. */
    private fun supportFailure(resp: Response<*>, fallback: String): Result.Error {
        val base = failure(resp, fallback)
        if (base.isAuthError) return base
        val raw = runCatching { resp.errorBody()?.string() }.getOrNull()
        val parsed = raw?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString<ErrorBody>(it) }.getOrNull() }
        return base.copy(
            message = SupportErrors.message(resp.code(), parsed?.code, parsed?.error, fallback),
            code = parsed?.code
        )
    }

    private fun supportNetworkError(): Result.Error = Result.Error(SupportErrors.NETWORK_MESSAGE, 0)

    /** GET /api/events/:id/announcements — global notices included (`global = true`). */
    suspend fun eventAnnouncements(eventId: String): Result<List<Announcement>> =
        withContext(Dispatchers.IO) {
            try {
                val resp = api.eventAnnouncements(eventId)
                val body = resp.body()
                if (resp.isSuccessful && body != null) Result.Success(body.announcements)
                else supportFailure(resp, "Could not load announcements")
            } catch (e: Exception) {
                supportNetworkError()
            }
        }

    suspend fun supportTickets(eventId: String? = null): Result<List<SupportTicket>> =
        withContext(Dispatchers.IO) {
            try {
                val resp = api.supportTickets(eventId)
                val body = resp.body()
                if (resp.isSuccessful && body != null) Result.Success(body.tickets)
                else supportFailure(resp, "Could not load help chats")
            } catch (e: Exception) {
                supportNetworkError()
            }
        }

    suspend fun createTicket(
        eventId: String,
        subject: String,
        message: String,
        clientId: String
    ): Result<CreateTicketResponse> = withContext(Dispatchers.IO) {
        try {
            val resp = api.createTicket(CreateTicketRequest(eventId, subject.trim(), message.trim(), clientId))
            val body = resp.body()
            if (resp.isSuccessful && body?.ticket != null) Result.Success(body)
            else supportFailure(resp, "Could not start the chat")
        } catch (e: Exception) {
            supportNetworkError()
        }
    }

    suspend fun ticketMessages(ticketId: String): Result<TicketMessagesResponse> =
        withContext(Dispatchers.IO) {
            try {
                val resp = api.ticketMessages(ticketId)
                val body = resp.body()
                if (resp.isSuccessful && body != null) Result.Success(body)
                else supportFailure(resp, "Could not load the chat")
            } catch (e: Exception) {
                supportNetworkError()
            }
        }

    /** Retry-safe: resending the same [clientId] returns the already-stored message. */
    suspend fun sendTicketMessage(
        ticketId: String,
        body: String,
        clientId: String
    ): Result<SupportMessage> = withContext(Dispatchers.IO) {
        try {
            val resp = api.sendTicketMessage(ticketId, SendMessageRequest(body.trim(), clientId))
            val msg = resp.body()?.message
            if (resp.isSuccessful && msg != null) Result.Success(msg)
            else supportFailure(resp, "Message not sent")
        } catch (e: Exception) {
            supportNetworkError()
        }
    }

    // ---- bookmarks / interests: server is the source of truth, edits are queued ------

    private class FlushResult(
        val drained: Boolean,
        val lastBookmark: BookmarkMutationResponse? = null,
        val authError: Result.Error? = null
    )

    /**
     * Replays queued bookmark ops (in order) and the pending interests edit. Each op is
     * dropped once the server acks it (or permanently rejects it). Stops at the first
     * network failure/5xx so ordering is kept; a 401 expires the session and keeps the queue.
     */
    private suspend fun flushOutbox(): FlushResult {
        var last: BookmarkMutationResponse? = null
        var authError: Result.Error? = null
        val replay = Outbox.replay(OfflineStore.pendingBookmarks()) { op ->
            try {
                val resp = if (op.add) api.addBookmark(op.eventId) else api.removeBookmark(op.eventId)
                when {
                    resp.isSuccessful -> {
                        last = resp.body() ?: last
                        OfflineStore.ackBookmark(op)
                        SendOutcome.ACKED
                    }
                    resp.code() == 401 -> {
                        authError = failure(resp, "Could not update bookmark")
                        SendOutcome.RETRY_LATER
                    }
                    resp.code() in 400..499 -> {
                        OfflineStore.ackBookmark(op)
                        SendOutcome.REJECTED
                    }
                    else -> SendOutcome.RETRY_LATER
                }
            } catch (e: Exception) {
                SendOutcome.RETRY_LATER
            }
        }
        var drained = replay.drained
        val pendingInterests = OfflineStore.pendingInterests()
        if (drained && pendingInterests != null) {
            try {
                val resp = api.updateInterests(InterestsRequest(pendingInterests))
                when {
                    resp.isSuccessful || resp.code() in 400..499 && resp.code() != 401 ->
                        OfflineStore.ackInterests(pendingInterests)
                    resp.code() == 401 -> {
                        authError = failure(resp, "Could not save interests")
                        drained = false
                    }
                    else -> drained = false
                }
            } catch (e: Exception) {
                drained = false
            }
        }
        return FlushResult(drained, last, authError)
    }

    suspend fun bookmarks(): Result<List<String>> = withContext(Dispatchers.IO) {
        OfflineStore.awaitReady()
        try {
            flushOutbox().authError?.let { return@withContext it }
            val resp = api.bookmarks()
            val body = resp.body()
            if (resp.isSuccessful && body != null) {
                // Edits the server has not acked yet must survive the overwrite.
                val ids = OfflineStore.mergePending(body.bookmarks)
                OfflineStore.replaceRegistered(ids)
                cachedUser = cachedUser?.copy(bookmarks = ids)
                Result.Success(ids)
            } else failure(resp, "Could not load bookmarks").propagate()
        } catch (e: Exception) {
            Result.Success(OfflineStore.registeredIds(), isStale = true)
        }
    }

    /**
     * Queues the bookmark edit (applied locally at once), then tries to replay the queue.
     * If the server is unreachable the edit stays queued and is sent on a later call.
     */
    suspend fun syncBookmark(eventId: String, bookmarked: Boolean): Result<BookmarkMutationResponse> =
        withContext(Dispatchers.IO) {
            OfflineStore.awaitReady()
            OfflineStore.queueBookmark(eventId, bookmarked)
            cachedUser = cachedUser?.copy(bookmarks = OfflineStore.registeredIds())
            val flush = flushOutbox()
            flush.authError?.let { return@withContext it }
            val body = flush.lastBookmark
            val stillPending = OfflineStore.pendingBookmarks().any { it.eventId == eventId }
            if (!stillPending && body != null) {
                val ids = OfflineStore.mergePending(body.bookmarks)
                OfflineStore.replaceRegistered(ids)
                cachedUser = cachedUser?.copy(bookmarks = ids)
                Result.Success(body)
            } else {
                Result.Error("Saved on device; will sync when online")
            }
        }

    suspend fun updateInterests(interests: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        OfflineStore.awaitReady()
        OfflineStore.queueInterests(interests)
        cachedUser = cachedUser?.copy(interests = interests)
        val flush = flushOutbox()
        flush.authError?.let { return@withContext it }
        if (OfflineStore.pendingInterests() == null) Result.Success(Unit)
        else Result.Error("Saved on device; will sync when online")
    }

    // ---- tracked (registered) events -------------------------------------------------

    fun isRegistered(id: String): Boolean = OfflineStore.isRegistered(id)

    fun unregisterFromEvent(id: String) {
        OfflineStore.unregister(id)
    }

    fun registeredIds(): List<String> = OfflineStore.registeredIds()

    fun loggedInUser(): UserProfile? = cachedUser ?: OfflineStore.user()
}