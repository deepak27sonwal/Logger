package com.leadlogger.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.leadlogger.app.LoggerApp
import com.leadlogger.app.R
import com.leadlogger.app.data.SupabaseException
import com.leadlogger.app.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val app get() = LoggerApp.from(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (app.prefs.isSignedIn) {
            routeToNextScreen()
            return
        }

        binding.loginButton.setOnClickListener { attemptLogin() }
    }

    private fun attemptLogin() {
        val email = binding.emailInput.text?.toString()?.trim().orEmpty()
        val password = binding.passwordInput.text?.toString().orEmpty()
        if (email.isEmpty() || password.isEmpty()) {
            showError(getString(R.string.login_error_generic))
            return
        }

        setLoading(true)
        lifecycleScope.launch {
            try {
                app.supabase.signInWithPassword(email, password)
                app.supabase.upsertSelfAsEmployee(
                    fullName = email.substringBefore("@"),
                    email = email,
                    deviceModel = android.os.Build.MODEL,
                )
                routeToNextScreen()
            } catch (e: SupabaseException) {
                showError(e.message ?: getString(R.string.login_error_generic))
            } catch (e: Exception) {
                showError(getString(R.string.login_error_generic))
            } finally {
                setLoading(false)
            }
        }
    }

    private fun routeToNextScreen() {
        val next = if (app.prefs.consentGiven) MainActivity::class.java else ConsentActivity::class.java
        startActivity(Intent(this, next))
        finish()
    }

    private fun setLoading(loading: Boolean) {
        binding.loginProgress.visibility = if (loading) android.view.View.VISIBLE else android.view.View.GONE
        binding.loginButton.isEnabled = !loading
    }

    private fun showError(message: String) {
        binding.errorText.text = message
        binding.errorText.visibility = android.view.View.VISIBLE
    }
}
