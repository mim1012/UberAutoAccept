package com.uber.autoaccept.state

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.uber.autoaccept.engine.FilterEngine
import com.uber.autoaccept.logging.RemoteLogger
import com.uber.autoaccept.model.*
import com.uber.autoaccept.utils.GestureClicker
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
            StateEvent.OfferParsed(offer)
        } else {
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
                StateEvent.OfferFiltered(accepted = true, reason = result.reasons.joinToString())
            }
            is FilterResult.Rejected -> {
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

    /** dispatchGesture 4차 fallback용. 서비스에서 주입 */
    var accessibilityService: AccessibilityService? = null

    override fun canHandle(state: AppState): Boolean = state is AppState.Accepting

    override suspend fun handle(state: AppState, rootNode: AccessibilityNodeInfo?): StateEvent? {
        if (state !is AppState.Accepting) {
            return StateEvent.ErrorOccurred("Invalid state")
        }

        Log.d(TAG, "수락 버튼 클릭 시도... (윈도우 수: ${allWindowRoots.size})")

        // 1차: 파싱 시점에 저장된 노드 사용
        val storedButton = state.offer.acceptButtonNode
        if (storedButton != null && com.uber.autoaccept.utils.AccessibilityHelper.isNodeValid(storedButton) && storedButton.isClickable) {
            if (storedButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "✅ 수락 버튼 클릭 성공 (1차: 저장된 노드)")
                RemoteLogger.logActionResult("accept", true, "1차_저장노드")
                return StateEvent.AcceptSuccess("1차_저장노드")
            }
            Log.w(TAG, "저장된 노드 클릭 실패, fallback 시도...")
        }

        // 탐색 대상: 모든 윈도우 루트 (없으면 rootNode 단독)
        val searchRoots = allWindowRoots.ifEmpty { listOfNotNull(rootNode) }

        if (searchRoots.isEmpty()) {
            Log.e(TAG, "❌ 탐색 가능한 윈도우 없음")
            return StateEvent.ErrorOccurred("수락 버튼 없음")
        }

        // 2차: 모든 윈도우에서 ViewId로 탐색 — isClickable 무관하게 performAction 시도
        for (root in searchRoots) {
            for (viewId in ACCEPT_BUTTON_VIEW_IDS) {
                val btn = com.uber.autoaccept.utils.AccessibilityHelper.findNodeByViewId(root, viewId)
                if (btn != null) {
                    if (btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.i(TAG, "✅ 수락 버튼 클릭 성공 (2차: ViewId: $viewId)")
                        RemoteLogger.logActionResult("accept", true, "2차_ViewId($viewId)")
                        return StateEvent.AcceptSuccess("2차_ViewId($viewId)")
                    }
                }
            }
        }

        // 3차: 모든 윈도우에서 텍스트로 탐색
        // findClickableNode() 실패 시 원본 노드로도 performAction 시도
        for (root in searchRoots) {
            for (text in ACCEPT_BUTTON_TEXTS) {
                val node = com.uber.autoaccept.utils.AccessibilityHelper.findNodeByText(root, text)
                val btn = com.uber.autoaccept.utils.AccessibilityHelper.findClickableNode(node) ?: node
                if (btn != null) {
                    if (btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.i(TAG, "✅ 수락 버튼 클릭 성공 (3차: 텍스트: $text)")
                        RemoteLogger.logActionResult("accept", true, "3차_텍스트($text)")
                        return StateEvent.AcceptSuccess("3차_텍스트($text)")
                    }
                }
            }
        }

        // 4차: dispatchGesture — performAction 실패 시 좌표 기반 터치 제스처
        // Uber Driver 커스텀 뷰는 performAction(ACTION_CLICK)을 무시할 수 있음
        // AutoClicker 방식: 노드 중심 좌표 → Path.moveTo → StrokeDescription → dispatchGesture
        val service = accessibilityService
        if (service != null) {
            Log.d(TAG, "4차 dispatchGesture fallback 시도...")

            // 4-a: ViewId로 찾은 노드의 좌표로 제스처 클릭
            for (root in searchRoots) {
                for (viewId in ACCEPT_BUTTON_VIEW_IDS) {
                    val btn = com.uber.autoaccept.utils.AccessibilityHelper.findNodeByViewId(root, viewId)
                    if (btn != null) {
                        if (GestureClicker.clickNode(service, btn)) {
                            delay(300) // 클릭 결과 반영 대기
                            Log.i(TAG, "✅ 수락 성공 (4차: Gesture ViewId: $viewId)")
                            RemoteLogger.logActionResult("accept", true, "4차_Gesture_ViewId($viewId)")
                            return StateEvent.AcceptSuccess("4차_Gesture_ViewId($viewId)")
                        }
                    }
                }
            }

            // 4-b: 텍스트로 찾은 노드의 좌표로 제스처 클릭
            for (root in searchRoots) {
                for (text in ACCEPT_BUTTON_TEXTS) {
                    val node = com.uber.autoaccept.utils.AccessibilityHelper.findNodeByText(root, text)
                    if (node != null) {
                        if (GestureClicker.clickNode(service, node)) {
                            delay(300)
                            Log.i(TAG, "✅ 수락 성공 (4차: Gesture 텍스트: $text)")
                            RemoteLogger.logActionResult("accept", true, "4차_Gesture_텍스트($text)")
                            return StateEvent.AcceptSuccess("4차_Gesture_텍스트($text)")
                        }
                    }
                }
            }
        } else {
            Log.w(TAG, "⚠️ AccessibilityService 미주입 — 4차 Gesture fallback 스킵")
        }

        Log.e(TAG, "❌ 모든 fallback 실패 (1차~4차, 윈도우: ${searchRoots.size})")
        RemoteLogger.logActionResult("accept", false, "1차~4차 모두 실패 (윈도우:${searchRoots.size})")
        return StateEvent.ErrorOccurred("수락 버튼 클릭 불가 (1차~4차 모두 실패)")
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
        
        // 2초 후 온라인 상태로 복귀
        delay(2000)
        
        return StateEvent.Reset
    }
}
