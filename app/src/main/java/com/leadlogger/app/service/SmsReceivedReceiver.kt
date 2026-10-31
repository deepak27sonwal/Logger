package com.leadlogger.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.leadlogger.app.LoggerApp

/**
 * Manifest-registered receiver so an incoming SMS wakes this up even if the
 * service were somehow not running. It does not read the SMS itself (the
 * content provider is the source of truth); it just nudges MonitorService
 * to sync right away instead of waiting for its periodic sweep.
 */
class SmsReceivedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return
        val prefs = LoggerApp.from(context).prefs
        if (prefs.monitoringEnabled && prefs.consentGiven && prefs.isSignedIn) {
            MonitorService.start(context)
        }
    }
}
