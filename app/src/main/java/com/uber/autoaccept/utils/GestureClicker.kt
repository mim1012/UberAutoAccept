package com.uber.autoaccept.utils

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * dispatchGesture 기반 클릭 유틸리티.
 *
 * performAction(ACTION_CLICK)이 동작하지 않는 커스텀 뷰(Uber Driver 등)에서
 * 좌표 기반 터치 제스처로 클릭을 시뮬레이션한다.
 *
 * AutoClicker APK 디컴파일 분석 결과를 바탕으로 구현:
 * - Path.moveTo(x, y) → StrokeDescription(path, 0, duration)
 * - dispatchGesture(gesture, callback, null)
 * - 랜덤 오프셋으로 봇 탐지 회피
 */
object GestureClicker {
    private const val TAG = "GestureClicker"
    
    /** 기본 터치 duration (ms). AutoClicker는 10ms 사용, 안정성 위해 50ms */
    private const val DEFAULT_CLICK_DURATION_MS = 50L
    
    /** 랜덤 오프셋 최대 범위 (px). 봇 탐지 회피용 */
    private const val MAX_RANDOM_OFFSET = 5

    /**
     * 좌표 기반 제스처 클릭. suspend로 결과를 대기한다.
     *
     * @param service AccessibilityService 인스턴스
     * @param x 클릭 X좌표 (screen coordinates)
     * @param y 클릭 Y좌표 (screen coordinates)
     * @param duration 터치 지속 시간 (ms)
     * @param humanize true면 ±MAX_RANDOM_OFFSET 랜덤 편차 적용
     * @return 제스처 성공 여부
     */
    suspend fun click(
        service: AccessibilityService,
        x: Float,
        y: Float,
        duration: Long = DEFAULT_CLICK_DURATION_MS,
        humanize: Boolean = true
    ): Boolean {
        val finalX = if (humanize) x + (-MAX_RANDOM_OFFSET..MAX_RANDOM_OFFSET).random() else x
        val finalY = if (humanize) y + (-MAX_RANDOM_OFFSET..MAX_RANDOM_OFFSET).random() else y

        val path = Path().apply { moveTo(finalX, finalY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return suspendCoroutine { cont ->
            val dispatched = service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Log.i(TAG, "✅ Gesture click 완료 ($finalX, $finalY)")
                        cont.resume(true)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.w(TAG, "❌ Gesture click 취소됨 ($finalX, $finalY)")
                        cont.resume(false)
                    }
                },
                null
            )
            if (!dispatched) {
                Log.e(TAG, "❌ dispatchGesture 반환값 false ($finalX, $finalY)")
                cont.resume(false)
            }
        }
    }

    /**
     * AccessibilityNodeInfo의 중심 좌표를 구해 제스처 클릭.
     */
    suspend fun clickNode(
        service: AccessibilityService,
        node: AccessibilityNodeInfo,
        humanize: Boolean = true
    ): Boolean {
        val (cx, cy) = AccessibilityHelper.getCenter(node)
        return click(service, cx.toFloat(), cy.toFloat(), humanize = humanize)
    }
}
