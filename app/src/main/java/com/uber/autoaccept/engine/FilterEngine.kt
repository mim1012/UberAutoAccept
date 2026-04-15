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

    private data class ConditionEvaluation(
        val id: Int,
        val description: String,
        val matched: Boolean,
        val keywordHits: List<String> = emptyList()
    )

    fun isEligible(offer: UberOffer): FilterResult {
        if (settings.mode == FilterMode.DISABLED) {
            return FilterResult.Rejected(
                reasons = listOf("필터 비활성화"),
                matchedConditions = emptyList(),
                enabledConditions = settings.enabledConditions,
                summary = "mode=DISABLED",
                rejectCode = "FILTER_DISABLED"
            )
        }

        val pickupKeywordHits = settings.pickupKeywords.filter { offer.pickupLocation.contains(it, ignoreCase = true) }
        val dropoffPickupKeywordHits = settings.pickupKeywords.filter { offer.dropoffLocation.contains(it, ignoreCase = true) }
        val pickupAirportHits = settings.airportKeywords.filter { offer.pickupLocation.contains(it, ignoreCase = true) }
        val dropoffAirportHits = settings.airportKeywords.filter { offer.dropoffLocation.contains(it, ignoreCase = true) }

        val evaluations = listOf(
            ConditionEvaluation(
                id = 1,
                description = "서울출발→공항/서울행",
                matched = 1 in settings.enabledConditions && pickupKeywordHits.isNotEmpty() &&
                    (dropoffAirportHits.isNotEmpty() || dropoffPickupKeywordHits.isNotEmpty()),
                keywordHits = (pickupKeywordHits + dropoffAirportHits + dropoffPickupKeywordHits).distinct()
            ),
            ConditionEvaluation(
                id = 2,
                description = "인천공항출발→어디든",
                matched = 2 in settings.enabledConditions && pickupAirportHits.isNotEmpty(),
                keywordHits = pickupAirportHits
            ),
            ConditionEvaluation(
                id = 3,
                description = "광역시출발→특별시행",
                matched = 3 in settings.enabledConditions &&
                    offer.pickupLocation.contains("광역시", ignoreCase = true) &&
                    offer.dropoffLocation.contains("특별시", ignoreCase = true),
                keywordHits = listOfNotNull(
                    "광역시".takeIf { offer.pickupLocation.contains(it, ignoreCase = true) },
                    "특별시".takeIf { offer.dropoffLocation.contains(it, ignoreCase = true) }
                )
            ),
            ConditionEvaluation(
                id = 4,
                description = "어디서든→인천공항행",
                matched = 4 in settings.enabledConditions && dropoffAirportHits.isNotEmpty(),
                keywordHits = dropoffAirportHits
            ),
            ConditionEvaluation(
                id = 5,
                description = "특별시출발→광역시중구행",
                matched = 5 in settings.enabledConditions &&
                    offer.pickupLocation.contains("특별시", ignoreCase = true) &&
                    offer.dropoffLocation.contains("광역시", ignoreCase = true) &&
                    offer.dropoffLocation.contains("중구", ignoreCase = true),
                keywordHits = listOfNotNull(
                    "특별시".takeIf { offer.pickupLocation.contains(it, ignoreCase = true) },
                    "광역시".takeIf { offer.dropoffLocation.contains(it, ignoreCase = true) },
                    "중구".takeIf { offer.dropoffLocation.contains(it, ignoreCase = true) }
                )
            )
        )

        val matchedConditions = evaluations.filter { it.matched }.map { it.id }
        val keywordHits = linkedMapOf(
            "pickup" to pickupKeywordHits,
            "dropoff_pickup_keywords" to dropoffPickupKeywordHits,
            "pickup_airport" to pickupAirportHits,
            "dropoff_airport" to dropoffAirportHits,
            "matched_conditions" to evaluations.filter { it.matched }.flatMap { it.keywordHits }.distinct()
        ).filterValues { it.isNotEmpty() }

        if (matchedConditions.isEmpty()) {
            val enabledConditionDescriptions = evaluations
                .filter { it.id in settings.enabledConditions }
                .associate { it.id.toString() to it.description }
            val reason = "조건 불충족 (출발: ${offer.pickupLocation} / 도착: ${offer.dropoffLocation})"
            val summary = "enabled=${settings.enabledConditions} matched=[] keywordHits=$keywordHits"
            Log.w(TAG, "❌ $reason | $summary")
            return FilterResult.Rejected(
                reasons = listOf(reason),
                matchedConditions = matchedConditions,
                enabledConditions = settings.enabledConditions,
                keywordHits = keywordHits + mapOf(
                    "enabled_condition_descriptions" to enabledConditionDescriptions.map { "${it.key}:${it.value}" }
                ),
                summary = summary,
                rejectCode = "NO_CONDITION_MATCH"
            )
        }

        if (offer.customerDistance > settings.maxCustomerDistance) {
            val reason = "고객거리 ${fmt(offer.customerDistance)}km > ${fmt(settings.maxCustomerDistance)}km"
            val summary = "matched=$matchedConditions enabled=${settings.enabledConditions}"
            Log.w(TAG, "❌ $reason | $summary")
            return FilterResult.Rejected(
                reasons = listOf(reason),
                matchedConditions = matchedConditions,
                enabledConditions = settings.enabledConditions,
                keywordHits = keywordHits,
                summary = summary,
                rejectCode = "CUSTOMER_DISTANCE_EXCEEDED"
            )
        }

        val matchedDescriptions = evaluations.filter { it.matched }.map { "${it.id}:${it.description}" }
        val reason = "${matchedDescriptions.joinToString()} | 고객거리 ${fmt(offer.customerDistance)}km"
        val summary = "matched=$matchedConditions enabled=${settings.enabledConditions} keywordHits=$keywordHits"
        Log.i(TAG, "✅ $reason: ${offer.pickupLocation} -> ${offer.dropoffLocation}")
        return FilterResult.Accepted(
            reasons = listOf(reason),
            matchedConditions = matchedConditions,
            enabledConditions = settings.enabledConditions,
            keywordHits = keywordHits,
            summary = summary
        )
    }

    private fun fmt(d: Double) = String.format("%.1f", d)
}
