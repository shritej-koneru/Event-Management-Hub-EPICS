package com.example.eventmanagementhub.data

/** Maps backend `{ error, code }` failures of the announcements / help-desk API to readable text. */
object SupportErrors {
    const val NETWORK_MESSAGE = "No connection. Help chats need an internet connection — check your network and try again."

    fun message(httpCode: Int?, code: String?, serverMessage: String?, fallback: String): String = when {
        code == "NOT_REGISTERED" -> "Register for this event first to start a help chat."
        code == "EVENT_NOT_FOUND" -> "This event no longer exists."
        code == "RATE_LIMITED" || httpCode == 429 -> "You are sending messages too fast. Wait a moment and try again."
        httpCode == 403 -> "You do not have access to this."
        httpCode == 404 -> "That chat could not be found."
        httpCode == 401 -> "Your session expired. Please sign in again."
        httpCode != null && httpCode >= 500 -> "The server had a problem. Please try again shortly."
        !serverMessage.isNullOrBlank() -> serverMessage
        else -> fallback
    }
}
