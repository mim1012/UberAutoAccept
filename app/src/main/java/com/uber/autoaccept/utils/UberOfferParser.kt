package com.uber.autoaccept.utils

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.uber.autoaccept.logging.ParsedOfferData
import com.uber.autoaccept.logging.RemoteLogger
import com.uber.autoaccept.model.ParseConfidence
import com.uber.autoaccept.model.UberOffer
import java.util.UUID

class UberOfferParser {
    companion object {
        private const val TAG = "UberOfferParser"
        private val DROPOFF_FALLBACK_IDS = listOf(
            "uda_offer_details_title",
            "uda_offer_details_subtitle",
            "leg_dropoff",
            "leg_dropoff_label"
        )
    }
    
    fun parseOfferDetails(rootNode: AccessibilityNodeInfo): UberOffer? {
        return parseByViewId(rootNode)
    }
    
    private fun parseByViewId(rootNode: AccessibilityNodeInfo): UberOffer? {
        try {
            val (pickupAddress, dropoffAddress, confidence) = findAddresses(rootNode)
                ?: run {
                    Log.w(TAG, "출발지/도착지 추출 실패 (strict)")
                    // Remote diagnostics: compact UI summary for on-device tracing (no ADB)
                    try {
                        val ids = AccessibilityHelper.collectResourceIds(rootNode).entries
                            .sortedByDescending { it.value }.take(30)
                            .joinToString(prefix = "[", postfix = "]") { "${'$'}{it.key}:${'$'}{it.value}" }
                        val addrs = AccessibilityHelper.findAddressLikeNodes(rootNode, 10)
                            .joinToString(prefix = "[", postfix = "]") { (rid, cls, s) -> "(${ '$'}rid|${ '$'}cls|${ '$'}{s.replace("|","/")})" }
                        val btns = AccessibilityHelper.findAcceptButtonCandidates(rootNode, 5)
                            .joinToString(prefix = "[", postfix = "]") { (rid, cls, s) -> "(${ '$'}rid|${ '$'}cls|${ '$'}{s.replace("|","/")})" }
                        RemoteLogger.logParseResult(false, null, "SIMPLE_ADDR_FAIL UI_SUMMARY ids=${'$'}ids addrs=${'$'}addrs btns=${'$'}btns")
                    } catch (_: Exception) {
                        RemoteLogger.logParseResult(false, null, "SIMPLE_ADDR_FAIL")
                    }
                    return null
                }

            val tripDistanceText = AccessibilityHelper.findNodeByViewId(rootNode, "uda_details_distance_text_view")?.text?.toString()
            val durationText = AccessibilityHelper.findNodeByViewId(rootNode, "uda_details_duration_text_view")?.text?.toString()

            val tripDistance = DistanceParser.parseDistance(tripDistanceText)
            val estimatedTime = DistanceParser.parseDuration(durationText)
            val customerDistance = extractCustomerDistance(rootNode)

            val acceptBtn = findAcceptButton(rootNode)

            Log.d(TAG, """
                오퍼 파싱 성공 ($confidence):
                - 출발지: $pickupAddress
                - 도착지: $dropoffAddress
                - 고객 거리: ${customerDistance}km
                - 여행 거리: ${tripDistance}km
                - 버튼 발견: ${acceptBtn != null}
            """.trimIndent())

            val offer = UberOffer(
                offerUuid = UUID.randomUUID().toString(),
                pickupLocation = pickupAddress,
                dropoffLocation = dropoffAddress,
                customerDistance = customerDistance,
                tripDistance = tripDistance,
                estimatedFare = 0,
                estimatedTime = estimatedTime,
                acceptButtonBounds = null,
                acceptButtonNode = acceptBtn,
                parseConfidence = confidence
            )

            RemoteLogger.logParseResult(
                true,
                ParsedOfferData(
                    offerUuid = offer.offerUuid,
                    pickup = offer.pickupLocation,
                    dropoff = offer.dropoffLocation,
                    customerDistance = offer.customerDistance,
                    tripDistance = offer.tripDistance,
                    parseConfidence = offer.parseConfidence.name,
                    acceptButtonFound = offer.acceptButtonNode != null
                ),
                null
            )

            return offer
        } catch (e: Exception) {
            Log.e(TAG, "ViewId 파싱 오류: ${e.message}", e)
            RemoteLogger.logParseResult(false, null, "ViewId 파싱 오류: ${e.message}")
            return null
        }
    }
    
    /** 출발지/도착지 추출 — SIMPLE MODE: 신뢰 ViewId만 */
    private fun findAddresses(rootNode: AccessibilityNodeInfo): Triple<String, String, ParseConfidence>? {
        fun looksLikeAddress(s: String?): Boolean {
            if (s.isNullOrBlank()) return false
            if (s.length < 5) return false
            val addrTerms = listOf("시", "구", "동", "로", "길", "역", "터미널")
            return addrTerms.any { s.contains(it) }
        }

        // 1순위: 전체 화면 ViewId
        val pickup2 = AccessibilityHelper.findNodeByViewId(rootNode, "uda_details_pickup_address_text_view")?.text?.toString()
        val dropoff2 = AccessibilityHelper.findNodeByViewId(rootNode, "uda_details_dropoff_address_text_view")?.text?.toString()
        if (looksLikeAddress(pickup2) && looksLikeAddress(dropoff2)) {
            Log.d(TAG, "주소 추출: 전체화면 ViewId (strict)")
            return Triple(pickup2!!, dropoff2!!, ParseConfidence.HIGH)
        }

        // 2순위: 카드 뷰 ViewId
        val pickup3 = AccessibilityHelper.findNodeByViewId(rootNode, "pick_up_address")?.text?.toString()
        val dropoff3 = AccessibilityHelper.findNodeByViewId(rootNode, "drop_off_address")?.text?.toString()
        if (looksLikeAddress(pickup3) && looksLikeAddress(dropoff3)) {
            Log.d(TAG, "주소 추출: 카드뷰 ViewId (strict)")
            return Triple(pickup3!!, dropoff3!!, ParseConfidence.MEDIUM)
        }

        // SIMPLE: 나머지 모든 fallback 비활성화
        return null
    }

    private fun extractCustomerDistance(rootNode: AccessibilityNodeInfo): Double {
        val mapLabel = AccessibilityHelper.findNodeByViewId(rootNode, "ub__upfront_offer_map_label")
        if (mapLabel != null) {
            val labelText = mapLabel.text?.toString() ?: ""
            val distance = DistanceParser.parseDistance(labelText)
            if (distance > 0) return distance
        }

        val allText = AccessibilityHelper.extractAllText(rootNode)

        // 0순위: (Xkm) 형태 — "1분 미만(0km) 남음", "11분(3.8km) 남음" 모두 매칭
        val parenPattern = Regex("\\(([\\d.]+)km\\)")
        val parenMatch = parenPattern.find(allText)
        if (parenMatch != null) {
            val d = parenMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            if (d >= 0) return d  // 0km도 유효 (아주 가까운 거리)
        }

        val patterns = listOf(
            Regex("\\d+분\\(([\\d.]+)km\\)\\s*남음"),          // "11분(3.8km) 남음" ← 실제 UI
            Regex("픽업까지\\s*([\\d.]+)\\s*km"),               // "픽업까지 3.8km"
            Regex("([\\d.]+)\\s*km\\s*away", RegexOption.IGNORE_CASE),
            Regex("([\\d.]+)\\s*km\\s*to\\s*pickup", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(allText)
            if (match != null) {
                val distance = match.groupValues[1].toDoubleOrNull() ?: 0.0
                if (distance > 0) return distance
            }
        }

        return 2.0 // 기본값
    }
    
    private fun findAcceptButton(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 1순위: 텍스트 — virtual view는 isClickable=false이므로 clickable 체크 없이 직접 반환
        val textVariants = listOf("콜 수락", "수락", "확인", "Accept", "ACCEPT")
        for (text in textVariants) {
            try {
                val nodes = rootNode.findAccessibilityNodeInfosByText(text) ?: continue
                val node = nodes.firstOrNull { it.text?.toString() == text } ?: nodes.firstOrNull()
                if (node != null) {
                    Log.d(TAG, "버튼 발견: text=${node.text} isClickable=${node.isClickable} cls=${node.className}")
                    return node
                }
            } catch (_: Exception) {}
        }

        // 2순위: ViewId — isClickable 무관하게 반환 (performAction은 non-clickable 노드도 동작 가능)
        val viewIds = listOf(
            "uda_details_accept_button",
            "upfront_offer_configurable_details_accept_button",
            "upfront_offer_configurable_details_auditable_accept_button"
        )
        for (viewId in viewIds) {
            val btn = AccessibilityHelper.findNodeByViewId(rootNode, viewId)
            if (btn != null) return btn
        }

        return null
    }
}
