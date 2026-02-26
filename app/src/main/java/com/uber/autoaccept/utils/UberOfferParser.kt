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
    }
    
    fun parseOfferDetails(rootNode: AccessibilityNodeInfo): UberOffer? {
        return parseByViewId(rootNode)
    }
    
    private fun parseByViewId(rootNode: AccessibilityNodeInfo): UberOffer? {
        try {
            val (pickupAddress, dropoffAddress, confidence) = findAddresses(rootNode)
                ?: run {
                    Log.w(TAG, "출발지/도착지 추출 실패")
                    RemoteLogger.logParseResult(false, null, "출발지/도착지 추출 실패")
                    return null
                }

            val tripDistanceText = AccessibilityHelper.findNodeByViewId(rootNode, "uda_details_distance_text_view")?.text?.toString()
            val durationText = AccessibilityHelper.findNodeByViewId(rootNode, "uda_details_duration_text_view")?.text?.toString()

            val tripDistance = DistanceParser.parseDistance(tripDistanceText)
            val estimatedTime = DistanceParser.parseDuration(durationText)
            val customerDistance = extractCustomerDistance(rootNode)
            val acceptButton = findAcceptButton(rootNode)

            Log.d(TAG, """
                오퍼 파싱 성공 ($confidence):
                - 출발지: $pickupAddress
                - 도착지: $dropoffAddress
                - 고객 거리: ${customerDistance}km
                - 여행 거리: ${tripDistance}km
            """.trimIndent())

            val offer = UberOffer(
                offerUuid = UUID.randomUUID().toString(),
                pickupLocation = pickupAddress,
                dropoffLocation = dropoffAddress,
                customerDistance = customerDistance,
                tripDistance = tripDistance,
                estimatedFare = 0,
                estimatedTime = estimatedTime,
                acceptButtonBounds = acceptButton?.let { AccessibilityHelper.getBounds(it) },
                acceptButtonNode = acceptButton,
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
    
    /** 출발지/도착지 3단계 fallback 추출 */
    private fun findAddresses(rootNode: AccessibilityNodeInfo): Triple<String, String, ParseConfidence>? {
        // 1순위: 텍스트 패턴 — "대한민국" 포함 노드 (실제 확인된 방식)
        try {
            val addrNodes = rootNode.findAccessibilityNodeInfosByText("대한민국")
                ?.filter { !it.text.isNullOrBlank() }
                ?: emptyList()
            if (addrNodes.size >= 2) {
                val pickup = addrNodes[0].text?.toString() ?: return null
                val dropoff = addrNodes[1].text?.toString() ?: return null
                Log.d(TAG, "주소 추출: 텍스트 패턴")
                return Triple(pickup, dropoff, ParseConfidence.MEDIUM)
            }
        } catch (e: Exception) {
            Log.w(TAG, "텍스트 패턴 매칭 실패: ${e.message}")
        }

        // 2순위: 카드 뷰 ViewId
        val pickup2 = AccessibilityHelper.findNodeByViewId(rootNode, "pick_up_address")?.text?.toString()
        val dropoff2 = AccessibilityHelper.findNodeByViewId(rootNode, "drop_off_address")?.text?.toString()
        if (pickup2 != null && dropoff2 != null) {
            Log.d(TAG, "주소 추출: 카드뷰 ViewId")
            return Triple(pickup2, dropoff2, ParseConfidence.MEDIUM)
        }

        // 3순위: 전체 화면 ViewId
        val pickup3 = AccessibilityHelper.findNodeByViewId(rootNode, "uda_details_pickup_address_text_view")?.text?.toString()
        val dropoff3 = AccessibilityHelper.findNodeByViewId(rootNode, "uda_details_dropoff_address_text_view")?.text?.toString()
        if (pickup3 != null && dropoff3 != null) {
            Log.d(TAG, "주소 추출: 전체화면 ViewId")
            return Triple(pickup3, dropoff3, ParseConfidence.HIGH)
        }

        // 모든 방법 실패 시 화면 텍스트 덤프
        val allText = AccessibilityHelper.extractAllText(rootNode)
        RemoteLogger.logParseResult(false, null, "DUMP:${allText.take(1000)}")
        RemoteLogger.flushNow()
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
        // 1순위: 텍스트 — 실제 확인된 UI 텍스트
        val textVariants = listOf("콜 수락", "수락", "확인", "Accept", "ACCEPT")
        for (text in textVariants) {
            val node = AccessibilityHelper.findNodeByText(rootNode, text)
            val btn = AccessibilityHelper.findClickableNode(node)
            if (btn != null) return btn
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
