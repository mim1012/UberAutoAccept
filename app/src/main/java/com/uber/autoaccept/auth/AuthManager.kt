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
 * Auth is tied to the SIM phone number only.
 * Manual input and ANDROID_ID fallback are intentionally disabled.
 */
class AuthManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "AuthManager"
        private const val PREFS_NAME = "twinme_auth"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_USER_TYPE = "user_type"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_LAST_AUTH_TIME = "last_auth_time"
        private const val KEY_IS_AUTHORIZED = "is_authorized"

        private const val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L

        @Volatile
        private var instance: AuthManager? = null

        fun getInstance(context: Context): AuthManager {
            return instance ?: synchronized(this) {
                instance ?: AuthManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isAuthorized: Boolean
        get() = prefs.getBoolean(KEY_IS_AUTHORIZED, false)
        private set(value) = prefs.edit().putBoolean(KEY_IS_AUTHORIZED, value).apply()

    var userType: String
        get() = prefs.getString(KEY_USER_TYPE, "") ?: ""
        private set(value) = prefs.edit().putString(KEY_USER_TYPE, value).apply()

    var expiresAt: String
        get() = prefs.getString(KEY_EXPIRES_AT, "") ?: ""
        private set(value) = prefs.edit().putString(KEY_EXPIRES_AT, value).apply()

    @Deprecated("SIM-only auth; manual phone entry is ignored.")
    var savedPhoneNumber: String
        get() = "__sim_only__"
        set(@Suppress("UNUSED_PARAMETER") value) {
            Log.w(TAG, "manual phone number input ignored; SIM auth only")
        }

    private var lastAuthTime: Long
        get() = prefs.getLong(KEY_LAST_AUTH_TIME, 0)
        set(value) = prefs.edit().putLong(KEY_LAST_AUTH_TIME, value).apply()

    fun isCacheValid(): Boolean {
        if (!isAuthorized) return false
        return System.currentTimeMillis() - lastAuthTime < CACHE_DURATION_MS
    }

    @Synchronized
    fun clearCache() {
        Log.d(TAG, "auth cache cleared")
        isAuthorized = false
        lastAuthTime = 0
    }

    @SuppressLint("HardwareIds")
    fun getPhoneNumber(): String? {
        if (!hasPhonePermission()) return null
        return try {
            val telephonyManager =
                context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val number = telephonyManager.line1Number
            if (!number.isNullOrBlank()) normalizePhone(number) else null
        } catch (e: Exception) {
            Log.w(TAG, "failed to read SIM phone number: ${e.message}")
            null
        }
    }

    @SuppressLint("HardwareIds")
    fun getDeviceId(): String {
        val cached = prefs.getString(KEY_DEVICE_ID, null)
        if (!cached.isNullOrEmpty()) return cached
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        return deviceId
    }

    fun hasPhonePermission(): Boolean {
        val readPhoneStateGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED
        val readPhoneNumbersGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) ==
                PackageManager.PERMISSION_GRANTED
        return readPhoneStateGranted || readPhoneNumbersGranted
    }

    @Synchronized
    fun authenticate(callback: AuthCallback) {
        if (isCacheValid()) {
            Log.d(TAG, "using cached auth result")
            callback.onSuccess(AuthResult(true, userType, expiresAt, "cached auth"))
            return
        }

        val phoneNumber = getPhoneNumber()
        if (phoneNumber.isNullOrBlank()) {
            isAuthorized = false
            callback.onFailure("SIM 전화번호를 읽을 수 없습니다. 등록된 유심 번호로만 인증됩니다.")
            return
        }

        Log.d(TAG, "auth via SIM phone number = $phoneNumber")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response =
                    SupabaseClient.rpcPost("check_license", mapOf("p_identifier" to phoneNumber))
                val authorized = response["authorized"] as? Boolean ?: false
                val message = response["message"] as? String ?: ""
                val responseUserType = response["user_type"] as? String ?: "basic"
                val responseExpiresAt = response["expires_at"] as? String ?: ""

                if (authorized) {
                    isAuthorized = true
                    userType = responseUserType
                    expiresAt = responseExpiresAt
                    lastAuthTime = System.currentTimeMillis()
                    callback.onSuccess(
                        AuthResult(true, responseUserType, responseExpiresAt, message)
                    )
                } else {
                    isAuthorized = false
                    callback.onFailure(message.ifEmpty { "등록된 SIM 번호가 아닙니다." })
                }
            } catch (e: Exception) {
                Log.e(TAG, "auth failed: ${e.message}")
                if (isAuthorized && lastAuthTime > 0) {
                    callback.onSuccess(AuthResult(true, userType, expiresAt, "offline cache"))
                } else {
                    callback.onFailure("인증 서버 연결 실패: ${e.message}")
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
        var normalized = phone.replace(Regex("[^0-9]"), "")
        if (normalized.startsWith("82")) {
            normalized = "0${normalized.substring(2)}"
        }
        return normalized
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
