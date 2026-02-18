package com.uber.autoaccept.state

import android.view.accessibility.AccessibilityNodeInfo
import com.uber.autoaccept.model.AppState
import com.uber.autoaccept.model.StateEvent

/**
 * 상태 핸들러 인터페이스
 * Vortex의 StateHandler 패턴을 따름
 */
interface IStateHandler {
    /**
     * 이 핸들러가 처리할 수 있는 상태인지 확인
     */
    fun canHandle(state: AppState): Boolean
    
    /**
     * 상태 처리 및 다음 이벤트 생성
     */
    suspend fun handle(state: AppState, rootNode: AccessibilityNodeInfo?): StateEvent?
}

/**
 * 기본 상태 핸들러 추상 클래스
 */
abstract class BaseStateHandler : IStateHandler {
    protected val TAG = this::class.simpleName ?: "StateHandler"
    
    /**
     * 안전한 노드 접근
     */
    protected fun AccessibilityNodeInfo?.isValid(): Boolean {
        if (this == null) return false
        return try {
            this.childCount
            true
        } catch (e: IllegalStateException) {
            false
        }
    }
}
