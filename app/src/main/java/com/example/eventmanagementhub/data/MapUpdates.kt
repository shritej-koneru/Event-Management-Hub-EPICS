package com.example.eventmanagementhub.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide "campus map changed on the server" signal. The socket layer bumps it
 * when a `map:updated` event arrives; MapScreen collects it and refetches.
 */
object MapUpdates {
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    fun notifyUpdated() { _version.value = _version.value + 1 }
}
