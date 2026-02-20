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

        val pickupFromSeoul = isPickupSeoul(offer)
        val pickupFromAirport = isPickupAirport(offer)

        // 시나리오 1: 출발지 서울 → 도착지 인천공항 키워드 + 고객거리 3km 이내
        if (pickupFromSeoul && (settings.mode == FilterMode.SEOUL_ENTRY || settings.mode == FilterMode.BOTH)) {
            val dropoffToAirport = settings.airportKeywords.any {
                offer.dropoffLocation.contains(it, ignoreCase = true)
            }
            if (dropoffToAirport) {
                if (offer.customerDistance <= settings.seoulPickupMaxDistance) {
                    val reason = "서울출발→인천공항 (고객거리 ${fmt(offer.customerDistance)}km)"
                    Log.i(TAG, "✅ $reason: ${offer.pickupLocation} -> ${offer.dropoffLocation}")
                    return FilterResult.Accepted(listOf(reason))
                } else {
                    val reason = "서울출발→인천공항이나 고객거리 ${fmt(offer.customerDistance)}km > ${fmt(settings.seoulPickupMaxDistance)}km"
                    Log.w(TAG, "❌ $reason")
                    return FilterResult.Rejected(listOf(reason))
                }
            }
        }

        // 시나리오 2: 출발지 인천공항 → 모든 콜 수락 + 고객거리 7km 이내
        if (pickupFromAirport && (settings.mode == FilterMode.AIRPORT || settings.mode == FilterMode.BOTH)) {
            if (offer.customerDistance <= settings.airportPickupMaxDistance) {
                val reason = "인천공항출발→전체 (고객거리 ${fmt(offer.customerDistance)}km)"
                Log.i(TAG, "✅ $reason: ${offer.pickupLocation} -> ${offer.dropoffLocation}")
                return FilterResult.Accepted(listOf(reason))
            } else {
                val reason = "인천공항출발이나 고객거리 ${fmt(offer.customerDistance)}km > ${fmt(settings.airportPickupMaxDistance)}km"
                Log.w(TAG, "❌ $reason")
                return FilterResult.Rejected(listOf(reason))
            }
        }

        val reason = "출발지가 서울/인천공항 조건에 해당하지 않음 (출발: ${offer.pickupLocation})"
        Log.w(TAG, "❌ $reason")
        return FilterResult.Rejected(listOf(reason))
    }

    /** 출발지가 서울 지역인지 */
    private fun isPickupSeoul(offer: UberOffer): Boolean {
        return settings.seoulKeywords.any {
            offer.pickupLocation.contains(it, ignoreCase = true)
        }
    }

    /** 출발지가 인천공항 지역인지 */
    private fun isPickupAirport(offer: UberOffer): Boolean {
        return settings.airportKeywords.any {
            offer.pickupLocation.contains(it, ignoreCase = true)
        }
    }

    private fun fmt(d: Double) = String.format("%.1f", d)
}
