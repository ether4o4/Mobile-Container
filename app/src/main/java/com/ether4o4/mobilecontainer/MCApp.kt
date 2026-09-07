package com.ether4o4.mobilecontainer

import android.app.Application
import android.content.Context
import com.ether4o4.mobilecontainer.util.LogStore

/**
 * Application entry point. Holds a global app context and seeds the bin dir.
 */
class MCApp : Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = this
        LogStore.add("[MCApp] started")
    }

    companion object {
        @Volatile
        lateinit var appContext: Context
            private set
    }
}
