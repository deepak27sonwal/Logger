package com.leadlogger.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import com.leadlogger.app.LoggerApp
import com.leadlogger.app.R
import com.leadlogger.app.sync.SyncRepository
import com.leadlogger.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that is the only thing on this device allowed to read
 * the call log / SMS providers. It is never hidden: Android requires (and
 * this app shows) a permanent notification the whole time it runs, and
 * stopping it from the home screen (MainActivity) stops capture instantly.
 *
 * Capture is "instant" via ContentObservers that fire the moment Android
 * writes a new call-log or SMS row, backed by a short periodic fallback
 * sync in case an observer callback is missed.
 */
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var periodicJob: Job? = null
    private lateinit var syncRepository: SyncRepository

    private val mainHandler = Handler(Looper.getMainLooper())

    private val callLogObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            triggerSync()
        }
    }

    private val smsObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            triggerSync()
        }
    }

    override fun onCreate() {
        super.onCreate()
        syncRepository = SyncRepository(this)
        startForeground(NOTIFICATION_ID, buildNotification())

        contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, callLogObserver)
        contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, smsObserver)

        // Fallback sweep in case an observer callback is coalesced or missed.
        periodicJob = scope.launch {
            while (isActive) {
                triggerSync()
                delay(PERIODIC_SYNC_INTERVAL_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LoggerApp.from(this).prefs.monitoringEnabled = true
        return START_STICKY
    }

    override fun onDestroy() {
        LoggerApp.from(this).prefs.monitoringEnabled = false
        contentResolver.unregisterContentObserver(callLogObserver)
        contentResolver.unregisterContentObserver(smsObserver)
        periodicJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun triggerSync() {
        scope.launch {
            try {
                syncRepository.syncAll()
            } catch (e: Exception) {
                // Transient network/auth errors are retried on the next
                // observer trigger or periodic sweep; nothing to surface
                // to the user mid-background-sync.
            }
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "monitoring"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                channelId,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val PERIODIC_SYNC_INTERVAL_MS = 60_000L

        fun start(context: Context) {
            val intent = Intent(context, MonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
