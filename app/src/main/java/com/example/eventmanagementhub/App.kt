package com.example.eventmanagementhub

import android.app.Application
import com.example.eventmanagementhub.data.AppContext
import com.example.eventmanagementhub.data.OfflineStore

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContext.install(this)
        // OfflineStore.init parses its JSON cache on a background thread.
        OfflineStore.init(this)
    }
}