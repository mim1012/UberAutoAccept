package com.uber.autoaccept.state

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.uber.autoaccept.engine.FilterEngine
import com.uber.autoaccept.model.*
import com.uber.autoaccept.utils.UberOfferParser
import kotlinx.coroutines.delay

/**
 * OfferDetected 상태 핸들러
 * 오퍼 정보를 파싱
 */
class OfferDetectedHandler(private val parser: UberOfferParser) : BaseStateHandler() {
    override fun canHandle(state: AppState): Boolean = state is AppState.OfferDetected
    
    override suspend fun handle(state: AppState, rootNode: AccessibilityNodeInfo?): StateEvent? {
        if (state !is AppState.OfferDetected || !rootNode.isValid()) {
            return StateEvent.ErrorOccurred("Invalid state or node")
        }
        
        Log.d(TAG, "오퍼 파싱 시작...")
        
        val offer = parser.parseOfferDetails(rootNode)
        
        return if (offer != null) {
            StateEvent.OfferParsed(offer)
        } else {
            StateEvent.ErrorOccurred("오퍼 파싱 실패")
        }
    }
}

/**
 * OfferAnalyzing 상태 핸들러
 * 오퍼를 필터링하여 수락 여부 결정
 */
class OfferAnalyzingHandler(private val filterEngine: FilterEngine) : BaseStateHandler() {
    override fun canHandle(state: AppState): Boolean = state is AppState.OfferAnalyzing
    
    override suspend fun handle(state: AppState, rootNode: AccessibilityNodeInfo?): StateEvent? {
        if (state !is AppState.OfferAnalyzing) {
            return StateEvent.ErrorOccurred("Invalid state")
        }
        
        Log.d(TAG, "오퍼 필터링 중...")
        
        val result = filterEngine.isEligible(state.offer)
        
        return when (result) {
            is FilterResult.Accepted -> {
                StateEvent.OfferFiltered(accepted = true, reason = result.reasons.joinToString())
            }
            is FilterResult.Rejected -> {
                StateEvent.OfferFiltered(accepted = false, reason = result.reasons.joinToString())
            }
        }
    }
}

/**
 * ReadyToAccept 상태 핸들러
 * 수락 버튼 클릭 준비
 */
class ReadyToAcceptHandler(private val config: AppConfig) : BaseStateHandler() {
    override fun canHandle(state: AppState): Boolean = state is AppState.ReadyToAccept
    
    override suspend fun handle(state: AppState, rootNode: AccessibilityNodeInfo?): StateEvent? {
        if (state !is AppState.ReadyToAccept) {
            return StateEvent.ErrorOccurred("Invalid state")
        }
        
        Log.d(TAG, "수락 준비 중... (딜레이: ${config.autoAcceptDelay}ms)")
        
        // 인간 행동 시뮬레이션: 랜덤 딜레이
        if (config.humanizationEnabled) {
            val randomDelay = config.autoAcceptDelay + (0..200).random()
            delay(randomDelay)
        } else {
            delay(config.autoAcceptDelay)
        }
        
        return StateEvent.AcceptButtonClicked
    }
}

/**
 * Accepting 상태 핸들러
 * 실제 수락 버튼 클릭 실행
 */
class AcceptingHandler : BaseStateHandler() {
    override fun canHandle(state: AppState): Boolean = state is AppState.Accepting
    
    override suspend fun handle(state: AppState, rootNode: AccessibilityNodeInfo?): StateEvent? {
        if (state !is AppState.Accepting) {
            return StateEvent.ErrorOccurred("Invalid state")
        }
        
        Log.d(TAG, "수락 버튼 클릭 시도...")
        
        val acceptButton = state.offer.acceptButtonNode
        
        if (acceptButton != null && acceptButton.isClickable) {
            val success = acceptButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            
            return if (success) {
                Log.i(TAG, "✅ 수락 버튼 클릭 성공")
                StateEvent.AcceptSuccess
            } else {
                Log.e(TAG, "❌ 수락 버튼 클릭 실패")
                StateEvent.AcceptFailed
            }
        } else {
            Log.e(TAG, "❌ 수락 버튼을 찾을 수 없음")
            return StateEvent.ErrorOccurred("수락 버튼 없음")
        }
    }
}

/**
 * Accepted 상태 핸들러
 * 수락 완료 후 처리
 */
class AcceptedHandler : BaseStateHandler() {
    override fun canHandle(state: AppState): Boolean = state is AppState.Accepted
    
    override suspend fun handle(state: AppState, rootNode: AccessibilityNodeInfo?): StateEvent? {
        if (state !is AppState.Accepted) {
            return null
        }
        
        Log.i(TAG, "콜 수락 완료. 다음 콜 대기 중...")
        
        // 3초 후 온라인 상태로 복귀
        delay(3000)
        
        return StateEvent.Reset
    }
}

/**
 * Rejected 상태 핸들러
 * 거부 후 처리
 */
class RejectedHandler : BaseStateHandler() {
    override fun canHandle(state: AppState): Boolean = state is AppState.Rejected
    
    override suspend fun handle(state: AppState, rootNode: AccessibilityNodeInfo?): StateEvent? {
        if (state !is AppState.Rejected) {
            return null
        }
        
        Log.w(TAG, "콜 거부됨: ${state.reason}")
        
        // 1초 후 온라인 상태로 복귀
        delay(1000)
        
        return StateEvent.Reset
    }
}

/**
 * Error 상태 핸들러
 * 오류 후 복구
 */
class ErrorHandler : BaseStateHandler() {
    override fun canHandle(state: AppState): Boolean = state is AppState.Error
    
    override suspend fun handle(state: AppState, rootNode: AccessibilityNodeInfo?): StateEvent? {
        if (state !is AppState.Error) {
            return null
        }
        
        Log.e(TAG, "오류 발생: ${state.message}", state.exception)
        
        // 2초 후 온라인 상태로 복귀
        delay(2000)
        
        return StateEvent.Reset
    }
}
