package com.uber.autoaccept.engine

import android.util.Log
import com.uber.autoaccept.model.AppState
import com.uber.autoaccept.model.StateEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 상태 머신 엔진
 * Vortex의 상태 머신 패턴을 구현
 */
class StateMachine {
    private val _currentState = MutableStateFlow<AppState>(AppState.Idle)
    val currentState: StateFlow<AppState> = _currentState.asStateFlow()
    
    private val stateHistory = mutableListOf<StateTransition>()
    
    companion object {
        private const val TAG = "StateMachine"
        private const val MAX_HISTORY_SIZE = 100
    }
    
    /**
     * 상태 전환 기록
     */
    data class StateTransition(
        val from: AppState,
        val to: AppState,
        val event: StateEvent,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * 이벤트 처리 및 상태 전환
     */
    fun handleEvent(event: StateEvent) {
        val currentState = _currentState.value
        val nextState = transition(currentState, event)
        
        if (nextState != currentState) {
            Log.d(TAG, "State transition: ${currentState::class.simpleName} -> ${nextState::class.simpleName} (event: ${event::class.simpleName})")
            
            // 상태 전환 기록
            recordTransition(currentState, nextState, event)
            
            // 상태 업데이트
            _currentState.value = nextState
            
            // 상태 진입 콜백
            onStateEntered(nextState)
        }
    }
    
    /**
     * 상태 전환 로직
     * Vortex의 상태 전환 규칙을 따름
     */
    private fun transition(current: AppState, event: StateEvent): AppState {
        return when (current) {
            is AppState.Idle -> {
                when (event) {
                    is StateEvent.UberAppOpened -> AppState.Online
                    is StateEvent.DriverWentOnline -> AppState.Online
                    else -> current
                }
            }
            
            is AppState.Online -> {
                when (event) {
                    is StateEvent.NewOfferAppeared -> AppState.OfferDetected(
                        com.uber.autoaccept.model.UberOffer(
                            offerUuid = event.traceContext.traceId,
                            traceContext = event.traceContext,
                            pickupLocation = "",
                            dropoffLocation = "",
                            customerDistance = 0.0,
                            tripDistance = 0.0,
                            estimatedFare = 0,
                            estimatedTime = 0,
                            acceptButtonBounds = null,
                            acceptButtonNode = event.rawNode,
                            parseConfidence = com.uber.autoaccept.model.ParseConfidence.HIGH
                        )
                    )
                    is StateEvent.UberAppClosed -> AppState.Idle
                    is StateEvent.DriverWentOffline -> AppState.Idle
                    else -> current
                }
            }
            
            is AppState.OfferDetected -> {
                when (event) {
                    is StateEvent.OfferParsed -> AppState.OfferAnalyzing(event.offer)
                    is StateEvent.ErrorOccurred -> AppState.Error(event.message, event.exception)
                    is StateEvent.Reset -> AppState.Online
                    else -> current
                }
            }
            
            is AppState.OfferAnalyzing -> {
                when (event) {
                    is StateEvent.OfferFiltered -> {
                        if (event.accepted) {
                            AppState.ReadyToAccept(current.offer)
                        } else {
                            AppState.Rejected(current.offer, event.reason)
                        }
                    }
                    is StateEvent.ErrorOccurred -> AppState.Error(event.message, event.exception)
                    is StateEvent.Reset -> AppState.Online
                    else -> current
                }
            }
            
            is AppState.ReadyToAccept -> {
                when (event) {
                    is StateEvent.AcceptButtonClicked -> AppState.Accepting(current.offer)
                    is StateEvent.ErrorOccurred -> AppState.Error(event.message, event.exception)
                    is StateEvent.Reset -> AppState.Online
                    else -> current
                }
            }
            
            is AppState.Accepting -> {
                when (event) {
                    is StateEvent.AcceptSuccess -> AppState.Accepted(current.offer, event.strategy)
                    is StateEvent.AcceptFailed -> AppState.Error("수락 실패")
                    is StateEvent.ErrorOccurred -> AppState.Error(event.message, event.exception)
                    is StateEvent.Reset -> AppState.Online
                    else -> current
                }
            }
            
            is AppState.Accepted -> {
                when (event) {
                    is StateEvent.Reset -> AppState.Online
                    is StateEvent.UberAppClosed -> AppState.Idle
                    else -> current
                }
            }
            
            is AppState.Rejected -> {
                when (event) {
                    is StateEvent.Reset -> AppState.Online
                    is StateEvent.NewOfferAppeared -> AppState.OfferDetected(
                        com.uber.autoaccept.model.UberOffer(
                            offerUuid = event.traceContext.traceId,
                            traceContext = event.traceContext,
                            pickupLocation = "",
                            dropoffLocation = "",
                            customerDistance = 0.0,
                            tripDistance = 0.0,
                            estimatedFare = 0,
                            estimatedTime = 0,
                            acceptButtonBounds = null,
                            acceptButtonNode = event.rawNode,
                            parseConfidence = com.uber.autoaccept.model.ParseConfidence.HIGH
                        )
                    )
                    else -> current
                }
            }
            
            is AppState.Error -> {
                when (event) {
                    is StateEvent.Reset -> AppState.Online
                    is StateEvent.UberAppClosed -> AppState.Idle
                    else -> current
                }
            }
        }
    }
    
    /**
     * 상태 전환 기록
     */
    private fun recordTransition(from: AppState, to: AppState, event: StateEvent) {
        stateHistory.add(StateTransition(from, to, event))
        
        // 히스토리 크기 제한
        if (stateHistory.size > MAX_HISTORY_SIZE) {
            stateHistory.removeAt(0)
        }
    }
    
    /**
     * 상태 진입 콜백
     */
    private fun onStateEntered(state: AppState) {
        when (state) {
            is AppState.Accepted -> {
                Log.i(TAG, "✅ 콜 수락 성공 [${state.strategy}]: ${state.offer.pickupLocation} -> ${state.offer.dropoffLocation}")
            }
            is AppState.Rejected -> {
                Log.w(TAG, "❌ 콜 거부: ${state.reason}")
            }
            is AppState.Error -> {
                Log.e(TAG, "⚠️ 오류 발생: ${state.message}", state.exception)
            }
            else -> {}
        }
    }
    
    /**
     * 현재 상태 가져오기
     */
    fun getCurrentState(): AppState = _currentState.value
    
    /**
     * 상태 히스토리 가져오기
     */
    fun getHistory(): List<StateTransition> = stateHistory.toList()
    
    /**
     * 상태 초기화
     */
    fun reset() {
        handleEvent(StateEvent.Reset)
    }
}
