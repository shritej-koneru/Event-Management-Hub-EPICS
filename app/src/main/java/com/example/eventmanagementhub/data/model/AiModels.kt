package com.example.eventmanagementhub.data.model

import kotlinx.serialization.Serializable

/** GET /api/ai/status. `enabled=false` (or an unreachable server) means every AI action stays hidden. */
@Serializable
data class AiStatus(
    val enabled: Boolean = false,
    val languages: List<AiLanguage> = emptyList(),
    val maxInputChars: Int = 2000
)

@Serializable
data class AiLanguage(val code: String = "", val name: String = "")

/** Filters the server applies strictly: only events that explicitly state the value match. */
@Serializable
data class AiSearchFilters(
    val category: String? = null,
    val dateFrom: String? = null,
    val dateTo: String? = null,
    val free: Boolean? = null,
    val maxPriceCents: Int? = null,
    val accessibility: List<String>? = null
) {
    fun isEmpty(): Boolean = category == null && dateFrom == null && dateTo == null && free == null &&
        maxPriceCents == null && accessibility.isNullOrEmpty()
}

/** Typed price: `type` is "free" or "paid"; a missing price means the organizer did not say. */
@Serializable
data class EventPrice(
    val type: String = "",
    val amountCents: Int? = null,
    val currency: String? = null
)

@Serializable
data class AssistantRequest(val question: String)

@Serializable
data class AiSource(
    val id: String = "",
    val type: String = "",
    val refId: String = "",
    val title: String = "",
    val eventId: String? = null
)

/** `available=false` means "I don't know": show [message], never a guess. */
@Serializable
data class AssistantResponse(
    val available: Boolean = false,
    val answer: String? = null,
    val message: String? = null,
    val sources: List<AiSource> = emptyList(),
    val aiGenerated: Boolean = false,
    val fallback: Boolean = false
)

@Serializable
data class SummaryResponse(
    @Serializable(with = FlexIdSerializer::class) val announcementId: String = "",
    val eventId: String? = null,
    val summary: String = "",
    val original: String = "",
    val aiGenerated: Boolean = false,
    val fallback: Boolean = false,
    val alreadyShort: Boolean = false
)

@Serializable
data class TranslateRequest(val language: String)

@Serializable
data class TranslationResponse(
    @Serializable(with = FlexIdSerializer::class) val announcementId: String = "",
    val original: String = "",
    val translated: String = "",
    val language: String = ""
)

@Serializable
data class RewriteRequest(val text: String, val kind: String = "message")

@Serializable
data class RewriteResponse(
    val original: String = "",
    val suggestion: String = "",
    val kind: String = "message",
    val requiresReview: Boolean = true
)

@Serializable
data class AccessibilitySummary(
    val offered: List<String> = emptyList(),
    val notOffered: List<String> = emptyList(),
    val notStated: List<String> = emptyList()
)

@Serializable
data class SimplifyResponse(
    val eventId: String = "",
    val original: String = "",
    val plain: String = "",
    val accessibility: AccessibilitySummary = AccessibilitySummary()
)

@Serializable
data class ScheduleRequest(val eventIds: List<String>)

@Serializable
data class ScheduleEventBrief(
    val id: String = "",
    val title: String = "",
    val category: String = "",
    val venue: String = "",
    val date: String = "",
    val time: String = "",
    val durationMin: Int = 0
)

@Serializable
data class ScheduleConflict(
    val a: ScheduleEventBrief = ScheduleEventBrief(),
    val b: ScheduleEventBrief = ScheduleEventBrief(),
    val overlapMinutes: Int = 0
)

@Serializable
data class ScheduleAlternatives(
    val forEventId: String = "",
    val options: List<ScheduleEventBrief> = emptyList()
)

@Serializable
data class ScheduleBasis(
    val source: String = "",
    val eventCount: Int = 0,
    val unknownIds: List<String> = emptyList()
)

@Serializable
data class ScheduleResponse(
    val hasConflicts: Boolean = false,
    val conflicts: List<ScheduleConflict> = emptyList(),
    val alternatives: List<ScheduleAlternatives> = emptyList(),
    val basedOn: ScheduleBasis = ScheduleBasis(),
    val limitation: String = ""
)
