package com.uber.autoaccept.state

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.uber.autoaccept.engine.FilterEngine
import com.uber.autoaccept.logging.ParsedOfferData
import com.uber.autoaccept.logging.RemoteLogger
import com.uber.autoaccept.model.*
import com.uber.autoaccept.utils.GestureClicker
import com.uber.autoaccept.utils.UberOfferParser
import kotlinx.coroutines.delay

class OfferDetectedHandler(private val parser: UberOfferParser) : BaseStateHandler() {
    override fun canHandle(state: AppState): Boolean = state is AppState.OfferDetected

    override suspend fun handle(state: AppState, rootNode: AccessibilityNodeInfo?): StateEvent? {
        if (state !is AppState.OfferDetected || rootNode == null || !rootNode.isValid()) {
            return StateEvent.ErrorOccurred("Invalid state or node")
        }

        Log.d(TAG, "Offer parse started")

        val traceContext = state.offer.traceContext
        var offer = parser.parseOfferDetails(rootNode, traceContext)
        var attempt = 0
        while (offer == null && attempt < 3) {
            attempt++
            Log.w("UAA", "[PARSE] retrying after render wait ($attempt/3)")
            delay(150L)
            if (!rootNode.isValid()) break
            rootNode.refresh()
            offer = parser.parseOfferDetails(rootNode, traceContext)
        }

        return if (offer != null) {
            Log.i(
                "UAA",
                "[PARSE] success (attempt=${attempt + 1}) | pickup=${offer.pickupLocation} | dropoff=${offer.dropoffLocation} | parser=${offer.parserSource}"
            )
            RemoteLogger.logParseResult(
                success = true,
                offerData = ParsedOfferData(
                    offerUuid = offer.offerUuid,
                    traceId = offer.traceContext?.traceId,
                    pickup = offer.pickupLocation,
                    dropoff = offer.dropoffLocation,
                    customerDistance = offer.customerDistance,
                    tripDistance = offer.tripDistance,
                    parseConfidence = offer.parseConfidence.name,
                    acceptButtonFound = offer.acceptButtonNode != null,
                    parserSource = offer.parserSource,
                    pickupViewId = offer.pickupViewId,
                    dropoffViewId = offer.dropoffViewId,
                    pickupValidated = offer.pickupValidated,
                    dropoffValidated = offer.dropoffValidated
                ),
                error = null,
                details = mapOf(
                    "trace_id" to offer.traceContext?.traceId,
                    "parser_source" to offer.parserSource,
                    "pickup_view_id" to offer.pickupViewId,
                    "dropoff_view_id" to offer.dropoffViewId,
                    "parse_attempt" to (attempt + 1)
                )
            )
            StateEvent.OfferParsed(offer)
        } else {
            Log.e("UAA", "[PARSE] failed after retry=3")
            RemoteLogger.logParseResult(
                success = false,
                offerData = null,
                error = "ViewId/텍스트 파싱 실패 (retry=3)",
                details = mapOf(
                    "trace_id" to traceContext?.traceId,
                    "error_code" to "PARSE_FAILED_AFTER_RETRY",
                    "failure_stage" to "offer_detected_handler",
                    "retry_count" to attempt
                )
            )
            StateEvent.ErrorOccurred("오퍼 파싱 실패")
        }
    }
}

class OfferAnalyzingHandler(private val filterEngine: FilterEngine) : BaseStateHandler() {
    override fun canHandle(state: AppState): Boolean = state is AppState.OfferAnalyzing

    override suspend fun handle(state: AppState, rootNode: AccessibilityNodeInfo?): StateEvent? {
        if (state !is AppState.OfferAnalyzing) {
            return StateEvent.ErrorOccurred("Invalid state")
        }

        Log.d(TAG, "Filtering offer")
        val result = filterEngine.isEligible(state.offer)
        RemoteLogger.logFilterResult(state.offer, result)

        return when (result) {
            is FilterResult.Accepted -> {
                Log.i(
                    "UAA",
                    "[FILTER][${state.offer.traceContext?.traceId}] accepted matched=${result.matchedConditions} enabled=${result.enabledConditions} hits=${result.keywordHits}"
                )
                StateEvent.OfferFiltered(accepted = true, reason = result.reasons.joinToString())
            }
            is FilterResult.Rejected -> {
                Log.w(
                    "UAA",
                    "[FILTER][${state.offer.traceContext?.traceId}] rejected code=${result.rejectCode} matched=${result.matchedConditions} enabled=${result.enabledConditions} hits=${result.keywordHits} reason=${result.reasons.joinToString()}"
                )
                StateEvent.OfferFiltered(accepted = false, reason = result.reasons.joinToString())
            }
        }
    }
}

class ReadyToAcceptHandler(private val config: AppConfig) : BaseStateHandler() {
    override fun canHandle(state: AppState): Boolean = state is AppState.ReadyToAccept

    override suspend fun handle(state: AppState, rootNode: AccessibilityNodeInfo?): StateEvent? {
        if (state !is AppState.ReadyToAccept) {
            return StateEvent.ErrorOccurred("Invalid state")
        }

        Log.d(TAG, "Preparing accept with delay=${config.autoAcceptDelay}ms")

        if (config.humanizationEnabled) {
            val randomDelay = config.autoAcceptDelay + (0..200).random()
            delay(randomDelay)
        } else {
            delay(config.autoAcceptDelay)
        }

        return StateEvent.AcceptButtonClicked
    }
}

class AcceptingHandler : BaseStateHandler() {
    companion object {
        private val ACCEPT_BUTTON_VIEW_IDS = listOf(
            "uda_details_accept_button",
            "upfront_offer_configurable_details_accept_button",
            "upfront_offer_configurable_details_auditable_accept_button"
        )
        private val ACCEPT_BUTTON_TEXTS = listOf("콜 수락", "수락", "확인", "Accept", "ACCEPT")
    }

    var allWindowRoots: List<AccessibilityNodeInfo> = emptyList()
    var openCVButtonRect: android.graphics.Rect? = null
    var serviceRef: android.accessibilityservice.AccessibilityService? = null
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

                if (com.uber.autoaccept.utils.ShizukuHelper.hasPermission()) {
                    val ok = com.uber.autoaccept.utils.ShizukuHelper.tap(
                        target.x,
                        target.y,
                        traceId = state.offer.traceContext?.traceId
                    )
                    if (ok) {
                        Log.i("UAA", "[ACCEPT][${state.offer.traceContext?.traceId}] Shizuku tap (${target.x},${target.y})")
                        RemoteLogger.logActionResult(
                            "accept",
                            true,
                            "shizuku_tap(${target.x},${target.y})",
                            state.offer,
                            mapOf("strategy" to "shizuku_tap")
                        )
                        return StateEvent.AcceptSuccess("shizuku_tap")
                    }
                    Log.w("UAA", "[ACCEPT][${state.offer.traceContext?.traceId}] Shizuku failed, fallback continues")
                    method = "shizuku_fail_dispatch"
                } else {
                    Log.w("UAA", "[ACCEPT] No Shizuku, fallback continues")
                    method = "no_shizuku_dispatch"
                }

                if (GestureClicker.click(svc, target.x, target.y, humanize = false)) {
                    Log.i("UAA", "[ACCEPT][${state.offer.traceContext?.traceId}] dispatchGesture (${target.x},${target.y})")
                    RemoteLogger.logActionResult(
                        "accept",
                        true,
                        "dispatch_completed(${target.x},${target.y})[$method]",
                        state.offer,
                        mapOf("strategy" to method)
                    )
                    return StateEvent.AcceptSuccess(method)
                }

                Log.w("UAA", "[ACCEPT][${state.offer.traceContext?.traceId}] target dispatch failed, node fallback continues")
                RemoteLogger.logDiagnostic(
                    "accept_target_dispatch_failed",
                    mapOf("method" to method, "target_x" to target.x, "target_y" to target.y)
                )
            } else {
                Log.w("UAA", "[ACCEPT] target/service missing, node fallback only")
            }

            val storedButton = state.offer.acceptButtonNode
            if (storedButton != null &&
                com.uber.autoaccept.utils.AccessibilityHelper.isNodeValid(storedButton) &&
                storedButton.isClickable
            ) {
                if (storedButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.i(TAG, "Accept button clicked with stored node")
                    RemoteLogger.logActionResult("accept", true, "stored_node", state.offer, mapOf("strategy" to "stored_node"))
                    return StateEvent.AcceptSuccess("stored_node")
                }
                Log.w(TAG, "Stored node click failed, trying fallbacks")
            }

            if (searchRoots.isEmpty()) {
                Log.e(TAG, "No searchable roots available")
                return StateEvent.ErrorOccurred("수락 버튼 없음")
            }

            for (root in searchRoots) {
                for (viewId in ACCEPT_BUTTON_VIEW_IDS) {
                    val btn = com.uber.autoaccept.utils.AccessibilityHelper.findNodeByViewId(root, viewId)
                    if (btn != null && btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.i(TAG, "Accept button clicked (ViewId: $viewId)")
                        RemoteLogger.logActionResult("accept", true, "view_id($viewId)", state.offer, mapOf("strategy" to "view_id($viewId)"))
                        return StateEvent.AcceptSuccess("view_id($viewId)")
                    }
                }
            }

            for (root in searchRoots) {
                for (text in ACCEPT_BUTTON_TEXTS) {
                    val node = com.uber.autoaccept.utils.AccessibilityHelper.findNodeByText(root, text)
                    val btn = com.uber.autoaccept.utils.AccessibilityHelper.findClickableNode(node) ?: node
                    if (btn != null && btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.i(TAG, "Accept button clicked (text: $text)")
                        RemoteLogger.logActionResult("accept", true, "text($text)", state.offer, mapOf("strategy" to "text($text)"))
                        return StateEvent.AcceptSuccess("text($text)")
                    }
                }
            }

            if (svc != null) {
                Log.d(TAG, "Trying gesture fallback on nodes")

                for (root in searchRoots) {
                    for (viewId in ACCEPT_BUTTON_VIEW_IDS) {
                        val btn = com.uber.autoaccept.utils.AccessibilityHelper.findNodeByViewId(root, viewId)
                        if (btn != null && GestureClicker.clickNode(svc, btn)) {
                            delay(300)
                            Log.i(TAG, "Accept succeeded (gesture view id: $viewId)")
                            RemoteLogger.logActionResult("accept", true, "gesture_view_id($viewId)", state.offer, mapOf("strategy" to "gesture_view_id($viewId)"))
                            return StateEvent.AcceptSuccess("gesture_view_id($viewId)")
                        }
                    }
                }

                for (root in searchRoots) {
                    for (text in ACCEPT_BUTTON_TEXTS) {
                        val node = com.uber.autoaccept.utils.AccessibilityHelper.findNodeByText(root, text)
                        if (node != null && GestureClicker.clickNode(svc, node)) {
                            delay(300)
                            Log.i(TAG, "Accept succeeded (gesture text: $text)")
                            RemoteLogger.logActionResult("accept", true, "gesture_text($text)", state.offer, mapOf("strategy" to "gesture_text($text)"))
                            return StateEvent.AcceptSuccess("gesture_text($text)")
                        }
                    }
                }
            } else {
                Log.w(TAG, "AccessibilityService missing, gesture fallback skipped")
            }

            Log.e(TAG, "All accept fallbacks failed (roots=${searchRoots.size})")
            RemoteLogger.logActionResult(
                "accept",
                false,
                "all_fallbacks_failed(window:${searchRoots.size})",
                state.offer,
                mapOf("strategy" to "all_fallbacks_failed")
            )
            return StateEvent.ErrorOccurred("수락 버튼 클릭 불가")
        } finally {
            com.uber.autoaccept.service.FloatingWidgetService.enableTargetTouch()
        }
    }
}

class AcceptedHandler : BaseStateHandler() {
    override fun canHandle(state: AppState): Boolean = state is AppState.Accepted

    override suspend fun handle(state: AppState, rootNode: AccessibilityNodeInfo?): StateEvent? {
        if (state !is AppState.Accepted) {
            return null
        }

        Log.i(TAG, "Accept complete, returning to online")
        delay(3000)
        return StateEvent.Reset
    }
}

class RejectedHandler : BaseStateHandler() {
    override fun canHandle(state: AppState): Boolean = state is AppState.Rejected

    override suspend fun handle(state: AppState, rootNode: AccessibilityNodeInfo?): StateEvent? {
        if (state !is AppState.Rejected) {
            return null
        }

        Log.w(TAG, "Offer rejected: ${state.reason}")
        delay(1000)
        return StateEvent.Reset
    }
}

class ErrorHandler : BaseStateHandler() {
    override fun canHandle(state: AppState): Boolean = state is AppState.Error

    override suspend fun handle(state: AppState, rootNode: AccessibilityNodeInfo?): StateEvent? {
        if (state !is AppState.Error) {
            return null
        }

        Log.e(TAG, "State error: ${state.message}", state.exception)
        delay(2000)
        return StateEvent.Reset
    }
}
