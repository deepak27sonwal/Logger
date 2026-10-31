package com.leadlogger.app.data

import com.leadlogger.app.BuildConfig
import com.leadlogger.app.util.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class SupabaseException(message: String, val httpCode: Int = -1) : Exception(message)

/**
 * Minimal, dependency-light client for Supabase Auth (password grant) and
 * PostgREST, with transparent access-token refresh. Every call goes over
 * HTTPS straight to the project configured in BuildConfig.
 */
class SupabaseClient(private val prefs: SecurePrefs) {

    private val baseUrl = BuildConfig.SUPABASE_URL
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY
    private val jsonMediaType = "application/json".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    // ---- Auth -------------------------------------------------------

    suspend fun signInWithPassword(email: String, password: String): Unit = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("email", email)
            put("password", password)
        }
        val request = Request.Builder()
            .url("$baseUrl/auth/v1/token?grant_type=password")
            .addHeader("apikey", anonKey)
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw SupabaseException(extractErrorMessage(text) ?: "Sign in failed", response.code)
            }
            val json = JSONObject(text)
            prefs.accessToken = json.getString("access_token")
            prefs.refreshToken = json.optString("refresh_token", null)
            val user = json.getJSONObject("user")
            prefs.userId = user.getString("id")
            prefs.userEmail = user.optString("email", email)
        }
    }

    private suspend fun refreshAccessToken(): Boolean = withContext(Dispatchers.IO) {
        val refreshToken = prefs.refreshToken ?: return@withContext false
        val body = JSONObject().apply { put("refresh_token", refreshToken) }
        val request = Request.Builder()
            .url("$baseUrl/auth/v1/token?grant_type=refresh_token")
            .addHeader("apikey", anonKey)
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext false
            val json = JSONObject(response.body?.string().orEmpty())
            prefs.accessToken = json.getString("access_token")
            prefs.refreshToken = json.optString("refresh_token", prefs.refreshToken)
            true
        }
    }

    fun signOut() {
        prefs.clearSession()
    }

    // ---- PostgREST ----------------------------------------------------

    /**
     * Upserts a batch of rows into [table], retrying once after a token
     * refresh on 401. [onConflict] names the unique constraint columns so
     * re-sending an already-synced call/SMS row is a harmless no-op rather
     * than a duplicate insert.
     */
    suspend fun upsert(table: String, rows: JSONArray, onConflict: String): Unit = withContext(Dispatchers.IO) {
        if (rows.length() == 0) return@withContext
        performUpsert(table, rows, onConflict, allowRetry = true)
    }

    private suspend fun performUpsert(table: String, rows: JSONArray, onConflict: String, allowRetry: Boolean) {
        val token = prefs.accessToken ?: throw SupabaseException("Not signed in")
        val request = Request.Builder()
            .url("$baseUrl/rest/v1/$table?on_conflict=$onConflict")
            .addHeader("apikey", anonKey)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(rows.toString().toRequestBody(jsonMediaType))
            .build()

        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw SupabaseException("Network error syncing $table: ${e.message}")
        }

        response.use {
            if (it.code == 401 && allowRetry && refreshAccessToken()) {
                performUpsert(table, rows, onConflict, allowRetry = false)
                return
            }
            if (!it.isSuccessful) {
                val text = it.body?.string().orEmpty()
                throw SupabaseException(extractErrorMessage(text) ?: "Upsert into $table failed", it.code)
            }
        }
    }

    /** Creates/updates the caller's own employees row (id = auth.uid()). */
    suspend fun upsertSelfAsEmployee(fullName: String, email: String, deviceModel: String): Unit =
        withContext(Dispatchers.IO) {
            val userId = prefs.userId ?: throw SupabaseException("Not signed in")
            val row = JSONObject().apply {
                put("id", userId)
                put("full_name", fullName)
                put("email", email)
                put("device_model", deviceModel)
            }
            upsert("employees", JSONArray().put(row), onConflict = "id")
        }

    /** Records consent for the signed-in employee; required before any insert into call_logs/sms_logs. */
    suspend fun recordConsent(consentVersion: String): Unit = withContext(Dispatchers.IO) {
        val token = prefs.accessToken ?: throw SupabaseException("Not signed in")
        val userId = prefs.userId ?: throw SupabaseException("Not signed in")
        val body = JSONObject().apply {
            put("consent_given_at", java.time.Instant.now().toString())
            put("consent_version", consentVersion)
        }
        val request = Request.Builder()
            .url("$baseUrl/rest/v1/employees?id=eq.$userId")
            .addHeader("apikey", anonKey)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "return=minimal")
            .patch(body.toString().toRequestBody(jsonMediaType))
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val text = response.body?.string().orEmpty()
                throw SupabaseException(extractErrorMessage(text) ?: "Could not record consent", response.code)
            }
        }
        prefs.consentGiven = true
    }

    private fun extractErrorMessage(body: String): String? = try {
        val json = JSONObject(body)
        json.optString("msg", json.optString("message", json.optString("error_description", null)))
    } catch (e: Exception) {
        null
    }
}
