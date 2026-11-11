package com.example.eventmanagementhub.data.api

import com.example.eventmanagementhub.data.model.AiStatus
import com.example.eventmanagementhub.data.model.AssistantRequest
import com.example.eventmanagementhub.data.model.AssistantResponse
import com.example.eventmanagementhub.data.model.RewriteRequest
import com.example.eventmanagementhub.data.model.RewriteResponse
import com.example.eventmanagementhub.data.model.ScheduleRequest
import com.example.eventmanagementhub.data.model.ScheduleResponse
import com.example.eventmanagementhub.data.model.SimplifyResponse
import com.example.eventmanagementhub.data.model.SummaryResponse
import com.example.eventmanagementhub.data.model.TranslateRequest
import com.example.eventmanagementhub.data.model.TranslationResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * The AI helpers. The app never talks to the AI provider and never holds a key: the server does that. These calls only
 * work for a signed-in user (except [status]) and each can answer 503 AI_DISABLED, which the UI treats as "hide it".
 */
interface AiApi {
    @GET("api/ai/status")
    suspend fun status(): Response<AiStatus>

    @POST("api/ai/assistant")
    suspend fun assistant(@Body body: AssistantRequest): Response<AssistantResponse>

    @POST("api/ai/announcements/{id}/summary")
    suspend fun summary(@Path("id") id: String): Response<SummaryResponse>

    @POST("api/ai/announcements/{id}/translate")
    suspend fun translate(@Path("id") id: String, @Body body: TranslateRequest): Response<TranslationResponse>

    @POST("api/ai/support/rewrite")
    suspend fun rewrite(@Body body: RewriteRequest): Response<RewriteResponse>

    @POST("api/ai/events/{id}/simplify")
    suspend fun simplify(@Path("id") id: String): Response<SimplifyResponse>

    @POST("api/ai/schedule/conflicts")
    suspend fun schedule(@Body body: ScheduleRequest): Response<ScheduleResponse>
}
