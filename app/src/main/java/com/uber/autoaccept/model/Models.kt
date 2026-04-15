package com.uber.autoaccept.model

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

enum class FilterMode {
    ENABLED,
    DISABLED
}

data class FilterSettings(
    val mode: FilterMode = FilterMode.ENABLED,
    val maxCustomerDistance: Double = 5.0,
    val pickupKeywords: List<String> = listOf("특별시"),
    val airportKeywords: List<String> = listOf(
        "인천공항",
        "인천국제",
        "제1터미널",
        "제2터미널",
        "공항로",
        "공항연결로",
        "여객터미널로",
        "Incheon Int",
        "영종해안남로",
        "국제여객터미널",
        "국내여객터미널"
    ),
    val enabledConditions: Set<Int> = setOf(4)
)

data class OfferTraceContext(
    val traceId: String,
    val detectedAtMs: Long = System.currentTimeMillis(),
    val detectionSource: String? = null,
    val detectionStage: String? = null
)

data class UberOffer(
    val offerUuid: String,
    val traceContext: OfferTraceContext? = null,
    val pickupLocation: String,
    val dropoffLocation: String,
    val customerDistance: Double,
    val tripDistance: Double,
    val estimatedFare: Int,
    val estimatedTime: Int,
    val acceptButtonBounds: Rect?,
    val acceptButtonNode: AccessibilityNodeInfo?,
    val parseConfidence: ParseConfidence,
    val parserSource: String? = null,
    val pickupViewId: String? = null,
    val dropoffViewId: String? = null,
    val pickupValidated: Boolean = false,
    val dropoffValidated: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

enum class ParseConfidence {
    HIGH,
    MEDIUM,
    LOW
}

sealed class FilterResult {
    abstract val reasons: List<String>
    abstract val matchedConditions: List<Int>
    abstract val enabledConditions: Set<Int>
    abstract val keywordHits: Map<String, List<String>>
    abstract val summary: String

    data class Accepted(
        override val reasons: List<String>,
        override val matchedConditions: List<Int>,
        override val enabledConditions: Set<Int>,
        override val keywordHits: Map<String, List<String>> = emptyMap(),
        override val summary: String
    ) : FilterResult()

    data class Rejected(
        override val reasons: List<String>,
        override val matchedConditions: List<Int>,
        override val enabledConditions: Set<Int>,
        override val keywordHits: Map<String, List<String>> = emptyMap(),
        override val summary: String,
        val rejectCode: String
    ) : FilterResult()
}

data class AppConfig(
    val filterSettings: FilterSettings = FilterSettings(),
    val enableShizuku: Boolean = true,
    val enableLogging: Boolean = true,
    val autoAcceptDelay: Long = 200L,
    val humanizationEnabled: Boolean = true,
    val remoteLoggingEnabled: Boolean = true,
    val deviceId: String = ""
)
