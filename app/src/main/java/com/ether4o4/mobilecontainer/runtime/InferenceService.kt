package com.ether4o4.mobilecontainer.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ether4o4.mobilecontainer.MainActivity
import com.ether4o4.mobilecontainer.R
import com.ether4o4.mobilecontainer.data.ModelManifest
import com.ether4o4.mobilecontainer.data.ModelStore
import fi.iki.elonen.NanoHTTPD
import java.io.File

/**
 * Foreground service that owns the InferenceEngine + HttpApiServer lifecycle.
 * One model loaded at a time (RAM safety). Start with an intent carrying the
 * model name; stops when unloaded.
 */
class InferenceService : Service() {

    private val engine = InferenceEngine()
    private var server: HttpApiServer? = null
    private var store: ModelStore? = null
    @Volatile private var current: ModelManifest? = null

    companion object {
        const val ACTION_LOAD = "load"
        const val ACTION_UNLOAD = "unload"
        const val EXTRA_MODEL = "model"
        const val EXTRA_PORT = "port"
        const val DEFAULT_PORT = 8080
        const val CHANNEL_ID = "mc_inference"
        const val NOTIF_ID = 4242

        // Singleton handle so the UI can talk to the live engine.
        @Volatile private var instance: InferenceService? = null
        fun get(): InferenceService? = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = ModelStore(this)
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Ready"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LOAD -> {
                val name = intent.getStringExtra(EXTRA_MODEL) ?: return START_NOT_STICKY
                val port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
                try {
                    val m = store?.get(name) ?: throw RuntimeException("Model not found: $name")
                    val root = store!!.modelDir(name)
                    val info = engine.load(m, root)
                    current = m
                    server?.stop()
                    server = HttpApiServer(port, engine) { current }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT) }
                    updateNotification("Running ${m.name}  :$port\n$info")
                    Log.i("MCINF", "loaded ${m.name} on :$port")
                } catch (e: Exception) {
                    Log.e("MCINF", "load failed", e)
                    updateNotification("Load failed: ${e.message}")
                }
            }
            ACTION_UNLOAD -> {
                stopEngine()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    fun stopEngine() {
        runCatching { server?.stop() }
        server = null
        engine.unload()
        current = null
    }

    override fun onDestroy() {
        stopEngine()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Inference", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MobileContainer")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }
}
