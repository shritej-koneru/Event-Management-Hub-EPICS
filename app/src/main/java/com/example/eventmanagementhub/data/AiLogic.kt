package com.example.eventmanagementhub.data

import com.example.eventmanagementhub.data.model.AccessibilitySummary
import com.example.eventmanagementhub.data.model.AiSearchFilters
import com.example.eventmanagementhub.data.model.EventPrice

/** Pure helpers behind the AI screens (unit-tested; no Android types). */
object AiLogic {

    const val OFFLINE_MESSAGE = "You're offline. AI helpers need a connection."

    /** Maps the server's machine code to a calm message. Never shows raw JSON or provider details. */
    fun friendlyError(code: String?, http: Int, fallback: String): String = when {
        code == "AI_DISABLED" -> "AI helpers are not turned on for this server."
        code == "AI_RATE_LIMITED" || http == 429 -> "AI is busy. Please wait a minute and try again."
        code == "AI_TIMEOUT" -> "The AI took too long. Please try again."
        code == "AI_UNAVAILABLE" -> "The AI service is not responding. Please try again later."
        code == "AI_BAD_RESPONSE" -> "The AI answer could not be trusted, so it was not shown. Please try again."
        code == "UNSUPPORTED_LANGUAGE" -> "That language is not supported."
        code == "INPUT_TOO_LONG" -> "That text is too long."
        http == 401 -> "Please log in to use this."
        http == 404 -> "That item no longer exists."
        else -> fallback
    }

    /** Filter chips on the Events search. Each chip adds exactly one explicit filter. */
    enum class Chip(val label: String) {
        FREE("Free"),
        STEP_FREE("Step-free"),
        CAPTIONS("Captions"),
        SIGN_LANGUAGE("Sign language"),
        THIS_WEEKEND("This weekend")
    }

    /** Builds the request filters from the selected chips. [today] is YYYY-MM-DD in the user's day. */
    fun filtersFor(chips: Set<Chip>, today: java.time.LocalDate): AiSearchFilters {
        val access = buildList {
            if (Chip.STEP_FREE in chips) add("stepFree")
            if (Chip.CAPTIONS in chips) add("captions")
            if (Chip.SIGN_LANGUAGE in chips) add("signLanguage")
        }
        var from: String? = null
        var to: String? = null
        if (Chip.THIS_WEEKEND in chips) {
            val sat = today.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SATURDAY))
            val start = if (today.dayOfWeek == java.time.DayOfWeek.SUNDAY) today else sat
            val end = if (today.dayOfWeek == java.time.DayOfWeek.SUNDAY) today else sat.plusDays(1)
            from = start.toString()
            to = end.toString()
        }
        return AiSearchFilters(
            free = if (Chip.FREE in chips) true else null,
            accessibility = access.ifEmpty { null },
            dateFrom = from,
            dateTo = to
        )
    }

    /** The server requires a non-empty query; with no typed text the chip words stand in (the server strips them). */
    fun queryFor(text: String, chips: Set<Chip>): String =
        text.trim().ifBlank {
            chips.joinToString(" ") {
                when (it) {
                    Chip.FREE -> "free"
                    Chip.STEP_FREE -> "step-free"
                    Chip.CAPTIONS -> "captions"
                    Chip.SIGN_LANGUAGE -> "sign language"
                    Chip.THIS_WEEKEND -> "this weekend"
                }
            }
        }

    /** One line about a price, or null when the organizer did not state it (unknown is never shown as free). */
    fun priceLabel(price: EventPrice?): String? = when (price?.type) {
        "free" -> "Free"
        "paid" -> price.amountCents?.let { "${price.currency.orEmpty()} ${formatMajor(it)}".trim() }
        else -> null
    }

    private fun formatMajor(cents: Int): String =
        if (cents % 100 == 0) (cents / 100).toString() else String.format(java.util.Locale.US, "%.2f", cents / 100.0)

    /** Text for the "what we know about access" block; each group is shown only when it has content. */
    fun accessibilityLines(a: AccessibilitySummary): List<String> = buildList {
        if (a.offered.isNotEmpty()) add("Offered: " + a.offered.joinToString(", "))
        if (a.notOffered.isNotEmpty()) add("Not offered: " + a.notOffered.joinToString(", "))
        if (a.notStated.isNotEmpty()) add("Not stated by the organizer: " + a.notStated.joinToString(", "))
    }

    /** Note under search results when filters hid events that never stated the value. */
    fun unknownNote(unknownExcluded: Int): String? =
        if (unknownExcluded > 0) {
            "$unknownExcluded event${if (unknownExcluded == 1) "" else "s"} not shown because the organizer did not state this."
        } else null
}
