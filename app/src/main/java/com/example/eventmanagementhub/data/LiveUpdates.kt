package com.example.eventmanagementhub.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** What the session socket heard. Screens refetch over REST when a signal concerns them. */
data class LiveSignal(
    val kind: Kind,
    /** Event the signal is about; null for global announcements or when the payload had none. */
    val eventId: String? = null,
    /** Support ticket the signal is about, when known. */
    val ticketId: String? = null
) {
    enum class Kind { ANNOUNCEMENT, SUPPORT_MESSAGE, SUPPORT_TICKET, RECONNECTED }
}

/** App-wide bus fed by the session socket (see LiveSocketViewModel). */
object LiveUpdates {
    private val _signals = MutableSharedFlow<LiveSignal>(extraBufferCapacity = 32)
    val signals: SharedFlow<LiveSignal> = _signals.asSharedFlow()

    fun emit(signal: LiveSignal) { _signals.tryEmit(signal) }
}
