package com.leadlogger.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.leadlogger.app.LoggerApp

/** Resumes monitoring after a reboot, but only if it was already consented to and running. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = LoggerApp.from(context).prefs
        if (prefs.consentGiven && prefs.isSignedIn && prefs.monitoringEnabled) {
            MonitorService.start(context)
        }
    }
}
