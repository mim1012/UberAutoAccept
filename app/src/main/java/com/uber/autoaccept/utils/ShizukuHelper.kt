package com.uber.autoaccept.utils

import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

object ShizukuHelper {
    private const val TAG = "ShizukuHelper"

    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Exception) {
        false
    }

    fun hasPermission(): Boolean = try {
        isAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }

    fun requestPermissionIfNeeded() {
        if (!isAvailable()) {
            Log.w(TAG, "Shizuku 서비스 미실행")
            return
        }
        if (!hasPermission()) {
            try {
                Shizuku.requestPermission(1001)
            } catch (e: Exception) {
                Log.e(TAG, "권한 요청 실패: ${e.message}")
            }
        }
    }

    suspend fun tap(x: Float, y: Float, times: Int = 5): Boolean = withContext(Dispatchers.IO) {
        val xi = x.toInt().toString()
        val yi = y.toInt().toString()
        var lastOk = false
        repeat(times) { i ->
            try {
                val process = Shizuku.newProcess(
                    arrayOf("input", "tap", xi, yi),
                    null, null
                )
                val exitCode = process.waitFor()
                Log.i(TAG, "Shizuku tap[$i] (${xi},${yi}) exit=$exitCode")
                lastOk = exitCode == 0
            } catch (e: Exception) {
                Log.e(TAG, "Shizuku tap[$i] 실패: ${e.message}")
            }
            if (i < times - 1) Thread.sleep(30)
        }
        lastOk
    }
}
