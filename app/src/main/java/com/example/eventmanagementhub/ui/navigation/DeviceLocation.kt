package com.example.eventmanagementhub.ui.navigation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Minimal last-known / single-fix location helper built on [LocationManager]
 * (no Google Play Services dependency — the outdoor map uses osmdroid tiles,
 * which also need neither GMS nor an API key).
 *
 * Privacy contract: callers invoke [requestSingle] only after the user opens
 * Outdoor mode or taps the locate button; [stop] cancels any in-flight
 * request (screen dispose, mode switch). No location is ever persisted.
 */
class DeviceLocation(private val context: Context) {

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listener: LocationListener? = null
    private var pending: ((Location?) -> Unit)? = null

    companion object {
        private const val TIMEOUT_MS = 15_000L

        fun hasPermission(context: Context): Boolean {
            val fine = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            return fine || coarse
        }
    }

    /** Best-effort last known fix across enabled providers, or null. */
    @SuppressLint("MissingPermission")
    fun lastKnown(): Location? {
        if (!hasPermission(context)) return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { p -> runCatching { manager.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    /**
     * Resolves a single current fix on the main thread: delivers the last known
     * location when one exists, otherwise subscribes for one update (or
     * getCurrentLocation on API 30+) and unsubscribes as soon as it arrives.
     * Always calls back exactly once; [onResult] receives null on timeout or
     * provider failure.
     */
    @SuppressLint("MissingPermission")
    fun requestSingle(onResult: (Location?) -> Unit) {
        if (!hasPermission(context)) {
            onResult(null)
            return
        }
        lastKnown()?.let {
            onResult(it)
            return
        }
        stop()
        pending = onResult
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                deliver(location)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        listener = l
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> {
                deliver(null)
                return
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val executor = java.util.concurrent.Executor { r -> mainHandler.post(r) }
                manager.getCurrentLocation(provider, null, executor) { loc ->
                    deliver(loc)
                }
            } else {
                manager.requestLocationUpdates(provider, 0L, 0f, l, Looper.getMainLooper())
            }
        }.onFailure {
            deliver(null)
        }
        mainHandler.postDelayed({ deliver(null) }, TIMEOUT_MS)
    }

    private fun deliver(location: Location?) {
        val cb = pending
        pending = null
        stop()
        cb?.invoke(location)
    }

    /** Cancels any in-flight request and drops the last callback. */
    fun stop() {
        listener?.let { l -> runCatching { manager.removeUpdates(l) } }
        listener = null
        mainHandler.removeCallbacksAndMessages(null)
        pending = null
    }
}
