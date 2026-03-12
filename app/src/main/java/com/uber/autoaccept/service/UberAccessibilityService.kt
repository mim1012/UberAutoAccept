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
    private var offerDetectionJob: Job? = null

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

        // 서비스 활성 상태로 설정 (중요: 접근성 이벤트를 처리하기 위함)
        ServiceState.start()

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

        // 서비스 연결 시 Uber 앱이 이미 열려있으면 즉시 Online으로 전환
        val rootNode = rootInActiveWindow
        if (rootNode?.packageName == UBER_PACKAGE) {
            Log.i(TAG, "서비스 연결 시 Uber 앱 이미 열려있음 → Online 전환")
            stateMachine.handleEvent(StateEvent.UberAppOpened)
        }

        Log.i(TAG, "초기화 완료. 모드: ${config.filterSettings.mode}")
    }
    
    /**
     * 상태 핸들러 등록
     */
    private lateinit var acceptingHandler: AcceptingHandler

    private fun registerStateHandlers() {
        acceptingHandler = AcceptingHandler()
        acceptingHandler.accessibilityService = this
        stateHandlers.clear()
        stateHandlers.add(OfferDetectedHandler(parser))
        stateHandlers.add(OfferAnalyzingHandler(filterEngine))
        stateHandlers.add(ReadyToAcceptHandler(config))
        stateHandlers.add(acceptingHandler)
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
                        val rootNode = if (state is AppState.OfferDetected) {
                            // 항상 findOfferWindow()로 재탐색:
                            // stored(rawNode).refresh()는 화면 전환 후 운행 리스트 내용을 반환할 수 있음
                            // null 반환 시 OfferDetectedHandler → ErrorOccurred → Reset
                            findOfferWindow()
                        } else {
                            rootInActiveWindow
                        }
                        // Accepting 상태: 클릭 직전에 모든 윈도우 루트 주입
                if (handler is AcceptingHandler) {
                    handler.allWindowRoots = windows?.mapNotNull { it.root } ?: emptyList()
                    Log.d(TAG, "AcceptingHandler 윈도우 주입: ${handler.allWindowRoots.size}개")
                }
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
        // 진단용 로그: 모든 접근성 이벤트 기록
        Log.d(TAG, "onAccessibilityEvent: pkg=${event.packageName}, type=${event.eventType}, serviceActive=${ServiceState.isActive()}")
        RemoteLogger.logAccessibilityEvent(event.packageName?.toString() ?: "null", event.eventType, ServiceState.isActive())

        if (event.packageName != UBER_PACKAGE) {
            Log.d(TAG, "Ignored: wrong package (${event.packageName} != $UBER_PACKAGE)")
            RemoteLogger.logDiagnostic("Wrong package", mapOf("expected" to UBER_PACKAGE, "actual" to (event.packageName ?: "null")))
            return
        }
        if (!ServiceState.isActive()) {
            Log.d(TAG, "Ignored: service not active")
            RemoteLogger.logDiagnostic("Service not active")
            return
        }

        Log.i(TAG, "✓ Processing accessibility event: type=${event.eventType}")
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
        var currentState = stateMachine.getCurrentState()

        // Idle 상태에서 컨텐츠 변경 = Uber 앱이 이미 열려있음 → Online 전환
        if (currentState is AppState.Idle) {
            stateMachine.handleEvent(StateEvent.UberAppOpened)
            currentState = stateMachine.getCurrentState()
        }

        // Online 상태에서만 새로운 오퍼 감지 (Rejected 제외: 거절 처리 중 중복 감지 방지)
        if (currentState is AppState.Online) {
            // debounce: 화면 점진적 로딩 중 과다 이벤트 필터링
            offerDetectionJob?.cancel()
            offerDetectionJob = serviceScope.launch {
                delay(400)
                if (stateMachine.getCurrentState() is AppState.Online) {
                    val offerRoot = findOfferWindow()
                    if (offerRoot != null) {
                        Log.i(TAG, "🔔 새로운 오퍼 감지!")
                        stateMachine.handleEvent(StateEvent.NewOfferAppeared(offerRoot))
                    }
                }
            }
        }
    }
    
    /**
     * 모든 윈도우에서 오퍼 창 탐색
     * - 비오퍼 화면(운행 리스트, 새로운 콜 배너 등) 제외
     * - 오퍼 화면 로딩 완료 확인 후 반환 ("대한민국" 주소 2개 이상)
     */
    private fun findOfferWindow(): android.view.accessibility.AccessibilityNodeInfo? {
        val helper = com.uber.autoaccept.utils.AccessibilityHelper
        val roots = windows?.mapNotNull { it.root }?.ifEmpty { null }
            ?: listOfNotNull(rootInActiveWindow)

        for (root in roots) {
            // 비오퍼 화면 제외
            if (helper.findNodeByText(root, "운행 리스트") != null) continue
            if (helper.findNodeByText(root, "새로운 콜") != null) continue
            if (helper.findNodeByText(root, "지금은 요청이 없습니다") != null) continue
            if (helper.findNodeByText(root, "요청 1건 매칭") != null) continue

            // 1순위: "콜 수락" 텍스트 (오퍼 화면 확실)
            if (helper.findNodeByText(root, "콜 수락", exactMatch = true) != null) return root

            // 2순위: "대한민국" 주소 2개 이상 (오퍼 화면 로딩 완료)
            try {
                val addrNodes = root.findAccessibilityNodeInfosByText("대한민국")
                    ?.filter { !it.text.isNullOrBlank() } ?: emptyList()
                if (addrNodes.size >= 2) return root
            } catch (_: Exception) {}

            // 3순위: ViewId 기반 감지 (health 로깅 겸용)
            val pickupNode = helper.findNodeByViewId(root, "uda_details_pickup_address_text_view")
            val dropoffNode = helper.findNodeByViewId(root, "uda_details_dropoff_address_text_view")
            RemoteLogger.logViewIdHealth("uda_details_pickup_address_text_view", pickupNode != null)
            RemoteLogger.logViewIdHealth("uda_details_dropoff_address_text_view", dropoffNode != null)
            if (pickupNode != null && dropoffNode != null) return root
        }
        return null
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
