package com.leadlogger.app.sync

import android.content.Context
import android.provider.CallLog
import android.provider.Telephony
import android.util.Log
import com.leadlogger.app.LoggerApp
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Reads new rows from the CallLog / SMS content providers since the last
 * sync cursor and upserts them to Supabase. Runs on whatever coroutine
 * dispatcher the caller is already on (call it from a background thread).
 */
class SyncRepository(private val context: Context) {

    private val app get() = LoggerApp.from(context)
    private val prefs get() = app.prefs

    suspend fun syncCallLog() {
        val employeeId = prefs.userId ?: return
        val since = prefs.lastSyncEpochMillis
        val rows = JSONArray()

        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
        )
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            "${CallLog.Calls.DATE} > ?",
            arrayOf(since.toString()),
            "${CallLog.Calls.DATE} ASC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(CallLog.Calls._ID)
            val numberCol = cursor.getColumnIndex(CallLog.Calls.NUMBER)
            val typeCol = cursor.getColumnIndex(CallLog.Calls.TYPE)
            val dateCol = cursor.getColumnIndex(CallLog.Calls.DATE)
            val durationCol = cursor.getColumnIndex(CallLog.Calls.DURATION)

            while (cursor.moveToNext()) {
                val dateMillis = cursor.getLong(dateCol)
                val row = JSONObject().apply {
                    put("employee_id", employeeId)
                    put("native_call_id", cursor.getString(idCol))
                    put("phone_number", cursor.getString(numberCol))
                    put("call_type", mapCallType(cursor.getInt(typeCol)))
                    put("call_time", Instant.ofEpochMilli(dateMillis).toString())
                    put("duration_seconds", cursor.getInt(durationCol))
                    put("device_id", android.os.Build.MODEL)
                }
                rows.put(row)
            }
        }

        if (rows.length() > 0) {
            app.supabase.upsert("call_logs", rows, onConflict = "employee_id,native_call_id")
            Log.i(TAG, "Synced ${rows.length()} call log row(s)")
        }
    }

    suspend fun syncSms() {
        val employeeId = prefs.userId ?: return
        val since = prefs.lastSyncEpochMillis
        val rows = JSONArray()

        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.TYPE,
            Telephony.Sms.DATE,
            Telephony.Sms.BODY,
        )
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            "${Telephony.Sms.DATE} > ?",
            arrayOf(since.toString()),
            "${Telephony.Sms.DATE} ASC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(Telephony.Sms._ID)
            val addressCol = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
            val typeCol = cursor.getColumnIndex(Telephony.Sms.TYPE)
            val dateCol = cursor.getColumnIndex(Telephony.Sms.DATE)
            val bodyCol = cursor.getColumnIndex(Telephony.Sms.BODY)

            while (cursor.moveToNext()) {
                val dateMillis = cursor.getLong(dateCol)
                val row = JSONObject().apply {
                    put("employee_id", employeeId)
                    put("native_sms_id", cursor.getString(idCol))
                    put("address", cursor.getString(addressCol))
                    put("sms_type", mapSmsType(cursor.getInt(typeCol)))
                    put("body", cursor.getString(bodyCol))
                    put("sms_time", Instant.ofEpochMilli(dateMillis).toString())
                    put("device_id", android.os.Build.MODEL)
                }
                rows.put(row)
            }
        }

        if (rows.length() > 0) {
            app.supabase.upsert("sms_logs", rows, onConflict = "employee_id,native_sms_id")
            Log.i(TAG, "Synced ${rows.length()} SMS row(s)")
        }
    }

    /** Runs both syncs and advances the cursor to "now" on success. */
    suspend fun syncAll() {
        val startedAt = System.currentTimeMillis()
        syncCallLog()
        syncSms()
        prefs.lastSyncEpochMillis = startedAt
    }

    private fun mapCallType(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        CallLog.Calls.BLOCKED_TYPE -> "blocked"
        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
        else -> "unknown"
    }

    private fun mapSmsType(type: Int): String = when (type) {
        Telephony.Sms.MESSAGE_TYPE_INBOX -> "inbox"
        Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
        Telephony.Sms.MESSAGE_TYPE_DRAFT -> "draft"
        Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "outbox"
        Telephony.Sms.MESSAGE_TYPE_FAILED -> "failed"
        Telephony.Sms.MESSAGE_TYPE_QUEUED -> "queued"
        else -> "unknown"
    }

    companion object {
        private const val TAG = "SyncRepository"
    }
}
