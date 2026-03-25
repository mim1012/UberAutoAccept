package com.uber.autoaccept.service

import android.util.Log
import com.uber.autoaccept.IShizukuService

class ShizukuUserService : IShizukuService.Stub() {

    companion object {
        private const val TAG = "ShizukuUserService"
    }

    override fun destroy() {
        Log.i(TAG, "destroy()")
    }

    override fun exit() {
        Log.i(TAG, "exit()")
        destroy()
        System.exit(0)
    }

    override fun tap(x: Int, y: Int): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("input", "tap", "$x", "$y"))
            process.waitFor() == 0
        } catch (e: Exception) {
            Log.e(TAG, "tap failed: ${e.message}")
            false
        }
    }

    override fun tapRepeat(x: Int, y: Int, times: Int, intervalMs: Int): Boolean {
        return try {
            var success = true
            for (i in 0 until times) {
                val process = Runtime.getRuntime().exec(arrayOf("input", "tap", "$x", "$y"))
                if (process.waitFor() != 0) {
                    success = false
                }
                if (i < times - 1 && intervalMs > 0) {
                    Thread.sleep(intervalMs.toLong())
                }
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "tapRepeat failed: ${e.message}")
            false
        }
    }
}
