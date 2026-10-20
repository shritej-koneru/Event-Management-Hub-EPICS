package com.example.eventmanagementhub

import android.app.Application
import com.example.eventmanagementhub.data.AppContext
import com.example.eventmanagementhub.data.OfflineStore
import org.osmdroid.config.Configuration

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContext.install(this)
        // OfflineStore.init parses its JSON cache on a background thread.
        OfflineStore.init(this)
        // Identify the app to OpenStreetMap tile servers (their usage policy
        // requires a valid User-Agent) before any MapView is created.
        Configuration.getInstance().userAgentValue =
            "EventManagementHub/1.0 ($packageName)"
    }
}