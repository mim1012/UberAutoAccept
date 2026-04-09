package com.uber.autoaccept.state

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

        var offer = parser.parseOfferDetails(rootNode)
        var attempt = 0
        while (offer == null && attempt < 3) {
            attempt++
            Log.w("UAA", "[PARSE] 렌더링 대기 후 재시도 ($attempt/3)...")
            delay(150L)
            if (!rootNode.isValid()) break
            rootNode.refresh()
            offer = parser.parseOfferDetails(rootNode)
        }

        return if (offer != null) {
            Log.i("UAA", "[PARSE] ✅ 파싱 성공 (attempt=${attempt+1}) | 출발: ${offer.pickupLocation} | 도착: ${offer.dropoffLocation} | 고객거리: ${offer.customerDistance}km | 버튼발견: ${offer.acceptButtonNode != null}")
            RemoteLogger.logParseResult(
                success = true,
                offerData = com.uber.autoaccept.logging.ParsedOfferData(
                    offerUuid = offer.offerUuid,
                    pickup = offer.pickupLocation,
                    dropoff = offer.dropoffLocation,
                    customerDistance = offer.customerDistance,
                    tripDistance = offer.tripDistance,
                    parseConfidence = offer.parseConfidence.name,
                    acceptButtonFound = offer.acceptButtonNode != null
                ),
                error = null
            )
            StateEvent.OfferParsed(offer)
        } else {
            Log.e("UAA", "[PARSE] ❌ 파싱 실패 (3회 재시도 후) — ViewId/텍스트 모두 실패")
            RemoteLogger.logParseResult(success = false, offerData = null, error = "ViewId/텍스트 파싱 실패 (retry=3)")
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
        val searchRoots = allWindowRoots.ifEmpty { listOfNotNull(rootNode) }
        RemoteLogger.logDiagnostic(
            "accept_handler_entered",
            mapOf(
                "has_target_point" to (target != null),
                "has_service_ref" to (svc != null),
                "search_root_count" to searchRoots.size,
                "stored_button_present" to (state.offer.acceptButtonNode != null)
            )
        )

        com.uber.autoaccept.service.FloatingWidgetService.disableTargetTouch()
        try {
            if (target != null && svc != null) {
                var method = "unknown"

                // 1차: 사용자 지정 타겟을 Shizuku로 직접 탭
                if (com.uber.autoaccept.utils.ShizukuHelper.hasPermission()) {
                    val ok = com.uber.autoaccept.utils.ShizukuHelper.tap(target.x, target.y)
                    if (ok) {
                        Log.i("UAA", "[ACCEPT] Shizuku 탭 ✅ (${target.x},${target.y})")
                        RemoteLogger.logActionResult("accept", true, "shizuku_tap(${target.x},${target.y})")
                        return StateEvent.AcceptSuccess("shizuku_tap")
                    }
                    Log.w("UAA", "[ACCEPT] Shizuku 탭 ❌ → gesture/node fallback")
                    RemoteLogger.logActionResult("accept", false, "shizuku_tap_failed→fallback")
                    method = "shizuku_fail→dispatch"
                } else {
                    Log.w("UAA", "[ACCEPT] Shizuku 미사용 → gesture/node fallback")
                    method = "no_shizuku→dispatch"
                }

                if (GestureClicker.click(svc, target.x, target.y, humanize = false)) {
                    Log.i("UAA", "[ACCEPT] dispatchGesture ✅ (${target.x},${target.y})")
                    RemoteLogger.logActionResult("accept", true, "dispatch_completed(${target.x},${target.y})[$method]")
                    return StateEvent.AcceptSuccess(method)
                }

                Log.w("UAA", "[ACCEPT] 타겟 좌표 gesture 실패 → 노드 기반 fallback 계속")
                RemoteLogger.logActionResult("accept", false, "dispatch_failed(${target.x},${target.y})[$method]")
                RemoteLogger.logDiagnostic("accept_target_dispatch_failed", mapOf("method" to method, "target_x" to target.x, "target_y" to target.y))
            } else {
                Log.w("UAA", "[ACCEPT] 타겟 좌표/서비스 미주입 → 노드 기반 fallback만 사용")
            }

            // 2차: 파싱 시점에 저장된 노드 사용
            val storedButton = state.offer.acceptButtonNode
            if (storedButton != null &&
                com.uber.autoaccept.utils.AccessibilityHelper.isNodeValid(storedButton) &&
                storedButton.isClickable
            ) {
                if (storedButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.i(TAG, "✅ 수락 버튼 클릭 성공 (저장된 노드)")
                    RemoteLogger.logActionResult("accept", true, "stored_node")
                    return StateEvent.AcceptSuccess("stored_node")
                }
                Log.w(TAG, "저장된 노드 클릭 실패, 추가 fallback 시도...")
            }

            if (searchRoots.isEmpty()) {
                Log.e(TAG, "❌ 탐색 가능한 윈도우 없음")
                return StateEvent.ErrorOccurred("수락 버튼 없음")
            }

            // 3차: 모든 윈도우에서 ViewId로 탐색
            for (root in searchRoots) {
                for (viewId in ACCEPT_BUTTON_VIEW_IDS) {
                    val btn = com.uber.autoaccept.utils.AccessibilityHelper.findNodeByViewId(root, viewId)
                    if (btn != null && btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.i(TAG, "✅ 수락 버튼 클릭 성공 (ViewId: $viewId)")
                        RemoteLogger.logActionResult("accept", true, "view_id($viewId)")
                        return StateEvent.AcceptSuccess("view_id($viewId)")
                    }
                }
            }

            // 4차: 텍스트 기반 클릭
            for (root in searchRoots) {
                for (text in ACCEPT_BUTTON_TEXTS) {
                    val node = com.uber.autoaccept.utils.AccessibilityHelper.findNodeByText(root, text)
                    val btn = com.uber.autoaccept.utils.AccessibilityHelper.findClickableNode(node) ?: node
                    if (btn != null && btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.i(TAG, "✅ 수락 버튼 클릭 성공 (텍스트: $text)")
                        RemoteLogger.logActionResult("accept", true, "text($text)")
                        return StateEvent.AcceptSuccess("text($text)")
                    }
                }
            }

            // 5차: 노드 중심 좌표 gesture fallback
            if (svc != null) {
                Log.d(TAG, "노드 기반 gesture fallback 시도...")

                for (root in searchRoots) {
                    for (viewId in ACCEPT_BUTTON_VIEW_IDS) {
                        val btn = com.uber.autoaccept.utils.AccessibilityHelper.findNodeByViewId(root, viewId)
                        if (btn != null && GestureClicker.clickNode(svc, btn)) {
                            delay(300)
                            Log.i(TAG, "✅ 수락 성공 (Gesture ViewId: $viewId)")
                            RemoteLogger.logActionResult("accept", true, "gesture_view_id($viewId)")
                            return StateEvent.AcceptSuccess("gesture_view_id($viewId)")
                        }
                    }
                }

                for (root in searchRoots) {
                    for (text in ACCEPT_BUTTON_TEXTS) {
                        val node = com.uber.autoaccept.utils.AccessibilityHelper.findNodeByText(root, text)
                        if (node != null && GestureClicker.clickNode(svc, node)) {
                            delay(300)
                            Log.i(TAG, "✅ 수락 성공 (Gesture 텍스트: $text)")
                            RemoteLogger.logActionResult("accept", true, "gesture_text($text)")
                            return StateEvent.AcceptSuccess("gesture_text($text)")
                        }
                    }
                }
            } else {
                Log.w(TAG, "⚠️ AccessibilityService 미주입 — gesture fallback 스킵")
            }

            Log.e(TAG, "❌ 모든 fallback 실패 (윈도우: ${searchRoots.size})")
            RemoteLogger.logActionResult("accept", false, "all_fallbacks_failed(window:${searchRoots.size})")
            return StateEvent.ErrorOccurred("수락 버튼 클릭 불가")
        } finally {
            com.uber.autoaccept.service.FloatingWidgetService.enableTargetTouch()
        }
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
