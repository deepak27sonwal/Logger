package com.leadlogger.app

import android.app.Application
import com.leadlogger.app.data.SupabaseClient
import com.leadlogger.app.util.SecurePrefs

class LoggerApp : Application() {

    lateinit var prefs: SecurePrefs
        private set

    lateinit var supabase: SupabaseClient
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = SecurePrefs(this)
        supabase = SupabaseClient(prefs)
    }

    companion object {
        fun from(context: android.content.Context): LoggerApp =
            context.applicationContext as LoggerApp
    }
}
