package com.leadlogger.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.leadlogger.app.LoggerApp
import com.leadlogger.app.databinding.ActivityConsentBinding
import com.leadlogger.app.service.MonitorService
import kotlinx.coroutines.launch

/**
 * Explicit, scrollable disclosure the employee must affirmatively accept
 * before any monitoring starts. Declining signs the employee out and
 * leaves the device unmonitored — there is no "monitor anyway" path.
 */
class ConsentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConsentBinding
    private val app get() = LoggerApp.from(this)

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.RECEIVE_SMS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) {
            startMonitoringAndContinue()
        } else {
            Toast.makeText(
                this,
                getString(com.leadlogger.app.R.string.permissions_body),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConsentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.acceptButton.setOnClickListener { onAccept() }
        binding.declineButton.setOnClickListener { onDecline() }
    }

    private fun onAccept() {
        binding.acceptButton.isEnabled = false
        lifecycleScope.launch {
            try {
                app.supabase.recordConsent(consentVersion = CONSENT_VERSION)
                permissionLauncher.launch(requiredPermissions)
            } catch (e: Exception) {
                binding.acceptButton.isEnabled = true
                Toast.makeText(this@ConsentActivity, "Could not record consent: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startMonitoringAndContinue() {
        app.prefs.monitoringEnabled = true
        MonitorService.start(this)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun onDecline() {
        app.supabase.signOut()
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    companion object {
        private const val CONSENT_VERSION = "2026-10-31-v1"
    }
}
