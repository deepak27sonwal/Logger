package com.leadlogger.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.leadlogger.app.LoggerApp
import com.leadlogger.app.R
import com.leadlogger.app.databinding.ActivityMainBinding
import com.leadlogger.app.service.MonitorService
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val app get() = LoggerApp.from(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!app.prefs.isSignedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        if (!app.prefs.consentGiven) {
            startActivity(Intent(this, ConsentActivity::class.java))
            finish()
            return
        }

        binding.toggleButton.setOnClickListener { onToggleClicked() }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val prefs = app.prefs
        binding.signedInAsText.text = getString(R.string.home_signed_in_as, prefs.userEmail ?: "-")

        if (prefs.monitoringEnabled) {
            binding.statusText.text = getString(R.string.home_status_active)
            binding.toggleButton.text = getString(R.string.home_stop)
        } else {
            binding.statusText.text = getString(R.string.home_status_inactive)
            binding.toggleButton.text = getString(R.string.home_start)
        }

        val lastSync = prefs.lastSyncEpochMillis
        binding.lastSyncText.text = if (lastSync > 0) {
            getString(R.string.home_last_sync, DateFormat.getDateTimeInstance().format(Date(lastSync)))
        } else {
            getString(R.string.home_last_sync, "never")
        }
    }

    private fun onToggleClicked() {
        val prefs = app.prefs
        if (prefs.monitoringEnabled) {
            // Stop monitoring and sign out entirely: this is a full,
            // visible off-switch, not a "pause".
            MonitorService.stop(this)
            app.supabase.signOut()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        } else {
            MonitorService.start(this)
            prefs.monitoringEnabled = true
            render()
        }
    }
}
