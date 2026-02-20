package com.uber.autoaccept.model

/**
 * 애플리케이션 상태 정의
 * Vortex의 상태 머신 패턴을 따름
 */
sealed class AppState {
    /**
     * 대기 상태 - Uber 앱이 실행되지 않았거나 메인 화면
     */
    object Idle : AppState()
    
    /**
     * 온라인 상태 - 드라이버가 온라인이고 콜 대기 중
     */
    object Online : AppState()
    
    /**
     * 오퍼 감지 상태 - 새로운 콜이 화면에 표시됨
     */
    data class OfferDetected(val offer: UberOffer) : AppState()
    
    /**
     * 오퍼 분석 상태 - 콜 정보를 파싱하고 필터링 중
     */
    data class OfferAnalyzing(val offer: UberOffer) : AppState()
    
    /**
     * 수락 대기 상태 - 조건에 맞아서 수락 버튼 클릭 준비
     */
    data class ReadyToAccept(val offer: UberOffer) : AppState()
    
    /**
     * 수락 중 상태 - 수락 버튼 클릭 실행 중
     */
    data class Accepting(val offer: UberOffer) : AppState()
    
    /**
     * 수락 완료 상태 - 콜 수락 성공
     */
    data class Accepted(val offer: UberOffer, val strategy: String = "") : AppState()
    
    /**
     * 거부 상태 - 조건에 맞지 않아 무시
     */
    data class Rejected(val offer: UberOffer, val reason: String) : AppState()
    
    /**
     * 오류 상태 - 예외 발생
     */
    data class Error(val message: String, val exception: Throwable? = null) : AppState()
}

/**
 * 상태 전환 이벤트
 */
sealed class StateEvent {
    object UberAppOpened : StateEvent()
    object UberAppClosed : StateEvent()
    object DriverWentOnline : StateEvent()
    object DriverWentOffline : StateEvent()
    data class NewOfferAppeared(val rawNode: android.view.accessibility.AccessibilityNodeInfo) : StateEvent()
    data class OfferParsed(val offer: UberOffer) : StateEvent()
    data class OfferFiltered(val accepted: Boolean, val reason: String) : StateEvent()
    object AcceptButtonClicked : StateEvent()
    data class AcceptSuccess(val strategy: String = "unknown") : StateEvent()
    object AcceptFailed : StateEvent()
    data class ErrorOccurred(val message: String, val exception: Throwable? = null) : StateEvent()
    object Reset : StateEvent()
}
