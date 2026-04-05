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

    suspend fun check(context: Context, password: String? = null, forceCheck: Boolean = false): UpdateCheckResult {
        return withContext(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

                // 비밀번호 없이 캐시 사용 (비밀번호 입력 시에는 항상 서버 확인)
                if (!forceCheck && password == null) {
                    val lastCheck = prefs.getLong(LAST_CHECK_KEY, 0)
                    if (System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS) {
                        val cached = prefs.getString(CACHED_RESULT_KEY, null)
                        if (cached != null) {
                            return@withContext gson.fromJson(cached, UpdateCheckResult::class.java)
                        }
                    }
                }

                // 휴대폰 번호 가져오기 (AuthManager prefs에서)
                val authPrefs = context.getSharedPreferences("twinme_auth", Context.MODE_PRIVATE)
                val phoneNumber = authPrefs.getString("phone_number", null)
                    ?: return@withContext UpdateCheckResult(false, false, null, null, null, "휴대폰 번호가 등록되지 않았습니다")

                val currentVersion = BuildConfig.VERSION_NAME

                // Supabase RPC 호출 (phone_number + password 방식)
                val params = mutableMapOf<String, Any>(
                    "p_phone_number" to phoneNumber,
                    "p_current_version" to currentVersion
                )
                if (password != null) {
                    params["p_password"] = password
                }

                val response = SupabaseClient.rpcPost("get_download_url", params)

                val allowed = response["allowed"] as? Boolean ?: false
                val hasUpdate = response["has_update"] as? Boolean ?: false
                val latestVersion = response["latest_version"] as? String
                val appUrl = response["app_url"] as? String
                val shizukuUrl = response["shizuku_url"] as? String
                val message = response["message"] as? String

                val result = UpdateCheckResult(allowed, hasUpdate, latestVersion, appUrl, shizukuUrl, message)

                // 성공 시 캐시 저장 (비밀번호 없이도 접근 가능한 경우만)
                if (allowed && password == null) {
                    prefs.edit()
                        .putLong(LAST_CHECK_KEY, System.currentTimeMillis())
                        .putString(CACHED_RESULT_KEY, gson.toJson(result))
                        .apply()
                }

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
