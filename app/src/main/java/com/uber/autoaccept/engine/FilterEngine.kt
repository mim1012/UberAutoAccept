package com.uber.autoaccept.engine

import android.util.Log
import com.uber.autoaccept.model.FilterMode
import com.uber.autoaccept.model.FilterResult
import com.uber.autoaccept.model.FilterSettings
import com.uber.autoaccept.model.UberOffer

class FilterEngine(private val settings: FilterSettings) {
    companion object {
        private const val TAG = "FilterEngine"
    }

    fun isEligible(offer: UberOffer): FilterResult {
        if (settings.mode == FilterMode.DISABLED) {
            return FilterResult.Rejected(listOf("필터 비활성화"))
        }

        // 조건1: 출발지 pickupKeywords 중 하나 AND 도착지 공항 키워드 OR pickupKeywords 중 하나
        val condition1 = 1 in settings.enabledConditions &&
            settings.pickupKeywords.any { offer.pickupLocation.contains(it, ignoreCase = true) } &&
            (settings.airportKeywords.any { offer.dropoffLocation.contains(it, ignoreCase = true) } ||
                settings.pickupKeywords.any { offer.dropoffLocation.contains(it, ignoreCase = true) })

        // 조건2: 출발지 공항 키워드 (도착지 무관, 모든 콜)
        val condition2 = 2 in settings.enabledConditions &&
            settings.airportKeywords.any { offer.pickupLocation.contains(it, ignoreCase = true) }

        // 조건3: 출발지 광역시 AND 도착지 특별시 (예: 인천→서울)
        val condition3 = 3 in settings.enabledConditions &&
            offer.pickupLocation.contains("광역시", ignoreCase = true) &&
            offer.dropoffLocation.contains("특별시", ignoreCase = true)

        // 조건4: 도착지 공항 키워드 (출발지 무관)
        val condition4 = 4 in settings.enabledConditions &&
            settings.airportKeywords.any { offer.dropoffLocation.contains(it, ignoreCase = true) }

        // 조건5: 특별시출발 → 광역시행 (서울 → 인천/부산/대전 등)
        val condition5 = 5 in settings.enabledConditions &&
            offer.pickupLocation.contains("특별시", ignoreCase = true) &&
            offer.dropoffLocation.contains("광역시", ignoreCase = true)

        if (!condition1 && !condition2 && !condition3 && !condition4 && !condition5) {
            val reason = "조건 불충족 (출발: ${offer.pickupLocation} / 도착: ${offer.dropoffLocation}) [활성=${settings.enabledConditions}, kw=${settings.pickupKeywords}]"
            Log.w(TAG, "❌ $reason")
            return FilterResult.Rejected(listOf(reason))
        }

        if (offer.customerDistance > settings.maxCustomerDistance) {
            val reason = "고객거리 ${fmt(offer.customerDistance)}km > ${fmt(settings.maxCustomerDistance)}km"
            Log.w(TAG, "❌ $reason")
            return FilterResult.Rejected(listOf(reason))
        }

        val condDesc = when {
            condition1 -> "서울출발→인천공항"
            condition2 -> "인천공항출발→모든콜"
            condition3 -> "광역시출발→특별시도착"
            condition5 -> "특별시출발→광역시행"
            else -> "어디서든→인천공항행"
        }
        val reason = "$condDesc | 고객거리 ${fmt(offer.customerDistance)}km"
        Log.i(TAG, "✅ $reason: ${offer.pickupLocation} -> ${offer.dropoffLocation}")
        return FilterResult.Accepted(listOf(reason))
    }

    private fun fmt(d: Double) = String.format("%.1f", d)
}
