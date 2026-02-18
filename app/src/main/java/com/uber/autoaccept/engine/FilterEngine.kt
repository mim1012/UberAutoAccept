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
        val reasons = mutableListOf<String>()
        
        if (!isCustomerDistanceValid(offer.customerDistance, reasons)) {
            return FilterResult.Rejected(reasons)
        }
        
        val isAirport = isAirportMode(offer)
        val isSeoul = isSeoulEntryMode(offer)
        
        val accepted = when (settings.mode) {
            FilterMode.AIRPORT -> isAirport
            FilterMode.SEOUL_ENTRY -> isSeoul
            FilterMode.BOTH -> isAirport || isSeoul
            FilterMode.DISABLED -> false
        }
        
        return if (accepted) {
            reasons.add("조건 충족")
            Log.i(TAG, "✅ 오퍼 수락: ${offer.pickupLocation} -> ${offer.dropoffLocation}")
            FilterResult.Accepted(reasons)
        } else {
            reasons.add("지정된 모드 조건 불일치")
            Log.w(TAG, "❌ 오퍼 거부: ${reasons.joinToString()}")
            FilterResult.Rejected(reasons)
        }
    }
    
    private fun isCustomerDistanceValid(distance: Double, reasons: MutableList<String>): Boolean {
        val isValid = distance >= settings.minCustomerDistance && distance <= settings.maxCustomerDistance
        if (!isValid) {
            reasons.add("고객 거리(${String.format("%.1f", distance)}km) 범위 이탈")
        }
        return isValid
    }
    
    private fun isAirportMode(offer: UberOffer): Boolean {
        return settings.airportKeywords.any { 
            offer.dropoffLocation.contains(it, ignoreCase = true) 
        }
    }
    
    private fun isSeoulEntryMode(offer: UberOffer): Boolean {
        return settings.seoulKeywords.any { 
            offer.dropoffLocation.contains(it, ignoreCase = true) 
        }
    }
}
