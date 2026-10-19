package com.example.eventmanagementhub.data.api

import com.example.eventmanagementhub.data.model.AiSearchRequest
import com.example.eventmanagementhub.data.model.AiSearchResponse
import com.example.eventmanagementhub.data.model.AnnouncementsResponse
import com.example.eventmanagementhub.data.model.AuthResponse
import com.example.eventmanagementhub.data.model.BookmarkMutationResponse
import com.example.eventmanagementhub.data.model.BookmarksResponse
import com.example.eventmanagementhub.data.model.CampusMapResponse
import com.example.eventmanagementhub.data.model.ChatMessagesResponse
import com.example.eventmanagementhub.data.model.EventDetailsResponse
import com.example.eventmanagementhub.data.model.EventListResponse
import com.example.eventmanagementhub.data.model.EventResponse
import com.example.eventmanagementhub.data.model.InterestsRequest
import com.example.eventmanagementhub.data.model.LoginRequest
import com.example.eventmanagementhub.data.model.PathRequest
import com.example.eventmanagementhub.data.model.PathResponse
import com.example.eventmanagementhub.data.model.RecommendationResponse
import com.example.eventmanagementhub.data.model.RegisterRequest
import com.example.eventmanagementhub.data.model.UserResponse
import com.example.eventmanagementhub.data.model.VenueMapResponse
import com.example.eventmanagementhub.data.model.VenuesResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Every call returns `Response<T>` rather than a bare body so the repository can
 * inspect the HTTP status (401 expiry, 4xx validation) instead of only catching
 * thrown exceptions.
 */
interface HubApi {

    // Auth
    @POST("api/auth/register")
    suspend fun register(@Body body: RegisterRequest): Response<AuthResponse>

    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): Response<AuthResponse>

    @GET("api/auth/me")
    suspend fun me(): Response<UserResponse>

    @PUT("api/auth/me/interests")
    suspend fun updateInterests(@Body body: InterestsRequest): Response<UserResponse>

    // Bookmarks (server is the source of truth; mirrored into the offline cache)
    @GET("api/auth/me/bookmarks")
    suspend fun bookmarks(): Response<BookmarksResponse>

    @POST("api/auth/me/bookmarks/{eventId}")
    suspend fun addBookmark(@Path("eventId") eventId: String): Response<BookmarkMutationResponse>

    @DELETE("api/auth/me/bookmarks/{eventId}")
    suspend fun removeBookmark(@Path("eventId") eventId: String): Response<BookmarkMutationResponse>

    // Events
    @GET("api/events")
    suspend fun events(
        @Query("category") category: String? = null,
        @Query("date") date: String? = null,
        @Query("q") q: String? = null
    ): Response<EventListResponse>

    @GET("api/events/{id}")
    suspend fun event(@Path("id") id: String): Response<EventResponse>

    /** Aggregate payload: event + venue + statistics + charts + announcements + navigation. */
    @GET("api/events/{id}/details")
    suspend fun eventDetails(
        @Path("id") id: String,
        @Query("from") from: String? = null
    ): Response<EventDetailsResponse>

    // AI
    @POST("api/ai/search")
    suspend fun aiSearch(@Body body: AiSearchRequest): Response<AiSearchResponse>

    @GET("api/ai/recommendations")
    suspend fun recommendations(): Response<RecommendationResponse>

    // Navigation
    @GET("api/navigation/venues")
    suspend fun venues(): Response<VenuesResponse>

    /** Whole-campus canvas layout: fractional positions + undirected walk graph. */
    @GET("api/navigation/map")
    suspend fun campusMap(): Response<CampusMapResponse>

    /** One venue node with positioned neighbours and edge distances. */
    @GET("api/navigation/venues/{venueId}/map")
    suspend fun venueMap(@Path("venueId") venueId: String): Response<VenueMapResponse>

    @POST("api/navigation/path")
    suspend fun path(@Body body: PathRequest): Response<PathResponse>

    // Chat (REST backup for BitChat announcements + persisted chat history)
    @GET("api/chat/announcements")
    suspend fun announcements(@Query("eventId") eventId: String? = null): Response<AnnouncementsResponse>

    @GET("api/chat/messages")
    suspend fun chatMessages(
        @Query("room") room: String? = null,
        @Query("before") before: String? = null,
        @Query("limit") limit: Int? = null
    ): Response<ChatMessagesResponse>
}