package com.uber.autoaccept.model

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 필터링 모드
 */
enum class FilterMode {
    AIRPORT,        // 인천공항 모드
    SEOUL_ENTRY,    // 서울 진입 모드
    BOTH,           // 두 모드 모두
    DISABLED        // 비활성화
}

/**
 * 필터 설정
 */
data class FilterSettings(
    val mode: FilterMode = FilterMode.BOTH,
    val minCustomerDistance: Double = 1.0,
    val maxCustomerDistance: Double = 3.0,
    val minFare: Int = 0,
    val airportKeywords: List<String> = listOf("인천공항", "인천국제공항", "Incheon Airport", "ICN"),
    val seoulKeywords: List<String> = listOf(
        "서울", "Seoul", "강남", "강북", "종로", "중구", "용산", "성동", "광진", "동대문",
        "중랑", "성북", "도봉", "노원", "은평", "서대문", "마포", "양천", "강서",
        "구로", "금천", "영등포", "동작", "관악", "서초", "송파", "강동"
    )
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
    val humanizationEnabled: Boolean = true  // 인간 행동 시뮬레이션
)
