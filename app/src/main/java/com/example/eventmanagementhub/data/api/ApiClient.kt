package com.example.eventmanagementhub.data.api

import com.example.eventmanagementhub.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType

/** Injectable auth header provider so Retrofit can attach the JWT when present. */
object AuthProvider {
    @Volatile var token: String? = null

    /**
     * Raised when the backend rejects our token (401). The UI collects this and
     * returns the user to the login screen instead of silently showing stale data.
     */
    private val _sessionExpired = MutableStateFlow(false)
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    /**
     * Set when a 401 invalidates the session. Persisted-token restore must skip
     * exactly one attempt: `SessionStore.clear()` writes asynchronously, so the
     * login screen can otherwise read the stale token back and re-authenticate a
     * session the server already rejected.
     */
    @Volatile var invalidated: Boolean = false
        private set

    fun onUnauthorized() {
        token = null
        invalidated = true
        _sessionExpired.value = true
    }

    fun consumeSessionExpiry() {
        _sessionExpired.value = false
    }

    /** Returns true once after a 401, so the caller can ignore a stale stored token. */
    fun consumeInvalidation(): Boolean {
        val was = invalidated
        invalidated = false
        return was
    }
}

private val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

private val authInterceptor = Interceptor { chain ->
    val token = AuthProvider.token
    val request = if (token != null) {
        chain.request().newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
    } else chain.request()
    chain.proceed(request)
}

object ApiClient {

    const val DEFAULT_BASE_URL = "http://10.0.2.2:3000/"

    val baseUrl: String
        get() = normalize(BuildConfig.API_BASE_URL.ifBlank { DEFAULT_BASE_URL })

    /**
     * Retrofit requires an absolute base URL ending in `/`. Endpoints in [HubApi]
     * already carry their own `api/` prefix, so this must stay an origin
     * (`http://10.0.2.2:3000/`) — passing `.../api` would double-prefix every path.
     */
    private fun normalize(url: String): String {
        val trimmed = url.trim()
        val usable = runCatching {
            val parsed = java.net.URI(trimmed)
            (parsed.scheme == "http" || parsed.scheme == "https") && parsed.host != null
        }.getOrDefault(false)
        if (!usable) return DEFAULT_BASE_URL
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    /**
     * BODY logging would print request payloads verbatim, which means the login
     * password and the JWT in every Authorization header end up in logcat.
     * BASIC still gives method/URL/status/timing without leaking secrets.
     */
    private val logging = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
        else HttpLoggingInterceptor.Level.NONE
        redactHeader("Authorization")
    }

    private val okHttp = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(logging)
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    val service: HubApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttp)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(HubApi::class.java)
}