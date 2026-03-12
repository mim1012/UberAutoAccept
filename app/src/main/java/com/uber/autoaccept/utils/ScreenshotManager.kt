package com.uber.autoaccept.utils

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 스크린샷 획득 유틸리티
 * API 30+: AccessibilityService.takeScreenshot() 사용
 * API 29 이하: 미지원 (null 반환)
 */
class ScreenshotManager(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "ScreenshotManager"
    }

    /**
     * 현재 화면 스크린샷을 Bitmap으로 반환
     * API 30 미만에서는 null 반환
     */
    suspend fun capture(): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            captureApiR()
        } else {
            Log.d(TAG, "API ${Build.VERSION.SDK_INT}: takeScreenshot 미지원 (API 30 필요)")
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureApiR(): Bitmap? = suspendCancellableCoroutine { cont ->
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val bitmap = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer,
                            screenshot.colorSpace
                        )?.copy(Bitmap.Config.ARGB_8888, false)
                        screenshot.hardwareBuffer.close()
                        Log.d(TAG, "스크린샷 성공: ${bitmap?.width}x${bitmap?.height}")
                        cont.resume(bitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "스크린샷 실패: errorCode=$errorCode")
                        cont.resume(null)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot 예외: ${e.message}")
            cont.resume(null)
        }
    }
}
