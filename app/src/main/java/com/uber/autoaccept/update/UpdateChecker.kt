package com.uber.autoaccept.update

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.uber.autoaccept.BuildConfig
import com.uber.autoaccept.supabase.SupabaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val PREFS_NAME = "uber_auto_accept"
    private const val LAST_CHECK_KEY = "update_last_check_time"
    private const val CACHED_RESULT_KEY = "update_cached_result"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    private val gson = Gson()

    suspend fun check(context: Context, forceCheck: Boolean = false): UpdateCheckResult {
        return withContext(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

                // Check cache
                if (!forceCheck) {
                    val lastCheck = prefs.getLong(LAST_CHECK_KEY, 0)
                    if (System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS) {
                        val cached = prefs.getString(CACHED_RESULT_KEY, null)
                        if (cached != null) {
                            return@withContext gson.fromJson(cached, UpdateCheckResult::class.java)
                        }
                    }
                }

                // Get device_id from auth prefs
                val authPrefs = context.getSharedPreferences("twinme_auth", Context.MODE_PRIVATE)
                val deviceId = authPrefs.getString("device_id", null)
                    ?: return@withContext UpdateCheckResult(false, false, null, null, null, "Device not registered")

                val currentVersion = BuildConfig.VERSION_NAME

                // Call Supabase RPC
                val response = SupabaseClient.rpcPost(
                    "get_download_url",
                    mapOf(
                        "p_device_id" to deviceId,
                        "p_current_version" to currentVersion
                    )
                )

                val allowed = response["allowed"] as? Boolean ?: false
                val hasUpdate = response["has_update"] as? Boolean ?: false
                val latestVersion = response["latest_version"] as? String
                val appUrl = response["app_url"] as? String
                val shizukuUrl = response["shizuku_url"] as? String
                val message = response["message"] as? String

                val result = UpdateCheckResult(allowed, hasUpdate, latestVersion, appUrl, shizukuUrl, message)

                // Cache result
                prefs.edit()
                    .putLong(LAST_CHECK_KEY, System.currentTimeMillis())
                    .putString(CACHED_RESULT_KEY, gson.toJson(result))
                    .apply()

                result
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed: ${e.message}")
                UpdateCheckResult(true, false, null, null, null, "Check failed: ${e.message}")
            }
        }
    }

    fun isNewerVersion(current: String, latest: String): Boolean {
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until maxLen) {
            val c = currentParts.getOrElse(i) { 0 }
            val l = latestParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }
}
