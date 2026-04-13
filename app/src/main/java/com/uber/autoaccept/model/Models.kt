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

data class UberOffer(
    val offerUuid: String,
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
    data class Accepted(val reasons: List<String>) : FilterResult()
    data class Rejected(val reasons: List<String>) : FilterResult()
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
