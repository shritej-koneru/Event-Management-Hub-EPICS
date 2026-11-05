package com.example.eventmanagementhub.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * Single owner of the "session" preferences DataStore so no two instances ever
 * target the same file (that throws IllegalStateException at runtime).
 */
private val Context.dataStore by preferencesDataStore(name = "session")
private val TOKEN_KEY = stringPreferencesKey("auth_token")

/**
 * Application context captured once at startup so non-UI layers (the repository)
 * can reach session storage without threading a Context through every call.
 */
object AppContext {
    @Volatile
    var app: Context? = null
        internal set

    fun install(context: Context) {
        app = context.applicationContext
    }
}

object SessionStore {

    private val cipher by lazy { KeystoreTokenCipher() }

    private fun vault(context: Context) = TokenVault(
        cipher,
        read = { context.dataStore.data.first()[TOKEN_KEY] },
        // Keeps an empty value for "cleared" because DataStore rejects empty preferences.
        write = { v -> context.dataStore.edit { it[TOKEN_KEY] = v } }
    )

    suspend fun token(context: Context): String? = vault(context).load()

    suspend fun save(context: Context, token: String) = vault(context).save(token)

    /** Clears the stored session. */
    suspend fun clear(context: Context) = vault(context).clear()
}
