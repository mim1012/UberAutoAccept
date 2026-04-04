package com.uber.autoaccept.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.uber.autoaccept.engine.FilterEngine
import com.uber.autoaccept.engine.StateMachine
import com.uber.autoaccept.logging.RemoteLogger
import com.uber.autoaccept.model.*
import com.uber.autoaccept.state.*
import com.uber.autoaccept.utils.OfferCardDetector
import com.uber.autoaccept.utils.ScreenshotManager
import com.uber.autoaccept.utils.UberOfferParser
import kotlinx.coroutines.*
import org.opencv.android.OpenCVLoader
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

    private lateinit var screenshotManager: ScreenshotManager
    private val offerCardDetector = OfferCardDetector()
    var openCVButtonRect: android.graphics.Rect? = null

    private val configReloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i(TAG, "Config reload requested")
            config = loadConfig()
            filterEngine = FilterEngine(config.filterSettings)
            registerStateHandlers()
        }
    }

    private val testTapReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val prefs = getSharedPreferences("uber_auto_accept", Context.MODE_PRIVATE)
            val lpX = prefs.getFloat("target_lp_x", -1f)
            val lpY = prefs.getFloat("target_lp_y", -1f)
            if (lpX < 0 || lpY < 0) {
                Log.w("UAA", "[TEST] 저장된 lp 좌표 없음 — ⊕ 먼저 배치하세요")
                return
            }
            val (tx, ty) = lpToClickCoord(lpX, lpY)
            Log.i("UAA", "[TEST] 테스트 탭 실행: lp=($lpX,$lpY) → click=($tx,$ty)")

            if (com.uber.autoaccept.utils.ShizukuHelper.hasPermission()) {
                // Shizuku: input tap (FLAG_IS_GENERATED_BY_ACCESSIBILITY 우회)
                serviceScope.launch {
                    FloatingWidgetService.disableTargetTouch()
                    val ok = com.uber.autoaccept.utils.ShizukuHelper.tap(tx, ty)
                    Log.i("UAA", "[TEST] Shizuku tap ${if (ok) "✅" else "❌"} ($tx,$ty)")
                    FloatingWidgetService.enableTargetTouch()
                }
            } else {
                // fallback: dispatchGesture
                Log.w("UAA", "[TEST] Shizuku 미사용 → dispatchGesture fallback")
                FloatingWidgetService.disableTargetTouch()
                val path = android.graphics.Path().apply { moveTo(tx, ty) }
                val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 10L)
                dispatchGesture(
                    android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build(),
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(g: android.accessibilityservice.GestureDescription) {
                            Log.i("UAA", "[TEST] ✅ 제스처 completed ($tx, $ty)")
                            FloatingWidgetService.enableTargetTouch()
                        }
                        override fun onCancelled(g: android.accessibilityservice.GestureDescription) {
                            Log.w("UAA", "[TEST] ❌ 제스처 cancelled ($tx, $ty)")
                            FloatingWidgetService.enableTargetTouch()
                        }
                    }, null
                )
            }
        }
    }

    private fun lpToClickCoord(lpX: Float, lpY: Float): Pair<Float, Float> {
        val halfPx = resources.displayMetrics.density * 60f  // 120dp의 절반
        return Pair(lpX + halfPx, lpY + halfPx)
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "서비스 연결됨")
        Log.i("UAA", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.i("UAA", "[SERVICE] ✅ 접근성 서비스 연결됨")

        // ServiceState 초기화 + 프로세스 재시작 시 마지막 상태 복원
        ServiceState.init(this)
        val wasRestored = ServiceState.restoreIfNeeded()

        // 설정 로드
        config = loadConfig()

        // 컴포넌트 초기화
        parser = UberOfferParser()
        filterEngine = FilterEngine(config.filterSettings)

        // Config reload receiver 등록
        registerReceiver(configReloadReceiver, IntentFilter("com.uber.autoaccept.RELOAD_CONFIG"),
            Context.RECEIVER_NOT_EXPORTED)

        // TEST_TAP receiver 등록
        registerReceiver(testTapReceiver, IntentFilter("com.uber.autoaccept.TEST_TAP"),
            Context.RECEIVER_NOT_EXPORTED)

        // 원격 로깅 초기화 (Supabase 사용)
        RemoteLogger.initialize(this, config.deviceId, config.remoteLoggingEnabled)
        RemoteLogger.currentStateSupplier = { stateMachine.getCurrentState()::class.simpleName ?: "unknown" }
        RemoteLogger.logServiceConnected()
        if (wasRestored) {
            RemoteLogger.logRecovery("service_state", "process_restart", true,
                mapOf("restored_from_prefs" to true))
        }
        RemoteLogger.flushNow()

        // Shizuku UserService 바인딩
        if (config.enableShizuku) {
            com.uber.autoaccept.utils.ShizukuHelper.bindService()
        }

        // OpenCV 초기화
        screenshotManager = ScreenshotManager(this)
        if (!OpenCVLoader.initLocal()) {
            Log.e(TAG, "OpenCV 초기화 실패")
        } else {
            Log.i(TAG, "OpenCV 초기화 성공")
        }

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

        Log.i("UAA", "[SERVICE] 초기화 완료 | 모드: ${config.filterSettings.mode} | 최대 고객 거리: ${config.filterSettings.maxCustomerDistance}km")
        Log.i(TAG, "초기화 완료. 모드: ${config.filterSettings.mode}")
    }
    
    /**
     * 상태 핸들러 등록
     */
    private lateinit var acceptingHandler: AcceptingHandler

    private fun registerStateHandlers() {
        acceptingHandler = AcceptingHandler()
        val prefs = getSharedPreferences("uber_auto_accept", Context.MODE_PRIVATE)
        val lpX = prefs.getFloat("target_lp_x", -1f)
        val lpY = prefs.getFloat("target_lp_y", -1f)
        if (lpX >= 0 && lpY >= 0) {
            val (cx, cy) = lpToClickCoord(lpX, lpY)
            acceptingHandler.targetClickPoint = android.graphics.PointF(cx, cy)
        }
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
                            var root = findOfferWindow()
                            // childCnt=1 빈 트리 감지: React Native 렌더 전환 중 → 200ms 대기 후 재탐색
                            if (root != null && root.childCount <= 1 &&
                                com.uber.autoaccept.utils.AccessibilityHelper.extractAllText(root).isBlank()) {
                                Log.w(TAG, "[PARSE] childCnt=${root.childCount} 빈 트리 감지 → 200ms 대기 후 재탐색")
                                delay(200)
                                root = findOfferWindow()
                            }
                            root
                        } else {
                            rootInActiveWindow
                        }
                        // Accepting 상태: 클릭 직전에 모든 윈도우 루트 주입
                if (handler is AcceptingHandler) {
                    handler.allWindowRoots = windows?.mapNotNull { it.root } ?: emptyList()
                    handler.openCVButtonRect = openCVButtonRect
                    handler.serviceRef = this@UberAccessibilityService
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
        if (event.packageName != UBER_PACKAGE) return

        if (!ServiceState.isActive()) return

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
     * STATE_CHANGED = 새 화면/오버레이 등장 → 오퍼 감지 즉시 시도
     */
    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val currentState = stateMachine.getCurrentState()

        if (currentState is AppState.Idle) {
            stateMachine.handleEvent(StateEvent.UberAppOpened)
        }

        // Online 상태에서 새 윈도우가 뜨면 짧은 딜레이 후 오퍼 감지 시도
        if (currentState is AppState.Online) {
            offerDetectionJob?.cancel()
            offerDetectionJob = serviceScope.launch {
                // 오퍼 카드 로딩 대기 후 재시도 (최대 3회, 50ms 간격)
                repeat(3) { attempt ->
                    delay(50)
                    if (stateMachine.getCurrentState() is AppState.Online) {
                        val offerRoot = findOfferWindow()
                        if (offerRoot != null) {
                            Log.i(TAG, "🔔 오퍼 감지 (STATE_CHANGED, attempt=${attempt + 1})")
                            Log.i("UAA", "[OFFER] 🔔 새 오퍼 화면 감지 (STATE_CHANGED attempt=${attempt + 1}) → 파싱 시작")
                            stateMachine.handleEvent(StateEvent.NewOfferAppeared(offerRoot))
                            return@launch
                        }
                    } else return@launch
                }
            }
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

        // Online 상태에서만 새로운 오퍼 감지
        if (currentState is AppState.Online) {
            // debounce: 화면 점진적 로딩 중 과다 이벤트 필터링
            offerDetectionJob?.cancel()
            offerDetectionJob = serviceScope.launch {
                delay(50)
                if (stateMachine.getCurrentState() !is AppState.Online) return@launch
                val offerRoot = findOfferWindow()
                if (offerRoot != null) {
                    Log.i(TAG, "🔔 새로운 오퍼 감지!")
                    Log.i("UAA", "[OFFER] 🔔 새 오퍼 화면 감지 → 파싱 시작")
                    stateMachine.handleEvent(StateEvent.NewOfferAppeared(offerRoot))
                } else {
                    dumpWindowTexts()
                }
            }
        }
    }
    
    /**
     * 진단: 현재 모든 윈도우의 텍스트를 UAA_DUMP 태그로 출력
     */
    private fun dumpWindowTexts() {
        val wins = windows ?: emptyList()
        val roots = wins.mapNotNull { it.root }.ifEmpty { listOfNotNull(rootInActiveWindow) }
        Log.d("UAA_DUMP", "=== DUMP 윈도우수:${roots.size} ===")
        roots.forEachIndexed { wi, root ->
            val winInfo = if (wi < wins.size) "type=${wins[wi].type} layer=${wins[wi].layer}" else "rootInActive"
            Log.d("UAA_DUMP", "[$wi] $winInfo pkg=${root.packageName} childCnt=${root.childCount}")
            // text + contentDescription 모두 수집
            val sb = StringBuilder()
            dumpNodeRecursive(root, sb, 0)
            val out = sb.toString().take(500)
            Log.d("UAA_DUMP", "[$wi] 텍스트: $out")
        }
    }

    private fun dumpNodeRecursive(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 10) return
        try {
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { sb.append("[T]$it ") }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { sb.append("[D]$it ") }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.also { child -> dumpNodeRecursive(child, sb, depth + 1) }
            }
        } catch (_: Exception) {}
    }

    /**
     * 모든 윈도우에서 오퍼 창 탐색
     * - 비오퍼 화면(운행 리스트, 새로운 콜 배너 등) 제외
     * - 오퍼 화면 로딩 완료 확인 후 반환 ("대한민국" 주소 2개 이상)
     */
    private suspend fun findOfferWindow(): android.view.accessibility.AccessibilityNodeInfo? {
        val helper = com.uber.autoaccept.utils.AccessibilityHelper
        val roots = windows?.mapNotNull { it.root }?.ifEmpty { null }
            ?: listOfNotNull(rootInActiveWindow)

        // 1단계: 모든 창에서 "콜 수락" 즉시 탐색 — 발견 즉시 반환 (부정 체크 불필요)
        for (root in roots) {
            if (helper.findNodeByText(root, "콜 수락", exactMatch = true) != null) {
                Log.w(TAG, "findOfferWindow → 반환: pkg=${root.packageName} (콜수락 즉시 감지)")
                return root
            }
        }

        // 2단계: "콜 수락" 없을 때만 폭넓은 탐색
        for (root in roots) {
            // 비오퍼 화면 제외
            if (helper.findNodeByText(root, "운행 리스트") != null) continue
            if (helper.findNodeByText(root, "새로운 콜") != null) continue
            if (helper.findNodeByText(root, "지금은 요청이 없습니다") != null) continue
            if (helper.findNodeByText(root, "요청 1건 매칭") != null) continue
            // 운행 중 화면 제외 (목적지 주소가 광역시 포함 시 오퍼 창으로 오판 방지)
            if (helper.findNodeByText(root, "목적지 도착") != null) continue
            if (helper.findNodeByText(root, "운행 명세서") != null) continue
            if (helper.findNodeByText(root, "요금 입력하기") != null) continue

            // 2순위: 광역 행정구역 키워드 (파서와 동일한 전략)
            val cityKeywords = listOf("특별시", "광역시", "특별자치시")
            var cityFound = false
            for (keyword in cityKeywords) {
                try {
                    val addrNodes = root.findAccessibilityNodeInfosByText(keyword)
                        ?.filter { !it.text.isNullOrBlank() } ?: emptyList()
                    if (addrNodes.isNotEmpty()) { cityFound = true; break }
                } catch (_: Exception) {}
            }
            if (cityFound) return root

            // 3순위: ViewId 기반 감지 (health 로깅 겸용)
            val pickupNode = helper.findNodeByViewId(root, "uda_details_pickup_address_text_view")
            val dropoffNode = helper.findNodeByViewId(root, "uda_details_dropoff_address_text_view")
            RemoteLogger.logViewIdHealth("uda_details_pickup_address_text_view", pickupNode != null)
            RemoteLogger.logViewIdHealth("uda_details_dropoff_address_text_view", dropoffNode != null)
            if (pickupNode != null && dropoffNode != null) return root

            // OpenCV fallback: 스크린샷으로 오퍼 카드 및 버튼 시각적 감지
            val screenshot = screenshotManager.capture()
            if (screenshot != null) {
                val result = offerCardDetector.detect(screenshot)
                if (result != null) {
                    openCVButtonRect = result.buttonRect
                    Log.i(TAG, "🔍 OpenCV 오퍼 감지 성공 — 버튼 좌표: (${result.buttonCenterX}, ${result.buttonCenterY})")
                    Log.i("UAA", "[OPENCV] ✅ 시각적 오퍼 감지 성공 | 버튼: (${result.buttonCenterX.toInt()}, ${result.buttonCenterY.toInt()})")
                    RemoteLogger.logOpenCVDetection(true, result.buttonCenterX, result.buttonCenterY)
                    return root
                } else {
                    RemoteLogger.logOpenCVDetection(false, reason = "card_not_detected")
                }
            } else {
                RemoteLogger.logOpenCVDetection(false, reason = "screenshot_failed")
            }
        }
        return null
    }
    
    /**
     * 설정 로드
     */
    private fun loadConfig(): AppConfig {
        val prefs = getSharedPreferences("uber_auto_accept", Context.MODE_PRIVATE)

        val enabled = prefs.getString("filter_mode", "ENABLED") != "DISABLED"
        val maxDist = prefs.getFloat("max_customer_distance",
            prefs.getFloat("airport_pickup_max_distance", 5.0f)  // 기존값 마이그레이션
        ).toDouble()

        // device_id: read or generate once
        var deviceId = prefs.getString("device_id", null)
        if (deviceId.isNullOrBlank()) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", deviceId).apply()
        }

        val pickupKeywords = listOf("특별시")

        val selectedCondition = prefs.getInt("selected_condition", 4)
        val enabledConditions = setOf(selectedCondition)

        return AppConfig(
            filterSettings = FilterSettings(
                mode = if (enabled) FilterMode.ENABLED else FilterMode.DISABLED,
                maxCustomerDistance = maxDist,
                pickupKeywords = pickupKeywords,
                enabledConditions = enabledConditions
            ),
            enableShizuku = prefs.getBoolean("enable_shizuku", true),
            enableLogging = prefs.getBoolean("enable_logging", true),
            autoAcceptDelay = prefs.getLong("auto_accept_delay", 0L),
            humanizationEnabled = prefs.getBoolean("humanization_enabled", false),
            remoteLoggingEnabled = prefs.getBoolean("remote_logging_enabled", true),
            deviceId = deviceId
        )
    }
    
    override fun onInterrupt() {
        Log.w(TAG, "서비스 중단됨 — 복구 시도")
        Log.e("UAA", "[SERVICE] ❌ 접근성 서비스 강제 중단 (onInterrupt) — 복구 시도 중")
        RemoteLogger.logServiceDisconnected("onInterrupt")

        // ServiceState 유지 — 이벤트 재수신 시 자동 복구되도록
        // config/filterEngine 재로드하여 stale 상태 방지
        try {
            config = loadConfig()
            filterEngine = FilterEngine(config.filterSettings)
            Log.i("UAA", "[SERVICE] ✅ onInterrupt 후 config/filterEngine 재로드 완료")
            RemoteLogger.logRecovery("accessibility", "on_interrupt", true,
                mapOf("config_reloaded" to true, "shizuku_enabled" to config.enableShizuku))
        } catch (e: Exception) {
            Log.e("UAA", "[SERVICE] onInterrupt 복구 실패: ${e.message}")
            RemoteLogger.logRecovery("accessibility", "on_interrupt", false,
                mapOf("error" to (e.message ?: "unknown")))
        }

        // Shizuku 재바인딩 시도
        if (config.enableShizuku) {
            com.uber.autoaccept.utils.ShizukuHelper.bindService()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(configReloadReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(testTapReceiver) } catch (_: Exception) {}
        com.uber.autoaccept.utils.ShizukuHelper.unbindService()
        RemoteLogger.logServiceDisconnected("onDestroy")
        RemoteLogger.shutdown()
        serviceScope.cancel()
        Log.i(TAG, "서비스 종료됨")
        Log.i("UAA", "[SERVICE] 서비스 정상 종료 (onDestroy)")
    }
}
