package com.example.eventmanagementhub.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Event(
    val id: String,
    val title: String,
    val category: String,
    val venue: String,
    val venueId: String,
    val date: String,
    val time: String,
    val durationMin: Int = 60,
    val capacity: Int = 0,
    val registered: Int = 0,
    val host: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val icon: String = "\uD83C\uDFAB"
)

@Serializable
data class EventListResponse(val events: List<Event> = emptyList())

@Serializable
data class EventResponse(val event: Event? = null)

@Serializable
data class RegisterRequest(
    val name: String,
    val email: String,
    val password: String,
    val interests: List<String> = emptyList()
)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class UserProfile(
    val id: String = "",
    val name: String = "",
    val email: String = "",
    val interests: List<String> = emptyList(),
    val bookmarks: List<String> = emptyList()
)

@Serializable
data class AuthResponse(val token: String, val user: UserProfile)

/** GET /api/auth/me -> 200 { user } */
@Serializable
data class UserResponse(val user: UserProfile? = null)

/** GET /api/auth/me/bookmarks -> 200 { bookmarks } */
@Serializable
data class BookmarksResponse(val bookmarks: List<String> = emptyList())

/** POST/DELETE /api/auth/me/bookmarks/:eventId -> 200 { bookmarked, eventId, bookmarks } */
@Serializable
data class BookmarkMutationResponse(
    val bookmarked: Boolean = false,
    val eventId: String = "",
    val bookmarks: List<String> = emptyList()
)

@Serializable
data class InterestsRequest(val interests: List<String> = emptyList())

@Serializable
data class AiSearchRequest(val query: String, val limit: Int = 10)

@Serializable
data class AiResult(val event: Event, val score: Double, val reason: String)

@Serializable
data class AiSearchResponse(val query: String = "", val results: List<AiResult> = emptyList())

@Serializable
data class RecommendationResponse(val recommendations: List<AiResult> = emptyList())

@Serializable
data class Venue(
    val id: String,
    val name: String,
    val neighbors: List<String> = emptyList()
)

@Serializable
data class VenuesResponse(val venues: List<Venue> = emptyList())

@Serializable
data class PathRequest(val from: String, val to: String)

@Serializable
data class PathNode(val id: String, val name: String)

@Serializable
data class PathStep(
    val node: String,
    val instruction: String,
    val distanceM: Int = 0,
    /** Backend supplies the display name; optional so offline steps still decode. */
    val name: String = ""
)

@Serializable
data class PathResponse(
    val nodes: List<PathNode> = emptyList(),
    val steps: List<PathStep> = emptyList(),
    val totalDistanceM: Int = 0
)

@Serializable
data class Announcement(
    val id: Long = 0,
    val room: String = "",
    val message: String = "",
    val at: String = "",
    /** Null for venue-wide notices; set when the notice is about one event. */
    val eventId: String? = null
)

@Serializable
data class AnnouncementsResponse(val announcements: List<Announcement> = emptyList())

/**
 * One persisted BitChat row from GET /api/chat/messages or the Socket.IO
 * `chat:message` payload (same wire shape).
 */
@Serializable
data class ChatMessage(
    val id: Long = 0,
    val author: String = "",
    val text: String = "",
    val room: String? = null,
    val bot: Boolean = false,
    val ts: String = ""
)

@Serializable
data class ChatMessagesResponse(val messages: List<ChatMessage> = emptyList())

/** Every backend failure leaves the server as `{ error, code }` per the contract. */
@Serializable
data class ErrorBody(val error: String? = null, val code: String? = null)

// ---------------------------------------------------------------------------
// Event Details aggregate — GET /api/events/{id}/details
// ---------------------------------------------------------------------------

/** Venue as returned inside the details payload (superset of Venue). */
@Serializable
data class EventVenue(
    val id: String = "",
    val name: String = "",
    val navigationNode: String? = null,
    val neighbors: List<String> = emptyList()
)

@Serializable
data class EventStatistics(
    val capacity: Int = 0,
    val registered: Int = 0,
    val seatsRemaining: Int = 0,
    val occupancyPercent: Int = 0,
    val durationMin: Int = 0,
    val announcementCount: Int = 0,
    val bookmarkCount: Int = 0,
    val isBookmarked: Boolean = false,
    val categoryEventCount: Int = 0,
    val totalEvents: Int = 0,
    val isPast: Boolean = false,
    val daysUntilEvent: Int? = null
)

@Serializable
data class ChartSlice(val label: String = "", val value: Double = 0.0)

@Serializable
data class EventChart(
    val id: String = "",
    val title: String = "",
    val type: String = "",
    val unit: String = "",
    val total: Double = 0.0,
    val data: List<ChartSlice> = emptyList()
)

@Serializable
data class EventNavigation(
    val from: String = "",
    val nodes: List<PathNode> = emptyList(),
    val steps: List<PathStep> = emptyList(),
    val totalDistanceM: Int = 0
)

@Serializable
data class EventDetailsResponse(
    val event: Event? = null,
    val venue: EventVenue? = null,
    val statistics: EventStatistics? = null,
    val charts: List<EventChart> = emptyList(),
    val announcements: List<Announcement> = emptyList(),
    val navigation: EventNavigation? = null
)