package com.uber.autoaccept.utils

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
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
            val pickupAddress = AccessibilityHelper.findNodeByViewId(rootNode, "uda_details_pickup_address_text_view")?.text?.toString()
            val dropoffAddress = AccessibilityHelper.findNodeByViewId(rootNode, "uda_details_dropoff_address_text_view")?.text?.toString()
            val tripDistanceText = AccessibilityHelper.findNodeByViewId(rootNode, "uda_details_distance_text_view")?.text?.toString()
            val durationText = AccessibilityHelper.findNodeByViewId(rootNode, "uda_details_duration_text_view")?.text?.toString()
            
            if (pickupAddress == null || dropoffAddress == null) {
                Log.w(TAG, "필수 정보 누락: pickup=$pickupAddress, dropoff=$dropoffAddress")
                return null
            }
            
            val tripDistance = DistanceParser.parseDistance(tripDistanceText)
            val estimatedTime = DistanceParser.parseDuration(durationText)
            val customerDistance = extractCustomerDistance(rootNode)
            val acceptButton = findAcceptButton(rootNode)
            
            Log.d(TAG, """
                오퍼 파싱 성공:
                - 출발지: $pickupAddress
                - 도착지: $dropoffAddress
                - 고객 거리: ${customerDistance}km
                - 여행 거리: ${tripDistance}km
            """.trimIndent())
            
            return UberOffer(
                offerUuid = UUID.randomUUID().toString(),
                pickupLocation = pickupAddress,
                dropoffLocation = dropoffAddress,
                customerDistance = customerDistance,
                tripDistance = tripDistance,
                estimatedFare = 0,
                estimatedTime = estimatedTime,
                acceptButtonBounds = acceptButton?.let { AccessibilityHelper.getBounds(it) },
                acceptButtonNode = acceptButton,
                parseConfidence = ParseConfidence.HIGH
            )
        } catch (e: Exception) {
            Log.e(TAG, "ViewId 파싱 오류: ${e.message}", e)
            return null
        }
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
            Regex("([\\d.]+)\\s*km\\s*away", RegexOption.IGNORE_CASE),
            Regex("([\\d.]+)\\s*km\\s*to\\s*pickup", RegexOption.IGNORE_CASE),
            Regex("픽업까지\\s*([\\d.]+)\\s*km")
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
        val viewIds = listOf(
            "upfront_offer_configurable_details_accept_button",
            "upfront_offer_configurable_details_auditable_accept_button"
        )
        
        for (viewId in viewIds) {
            val btn = AccessibilityHelper.findNodeByViewId(rootNode, viewId)
            if (btn != null && btn.isClickable) return btn
        }
        
        val textVariants = listOf("Accept", "ACCEPT", "수락", "확인")
        for (text in textVariants) {
            val btn = AccessibilityHelper.findNodeByText(rootNode, text)
            if (btn != null && btn.isClickable) return btn
        }
        
        return null
    }
}
