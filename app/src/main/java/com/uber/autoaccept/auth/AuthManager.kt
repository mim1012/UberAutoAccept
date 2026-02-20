package com.uber.autoaccept.auth

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.uber.autoaccept.supabase.SupabaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Vortex 패턴 인증 관리자.
 * 전화번호(우선) 또는 ANDROID_ID(폴백)로 Supabase check_license RPC 호출.
 * 24시간 캐시, 네트워크 오류 시 캐시 허용.
 */
class AuthManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "AuthManager"
        private const val PREFS_NAME = "twinme_auth"
        private const val KEY_PHONE_NUMBER = "phone_number"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_USER_TYPE = "user_type"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_LAST_AUTH_TIME = "last_auth_time"
        private const val KEY_IS_AUTHORIZED = "is_authorized"

        private const val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L // 24시간

        @Volatile
        private var instance: AuthManager? = null

        fun getInstance(context: Context): AuthManager {
            return instance ?: synchronized(this) {
                instance ?: AuthManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isAuthorized: Boolean
        get() = prefs.getBoolean(KEY_IS_AUTHORIZED, false)
        private set(value) = prefs.edit().putBoolean(KEY_IS_AUTHORIZED, value).apply()

    var userType: String
        get() = prefs.getString(KEY_USER_TYPE, "") ?: ""
        private set(value) = prefs.edit().putString(KEY_USER_TYPE, value).apply()

    var expiresAt: String
        get() = prefs.getString(KEY_EXPIRES_AT, "") ?: ""
        private set(value) = prefs.edit().putString(KEY_EXPIRES_AT, value).apply()

    var savedPhoneNumber: String
        get() = prefs.getString(KEY_PHONE_NUMBER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PHONE_NUMBER, value).apply()

    private var lastAuthTime: Long
        get() = prefs.getLong(KEY_LAST_AUTH_TIME, 0)
        set(value) = prefs.edit().putLong(KEY_LAST_AUTH_TIME, value).apply()

    fun isCacheValid(): Boolean {
        if (!isAuthorized) return false
        return System.currentTimeMillis() - lastAuthTime < CACHE_DURATION_MS
    }

    @Synchronized
    fun clearCache() {
        Log.d(TAG, "인증 캐시 무효화")
        isAuthorized = false
        lastAuthTime = 0
    }

    /** 전화번호 읽기 (권한 있을 때만, 없으면 null) */
    @SuppressLint("HardwareIds")
    fun getPhoneNumber(): String? {
        if (savedPhoneNumber.isNotEmpty()) return savedPhoneNumber
        if (!hasPhonePermission()) return null
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val num = tm.line1Number
            if (!num.isNullOrEmpty()) normalizePhone(num) else null
        } catch (e: Exception) {
            Log.w(TAG, "전화번호 추출 실패: ${e.message}")
            null
        }
    }

    /** 기기 고유 ID (ANDROID_ID 기반, SharedPreferences 캐시) */
    @SuppressLint("HardwareIds")
    fun getDeviceId(): String {
        val cached = prefs.getString(KEY_DEVICE_ID, null)
        if (!cached.isNullOrEmpty()) return cached
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        return deviceId
    }

    fun hasPhonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * 인증 수행. 캐시가 유효하면 즉시 성공 반환.
     * 네트워크 오류 시 이전 캐시 허용.
     */
    @Synchronized
    fun authenticate(callback: AuthCallback) {
        if (isCacheValid()) {
            Log.d(TAG, "캐시된 인증 사용")
            callback.onSuccess(AuthResult(true, userType, expiresAt, "캐시 사용"))
            return
        }

        val identifier = getPhoneNumber() ?: getDeviceId()
        val isPhone = getPhoneNumber() != null
        Log.d(TAG, "인증 시도: ${if (isPhone) "전화번호" else "기기ID"} = $identifier")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = SupabaseClient.rpcPost("check_license", mapOf("p_identifier" to identifier))
                val authorized = response["authorized"] as? Boolean ?: false
                val message = response["message"] as? String ?: ""
                val resUserType = response["user_type"] as? String ?: "basic"
                val resExpiresAt = response["expires_at"] as? String ?: ""

                if (authorized) {
                    isAuthorized = true
                    userType = resUserType
                    expiresAt = resExpiresAt
                    lastAuthTime = System.currentTimeMillis()
                    callback.onSuccess(AuthResult(true, resUserType, resExpiresAt, message))
                } else {
                    isAuthorized = false
                    callback.onFailure(message.ifEmpty { "인증 실패" })
                }
            } catch (e: Exception) {
                Log.e(TAG, "인증 오류: ${e.message}")
                if (isAuthorized && lastAuthTime > 0) {
                    // 네트워크 오류이고 이전 인증 기록이 있으면 허용
                    callback.onSuccess(AuthResult(true, userType, expiresAt, "오프라인 캐시"))
                } else {
                    callback.onFailure("네트워크 연결 실패: ${e.message}")
                }
            }
        }
    }

    fun clearAuth() {
        prefs.edit()
            .remove(KEY_IS_AUTHORIZED)
            .remove(KEY_USER_TYPE)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_LAST_AUTH_TIME)
            .apply()
    }

    private fun normalizePhone(phone: String): String {
        var n = phone.replace(Regex("[^0-9]"), "")
        if (n.startsWith("82")) n = "0${n.substring(2)}"
        return n
    }

    data class AuthResult(
        val authorized: Boolean,
        val userType: String,
        val expiresAt: String,
        val message: String
    )

    interface AuthCallback {
        fun onSuccess(result: AuthResult)
        fun onFailure(error: String)
    }
}
