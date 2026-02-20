package com.uber.autoaccept.auth

import android.util.Log
import com.uber.autoaccept.supabase.SupabaseClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

sealed class LicenseStatus {
    object Valid : LicenseStatus()
    data class Expired(val since: String) : LicenseStatus()
    object NotFound : LicenseStatus()
    data class Error(val message: String) : LicenseStatus()
}

/**
 * Supabase licenses 테이블에서 라이센스 유효성 확인.
 * RLS 정책으로 현재 로그인한 사용자의 라이센스만 조회됨.
 */
object LicenseManager {
    private const val TAG = "LicenseManager"

    suspend fun checkLicense(deviceId: String, accessToken: String): LicenseStatus {
        return try {
            val rows = SupabaseClient.restGet(
                table = "licenses",
                query = "device_id=eq.$deviceId&select=is_active,expires_at",
                token = accessToken
            )
            if (rows.isEmpty()) return LicenseStatus.NotFound

            val license = rows.first()
            val isActive = license["is_active"] as? Boolean ?: false
            val expiresAt = license["expires_at"] as? String

            when {
                !isActive -> LicenseStatus.Expired(expiresAt ?: "비활성화됨")
                expiresAt != null && isExpired(expiresAt) -> LicenseStatus.Expired(expiresAt)
                else -> LicenseStatus.Valid
            }
        } catch (e: Exception) {
            Log.e(TAG, "License check failed", e)
            LicenseStatus.Error(e.message ?: "알 수 없는 오류")
        }
    }

    private fun isExpired(expiresAt: String): Boolean {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val expiry = sdf.parse(expiresAt.take(19)) ?: return false
            expiry.before(Date())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse expiry date: $expiresAt")
            false
        }
    }
}
