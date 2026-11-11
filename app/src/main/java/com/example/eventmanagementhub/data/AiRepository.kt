package com.example.eventmanagementhub.data

import com.example.eventmanagementhub.data.api.AiApi
import com.example.eventmanagementhub.data.api.ApiClient
import com.example.eventmanagementhub.data.api.AuthProvider
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import retrofit2.Response

/**
 * Talks to the AI endpoints. Rules:
 *  - AI is an extra. Any failure becomes a plain [Result.Error] the UI shows next to the normal, unchanged screen.
 *  - No retries: these are user-triggered, may spend provider quota, and a second tap is the user's decision.
 *  - Nothing is cached as truth and nothing is sent without the user asking.
 */
class AiRepository(private val api: AiApi = ApiClient.aiService) {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    @Serializable
    private data class ErrorBody(val error: String = "", val code: String = "")

    /** `enabled=false` on any failure, so the AI buttons are hidden unless the server positively says yes. */
    suspend fun status(): AiStatus = withContext(Dispatchers.IO) {
        try {
            val resp = api.status()
            if (resp.isSuccessful) resp.body() ?: AiStatus() else AiStatus()
        } catch (e: Exception) {
            AiStatus()
        }
    }

    suspend fun ask(question: String): Result<AssistantResponse> = call("The assistant is not available right now.") {
        api.assistant(AssistantRequest(question.trim()))
    }

    suspend fun summarize(announcementId: String): Result<SummaryResponse> =
        call("The summary is not available right now.") { api.summary(announcementId) }

    suspend fun translate(announcementId: String, language: String): Result<TranslationResponse> =
        call("The translation is not available right now.") { api.translate(announcementId, TranslateRequest(language)) }

    suspend fun rewrite(text: String, kind: String): Result<RewriteResponse> =
        call("The suggestion is not available right now.") { api.rewrite(RewriteRequest(text.trim(), kind)) }

    suspend fun simplify(eventId: String): Result<SimplifyResponse> =
        call("The plain-language version is not available right now.") { api.simplify(eventId) }

    /** [eventIds] are the events THIS app knows you saved; the server cannot see them otherwise. */
    suspend fun checkSchedule(eventIds: List<String>): Result<ScheduleResponse> =
        call("The schedule check is not available right now.") { api.schedule(ScheduleRequest(eventIds.distinct().take(MAX_EVENT_IDS))) }

    private suspend fun <T> call(fallback: String, block: suspend () -> Response<T>): Result<T> = withContext(Dispatchers.IO) {
        try {
            val resp = block()
            val body = resp.body()
            if (resp.isSuccessful && body != null) return@withContext Result.Success(body)
            val parsed = runCatching { json.decodeFromString<ErrorBody>(resp.errorBody()?.string().orEmpty()) }.getOrNull()
            val expired = resp.code() == 401 && AuthProvider.token != null
            Result.Error(AiLogic.friendlyError(parsed?.code, resp.code(), fallback), resp.code(), expired, parsed?.code)
        } catch (e: java.io.IOException) {
            Result.Error(AiLogic.OFFLINE_MESSAGE, null, false, "OFFLINE")
        } catch (e: Exception) {
            // An answer we could not read is not "offline"; show the calm per-feature message instead.
            Result.Error(fallback, null, false, "UNREADABLE")
        }
    }

    companion object {
        /** Mirrors the server limit for the schedule check. */
        const val MAX_EVENT_IDS = 30
    }
}
