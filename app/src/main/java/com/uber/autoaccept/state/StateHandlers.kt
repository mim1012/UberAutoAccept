package com.uber.autoaccept.state

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.uber.autoaccept.engine.FilterEngine
import com.uber.autoaccept.logging.RemoteLogger
import com.uber.autoaccept.model.*
import com.uber.autoaccept.utils.UberOfferParser
import kotlinx.coroutines.delay

/**
 * OfferDetected 상태 핸들러
 * 오퍼 정보를 파싱
 */
class OfferDetectedHandler(private val parser: UberOfferParser) : BaseStateHandler() {
    override fun canHandle(state: AppState): Boolean = state is AppState.OfferDetected
    
    override suspend fun handle(state: AppState, rootNode: AccessibilityNodeInfo?): StateEvent? {
        if (state !is AppState.OfferDetected || rootNode == null || !rootNode.isValid()) {
            return StateEvent.ErrorOccurred("Invalid state or node")
        }

        Log.d(TAG, "오퍼 파싱 시작...")

        val offer = parser.parseOfferDetails(rootNode)
        
        return if (offer != null) {
            Log.i("UAA", "[PARSE] ✅ 파싱 성공 | 출발: ${offer.pickupLocation} | 도착: ${offer.dropoffLocation} | 고객거리: ${offer.customerDistance}km | 버튼발견: ${offer.acceptButtonNode != null}")
            StateEvent.OfferParsed(offer)
        } else {
            Log.e("UAA", "[PARSE] ❌ 파싱 실패 — ViewId/텍스트 모두 실패 (Uber 앱 버전 변경 가능성)")
            StateEvent.ErrorOccurred("오퍼 파싱 실패")
        }
    }
}

/**
 * OfferAnalyzing 상태 핸들러
 * 오퍼를 필터링하여 수락 여부 결정
 */
class OfferAnalyzingHandler(private val filterEngine: FilterEngine) : BaseStateHandler() {
    override fun canHandle(state: AppState): Boolean = state is AppState.OfferAnalyzing
    
    override suspend fun handle(state: AppState, rootNode: AccessibilityNodeInfo?): StateEvent? {
        if (state !is AppState.OfferAnalyzing) {
            return StateEvent.ErrorOccurred("Invalid state")
        }
        
        Log.d(TAG, "오퍼 필터링 중...")
        
        val result = filterEngine.isEligible(state.offer)
        
        return when (result) {
            is FilterResult.Accepted -> {
                Log.i("UAA", "[FILTER] ✅ 수락 조건 충족 → ${result.reasons.joinToString()}")
                StateEvent.OfferFiltered(accepted = true, reason = result.reasons.joinToString())
            }
            is FilterResult.Rejected -> {
                Log.w("UAA", "[FILTER] ⛔ 수락 조건 불충족 → ${result.reasons.joinToString()}")
                StateEvent.OfferFiltered(accepted = false, reason = result.reasons.joinToString())
            }
        }
    }
}

/**
 * ReadyToAccept 상태 핸들러
 * 수락 버튼 클릭 준비
 */
class ReadyToAcceptHandler(private val config: AppConfig) : BaseStateHandler() {
    override fun canHandle(state: AppState): Boolean = state is AppState.ReadyToAccept
    
    override suspend fun handle(state: AppState, rootNode: AccessibilityNodeInfo?): StateEvent? {
        if (state !is AppState.ReadyToAccept) {
            return StateEvent.ErrorOccurred("Invalid state")
        }
        
        Log.d(TAG, "수락 준비 중... (딜레이: ${config.autoAcceptDelay}ms)")
        
        // 인간 행동 시뮬레이션: 랜덤 딜레이
        if (config.humanizationEnabled) {
            val randomDelay = config.autoAcceptDelay + (0..200).random()
            delay(randomDelay)
        } else {
            delay(config.autoAcceptDelay)
        }
        
        return StateEvent.AcceptButtonClicked
    }
}

/**
 * Accepting 상태 핸들러
 * 실제 수락 버튼 클릭 실행
 */
class AcceptingHandler : BaseStateHandler() {
    companion object {
        private val ACCEPT_BUTTON_VIEW_IDS = listOf(
            "uda_details_accept_button",
            "upfront_offer_configurable_details_accept_button",
            "upfront_offer_configurable_details_auditable_accept_button"
        )
        private val ACCEPT_BUTTON_TEXTS = listOf("콜 수락", "수락", "확인", "Accept", "ACCEPT")
    }

    /** 서비스에서 클릭 직전에 주입 — 모든 윈도우 루트 */
    var allWindowRoots: List<AccessibilityNodeInfo> = emptyList()
    var openCVButtonRect: android.graphics.Rect? = null
    var serviceRef: android.accessibilityservice.AccessibilityService? = null
    /** 사용자가 Floating Target으로 지정한 수락 버튼 좌표 */
    var targetClickPoint: android.graphics.PointF? = null

    override fun canHandle(state: AppState): Boolean = state is AppState.Accepting

    override suspend fun handle(state: AppState, rootNode: AccessibilityNodeInfo?): StateEvent? {
        if (state !is AppState.Accepting) {
            return StateEvent.ErrorOccurred("Invalid state")
        }

        val target = targetClickPoint
        val svc = serviceRef

        if (target == null || svc == null) {
            Log.e("UAA", "[ACCEPT] ❌ 타겟 좌표 없음 — ⊕ 먼저 배치하세요")
            RemoteLogger.logActionResult("accept", false, "타겟좌표 없음")
            return StateEvent.ErrorOccurred("타겟 좌표 미설정")
        }

        com.uber.autoaccept.service.FloatingWidgetService.disableTargetTouch()

        repeat(5) { i ->
            val path = android.graphics.Path().apply { moveTo(target.x, target.y) }
            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 100L)
            svc.dispatchGesture(
                android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build(),
                object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: android.accessibilityservice.GestureDescription) {
                        Log.i("UAA", "[ACCEPT] 탭 ${i + 1}/5 ✅ completed (${target.x},${target.y})")
                    }
                    override fun onCancelled(g: android.accessibilityservice.GestureDescription) {
                        Log.w("UAA", "[ACCEPT] 탭 ${i + 1}/5 ❌ cancelled (${target.x},${target.y})")
                    }
                }, null
            )
            delay(150)
        }

        com.uber.autoaccept.service.FloatingWidgetService.enableTargetTouch()
        Log.i("UAA", "[ACCEPT] ✅ 제스처 완료 (${target.x},${target.y})")
        RemoteLogger.logActionResult("accept", true, "dispatchGesture(${target.x},${target.y})")
        return StateEvent.AcceptSuccess("dispatchGesture")
    }
}

/**
 * Accepted 상태 핸들러
 * 수락 완료 후 처리
 */
class AcceptedHandler : BaseStateHandler() {
    override fun canHandle(state: AppState): Boolean = state is AppState.Accepted
    
    override suspend fun handle(state: AppState, rootNode: AccessibilityNodeInfo?): StateEvent? {
        if (state !is AppState.Accepted) {
            return null
        }
        
        Log.i(TAG, "콜 수락 완료. 다음 콜 대기 중...")
        
        // 3초 후 온라인 상태로 복귀
        delay(3000)
        
        return StateEvent.Reset
    }
}

/**
 * Rejected 상태 핸들러
 * 거부 후 처리
 */
class RejectedHandler : BaseStateHandler() {
    override fun canHandle(state: AppState): Boolean = state is AppState.Rejected
    
    override suspend fun handle(state: AppState, rootNode: AccessibilityNodeInfo?): StateEvent? {
        if (state !is AppState.Rejected) {
            return null
        }
        
        Log.w(TAG, "콜 거부됨: ${state.reason}")
        Log.w("UAA", "[FILTER] ⛔ 콜 거부: ${state.reason}")

        // 1초 후 온라인 상태로 복귀
        delay(1000)
        
        return StateEvent.Reset
    }
}

/**
 * Error 상태 핸들러
 * 오류 후 복구
 */
class ErrorHandler : BaseStateHandler() {
    override fun canHandle(state: AppState): Boolean = state is AppState.Error
    
    override suspend fun handle(state: AppState, rootNode: AccessibilityNodeInfo?): StateEvent? {
        if (state !is AppState.Error) {
            return null
        }
        
        Log.e(TAG, "오류 발생: ${state.message}", state.exception)
        Log.e("UAA", "[ERROR] ❌ 상태 오류: ${state.message}")

        // 2초 후 온라인 상태로 복귀
        delay(2000)
        
        return StateEvent.Reset
    }
}
