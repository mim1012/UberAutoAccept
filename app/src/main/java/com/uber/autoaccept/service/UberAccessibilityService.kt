package com.uber.autoaccept.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.uber.autoaccept.engine.FilterEngine
import com.uber.autoaccept.engine.StateMachine
import com.uber.autoaccept.logging.RemoteLogger
import com.uber.autoaccept.model.*
import com.uber.autoaccept.state.*
import com.uber.autoaccept.utils.UberOfferParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.UUID

/**
 * Uber 자동 수락 AccessibilityService
 * Vortex의 메인 루프 패턴을 따름
 */
class UberAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "UberAccessibilityService"
        private const val UBER_PACKAGE = "com.ubercab.driver"
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val stateMachine = StateMachine()

    private lateinit var parser: UberOfferParser
    private lateinit var filterEngine: FilterEngine
    private lateinit var config: AppConfig

    private val stateHandlers = mutableListOf<IStateHandler>()

    private val configReloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i(TAG, "Config reload requested")
            config = loadConfig()
            filterEngine = FilterEngine(config.filterSettings)
            registerStateHandlers()
        }
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "서비스 연결됨")

        // 설정 로드
        config = loadConfig()

        // 컴포넌트 초기화
        parser = UberOfferParser()
        filterEngine = FilterEngine(config.filterSettings)

        // Config reload receiver 등록
        registerReceiver(configReloadReceiver, IntentFilter("com.uber.autoaccept.RELOAD_CONFIG"),
            Context.RECEIVER_NOT_EXPORTED)

        // 원격 로깅 초기화 (Supabase 사용)
        RemoteLogger.initialize(this, config.deviceId, config.remoteLoggingEnabled)
        RemoteLogger.currentStateSupplier = { stateMachine.getCurrentState()::class.simpleName ?: "unknown" }
        RemoteLogger.logServiceConnected()
        RemoteLogger.flushNow()

        // 상태 핸들러 등록
        registerStateHandlers()

        // 상태 관찰 시작
        observeState()

        Log.i(TAG, "초기화 완료. 모드: ${config.filterSettings.mode}")
    }
    
    /**
     * 상태 핸들러 등록
     */
    private fun registerStateHandlers() {
        stateHandlers.clear()
        stateHandlers.add(OfferDetectedHandler(parser))
        stateHandlers.add(OfferAnalyzingHandler(filterEngine))
        stateHandlers.add(ReadyToAcceptHandler(config))
        stateHandlers.add(AcceptingHandler())
        stateHandlers.add(AcceptedHandler())
        stateHandlers.add(RejectedHandler())
        stateHandlers.add(ErrorHandler())
    }
    
    /**
     * 상태 관찰 및 자동 처리
     * Vortex의 메인 루프 패턴
     */
    private fun observeState() {
        serviceScope.launch {
            stateMachine.currentState.collect { state ->
                Log.d(TAG, "Current State: ${state::class.simpleName}")
                
                // 상태에 맞는 핸들러 찾기
                val handler = stateHandlers.firstOrNull { it.canHandle(state) }
                
                if (handler != null) {
                    try {
                        val rootNode = rootInActiveWindow
                        val event = handler.handle(state, rootNode)
                        
                        if (event != null) {
                            stateMachine.handleEvent(event)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Handler error: ${e.message}", e)
                        stateMachine.handleEvent(StateEvent.ErrorOccurred(e.message ?: "Unknown error", e))
                    }
                }
            }
        }
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName != UBER_PACKAGE) return
        if (!ServiceState.isActive()) return
        
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handleWindowContentChanged(event)
            }
        }
    }
    
    /**
     * 윈도우 상태 변경 처리
     */
    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val currentState = stateMachine.getCurrentState()
        
        if (currentState is AppState.Idle) {
            stateMachine.handleEvent(StateEvent.UberAppOpened)
        }
    }
    
    /**
     * 윈도우 콘텐츠 변경 처리
     */
    private fun handleWindowContentChanged(event: AccessibilityEvent) {
        val currentState = stateMachine.getCurrentState()
        
        // Online 상태에서만 새로운 오퍼 감지
        if (currentState is AppState.Online || currentState is AppState.Rejected) {
            val rootNode = rootInActiveWindow ?: return
            
            // 오퍼 화면 감지 (ViewId 기반)
            val offerDetected = detectOffer(rootNode)
            
            if (offerDetected) {
                Log.i(TAG, "🔔 새로운 오퍼 감지!")
                stateMachine.handleEvent(StateEvent.NewOfferAppeared(rootNode))
            }
        }
    }
    
    /**
     * 오퍼 화면 감지
     */
    private fun detectOffer(rootNode: android.view.accessibility.AccessibilityNodeInfo): Boolean {
        // 전략 1: ViewId 기반 감지
        val pickupNode = com.uber.autoaccept.utils.AccessibilityHelper.findNodeByViewId(
            rootNode, 
            "uda_details_pickup_address_text_view"
        )
        val dropoffNode = com.uber.autoaccept.utils.AccessibilityHelper.findNodeByViewId(
            rootNode, 
            "uda_details_dropoff_address_text_view"
        )
        
        RemoteLogger.logViewIdHealth("uda_details_pickup_address_text_view", pickupNode != null)
        RemoteLogger.logViewIdHealth("uda_details_dropoff_address_text_view", dropoffNode != null)

        if (pickupNode != null && dropoffNode != null) {
            return true
        }

        // 전략 2: 텍스트 기반 감지 (Fallback)
        val allText = com.uber.autoaccept.utils.AccessibilityHelper.extractAllText(rootNode)
        return allText.contains("Pickup", ignoreCase = true) && 
               allText.contains("Dropoff", ignoreCase = true)
    }
    
    /**
     * 설정 로드
     */
    private fun loadConfig(): AppConfig {
        val prefs = getSharedPreferences("uber_auto_accept", Context.MODE_PRIVATE)

        val modeString = prefs.getString("filter_mode", FilterMode.BOTH.name) ?: FilterMode.BOTH.name
        val mode = try {
            FilterMode.valueOf(modeString)
        } catch (e: Exception) {
            FilterMode.BOTH
        }

        val seoulMaxDist = prefs.getFloat("seoul_pickup_max_distance", 3.0f).toDouble()
        val airportMaxDist = prefs.getFloat("airport_pickup_max_distance", 7.0f).toDouble()

        // device_id: read or generate once
        var deviceId = prefs.getString("device_id", null)
        if (deviceId.isNullOrBlank()) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", deviceId).apply()
        }

        return AppConfig(
            filterSettings = FilterSettings(
                mode = mode,
                seoulPickupMaxDistance = seoulMaxDist,
                airportPickupMaxDistance = airportMaxDist
            ),
            enableShizuku = prefs.getBoolean("enable_shizuku", true),
            enableLogging = prefs.getBoolean("enable_logging", true),
            autoAcceptDelay = prefs.getLong("auto_accept_delay", 200L),
            humanizationEnabled = prefs.getBoolean("humanization_enabled", true),
            remoteLoggingEnabled = prefs.getBoolean("remote_logging_enabled", true),
            deviceId = deviceId
        )
    }
    
    override fun onInterrupt() {
        Log.w(TAG, "서비스 중단됨")
        RemoteLogger.logServiceDisconnected("onInterrupt")
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(configReloadReceiver) } catch (_: Exception) {}
        RemoteLogger.logServiceDisconnected("onDestroy")
        RemoteLogger.shutdown()
        serviceScope.cancel()
        Log.i(TAG, "서비스 종료됨")
    }
}
