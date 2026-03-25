package com.uber.autoaccept.model

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 필터링 모드
 */
enum class FilterMode {
    ENABLED,        // 활성화
    DISABLED        // 비활성화
}

/**
 * 필터 설정
 */
data class FilterSettings(
    val mode: FilterMode = FilterMode.ENABLED,
    val maxCustomerDistance: Double = 5.0,
    val pickupKeywords: List<String> = listOf("특별시"),
    val airportKeywords: List<String> = listOf(
        "인천공항", "인천국제", "용유동", "운서동", "운서1동", "운서2동",
        "공항로", "공항연결로", "제2터미널대로", "Incheon Int",
        "영종해안남로", "제1여객터미널", "제2여객터미널"
    ),
    val enabledConditions: Set<Int> = setOf(4)
)

/**
 * Uber 오퍼 정보
 */
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
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 파싱 신뢰도
 */
enum class ParseConfidence {
    HIGH,       // ViewId 기반
    MEDIUM,     // 정규식 기반
    LOW         // OCR 기반
}

/**
 * 필터링 결과
 */
sealed class FilterResult {
    data class Accepted(val reasons: List<String>) : FilterResult()
    data class Rejected(val reasons: List<String>) : FilterResult()
}

/**
 * 앱 설정
 */
data class AppConfig(
    val filterSettings: FilterSettings = FilterSettings(),
    val enableShizuku: Boolean = true,
    val enableLogging: Boolean = true,
    val autoAcceptDelay: Long = 200L,  // 수락 버튼 클릭 전 딜레이 (ms)
    val humanizationEnabled: Boolean = true,  // 인간 행동 시뮬레이션
    val remoteLoggingEnabled: Boolean = true,
    val deviceId: String = ""
)
