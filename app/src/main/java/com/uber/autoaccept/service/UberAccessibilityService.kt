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
import com.uber.autoaccept.utils.UberOfferGate
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
        private const val UBER_PACKAGE = UberOfferGate.UBER_DRIVER_PACKAGE
        private const val ACTION_ENGINE_START = "com.uber.autoaccept.ACTION_ENGINE_START"
        private const val ACTION_ENGINE_STOP = "com.uber.autoaccept.ACTION_ENGINE_STOP"
        private const val OFFER_LOGCAT_TAG = "UAA_OFFER"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val stateMachine = StateMachine()

    private lateinit var parser: UberOfferParser
    private lateinit var filterEngine: FilterEngine
    private lateinit var config: AppConfig

    private val stateHandlers = mutableListOf<IStateHandler>()
    private var offerDetectionJob: Job? = null

    /** 수락 후 3초 쿨다운: 화면 전환 전 stale 트리 재파싱 방지 */
    @Volatile private var lastAcceptTimeMs: Long = 0L
    private val ACCEPT_COOLDOWN_MS = 3000L
    @Volatile private var lastVisualCardSignature: String? = null
    @Volatile private var lastVisualDetectionAtMs: Long = 0L

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

    private val engineControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_ENGINE_START -> {
                    RemoteLogger.logEngineCommand(
                        source = "accessibility_receiver",
                        action = ACTION_ENGINE_START,
                        details = mapOf("accessibility_connected" to true)
                    )
                    ServiceState.start("accessibility_receiver:$ACTION_ENGINE_START")
                    RemoteLogger.logRecovery("engine", "explicit_start", true,
                        mapOf("accessibility_connected" to true))
                    Log.i("UAA", "[ENGINE] START 수신 → active=true")
                }
                ACTION_ENGINE_STOP -> {
                    RemoteLogger.logEngineCommand(
                        source = "accessibility_receiver",
                        action = ACTION_ENGINE_STOP,
                        details = mapOf("accessibility_connected" to true)
                    )
                    ServiceState.stop("accessibility_receiver:$ACTION_ENGINE_STOP")
                    RemoteLogger.logRecovery("engine", "explicit_stop", true,
                        mapOf("accessibility_connected" to true))
                    Log.i("UAA", "[ENGINE] STOP 수신 → active=false")
                }
            }
            RemoteLogger.flushNow()
        }
    }

    private fun lpToClickCoord(lpX: Float, lpY: Float): Pair<Float, Float> {
        val halfPx = resources.displayMetrics.density * 60f  // 120dp의 절반
        return Pair(lpX + halfPx, lpY + halfPx)
    }

    private fun newOfferTraceContext(source: String, stage: String): OfferTraceContext {
        return OfferTraceContext(
            traceId = UUID.randomUUID().toString(),
            detectionSource = source,
            detectionStage = stage
        )
    }

    private fun formatOfferLogDetails(details: Map<String, Any?>): String {
        if (details.isEmpty()) return "-"
        return details.entries.joinToString(",") { (key, value) -> "$key=${value ?: "null"}" }
    }

    private fun logOfferSnapshot(
        stage: String,
        source: String,
        root: AccessibilityNodeInfo?,
        traceContext: OfferTraceContext? = null,
        details: Map<String, Any?> = emptyMap()
    ) {
        if (root == null) return
        val snapshot = com.uber.autoaccept.utils.AccessibilityHelper.buildOfferDebugLine(root)
        Log.i(
            OFFER_LOGCAT_TAG,
            "[trace=${traceContext?.traceId ?: "none"}][$source/$stage] pkg=${root.packageName ?: "null"} class=${root.className ?: "null"} details=${formatOfferLogDetails(details)} $snapshot"
        )
    }

    private fun buildCandidateRoots(seed: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        val seen = linkedSetOf<Int>()

        fun addChain(node: AccessibilityNodeInfo?) {
            var current = node
            var depth = 0
            while (current != null && depth < 12) {
                val key = System.identityHashCode(current)
                if (seen.add(key)) {
                    candidates += current
                }
                current = try { current.parent } catch (_: Exception) { null }
                depth++
            }
        }

        addChain(seed)
        windows?.mapNotNull { it.root }?.forEach { addChain(it) }
        addChain(rootInActiveWindow)
        return candidates
    }

    private fun dispatchDetectedOffer(
        offerRoot: AccessibilityNodeInfo,
        source: String,
        stage: String,
        details: Map<String, Any?> = emptyMap(),
        traceContext: OfferTraceContext = newOfferTraceContext(source, stage)
    ) {
        logOfferSnapshot(stage, source, offerRoot, traceContext, details)
        RemoteLogger.logOfferDetection(
            stage = "trigger_parse",
            source = source,
            success = true,
            details = details,
            traceContext = traceContext
        )
        stateMachine.handleEvent(StateEvent.NewOfferAppeared(offerRoot, traceContext))
    }

    private fun maybeCachePrefetchedOffer(
        root: AccessibilityNodeInfo,
        traceContext: OfferTraceContext?,
        contentSignal: com.uber.autoaccept.utils.OfferContentSignal
    ) {
        if (traceContext == null || !contentSignal.isStructuredOffer) return

        val orderedTexts = com.uber.autoaccept.utils.AccessibilityHelper.extractOrderedTexts(root)
        val virtualCandidates = com.uber.autoaccept.utils.AccessibilityHelper.collectVirtualAddressTexts(root)
        val addressCandidates = com.uber.autoaccept.utils.AccessibilityHelper.selectAddressCandidates(
            orderedTexts,
            virtualCandidates
        )
        val pickup = contentSignal.textCluster.pickupAddress ?: addressCandidates.getOrNull(0)
        val dropoff = contentSignal.textCluster.dropoffAddress ?: addressCandidates.getOrNull(1)
        if (!com.uber.autoaccept.utils.AccessibilityHelper.looksLikeAddressText(pickup) ||
            !com.uber.autoaccept.utils.AccessibilityHelper.looksLikeAddressText(dropoff)
        ) return

        val acceptButton = com.uber.autoaccept.utils.AccessibilityHelper.findFirstNodeByViewIds(
            root,
            listOf(
                "uda_details_accept_button",
                "upfront_offer_configurable_details_accept_button",
                "upfront_offer_configurable_details_auditable_accept_button"
            )
        )?.second ?: com.uber.autoaccept.utils.AccessibilityHelper.findNodeByText(root, "콜 수락")
            ?: com.uber.autoaccept.utils.AccessibilityHelper.findNodeByText(root, "수락")
            ?: com.uber.autoaccept.utils.AccessibilityHelper.findNodeByText(root, "Accept")

        val parserSource = when {
            contentSignal.textCluster.pickupAddress != null && contentSignal.textCluster.dropoffAddress != null -> "prefetched_text_cluster"
            virtualCandidates.isNotEmpty() -> "prefetched_virtual_text"
            else -> "prefetched_content_signal"
        }

        val prefetchedOffer = UberOffer(
            offerUuid = traceContext.traceId,
            traceContext = traceContext,
            pickupLocation = pickup!!,
            dropoffLocation = dropoff!!,
            customerDistance = com.uber.autoaccept.utils.DistanceParser.parseDistance(contentSignal.textCluster.pickupEtaText),
            tripDistance = 0.0,
            estimatedFare = 0,
            estimatedTime = com.uber.autoaccept.utils.DistanceParser.parseDuration(contentSignal.textCluster.tripDurationText),
            acceptButtonBounds = acceptButton?.let { com.uber.autoaccept.utils.AccessibilityHelper.getBounds(it) },
            acceptButtonNode = acceptButton,
            parseConfidence = ParseConfidence.MEDIUM,
            parserSource = parserSource,
            pickupViewId = if (parserSource == "prefetched_text_cluster") "text_cluster_prefetch_pickup" else "virtual_text_prefetch_pickup",
            dropoffViewId = if (parserSource == "prefetched_text_cluster") "text_cluster_prefetch_dropoff" else "virtual_text_prefetch_dropoff",
            pickupValidated = true,
            dropoffValidated = true
        )
        UberOfferParser.cachePrefetchedOffer(prefetchedOffer)
        Log.i(
            OFFER_LOGCAT_TAG,
            "[trace=${traceContext.traceId}] prefetched_offer parser=$parserSource pickup=${pickup.take(40)} dropoff=${dropoff.take(40)} addrCandidates=${addressCandidates.size}"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        Log.i(TAG, "서비스 연결됨")
        Log.i("UAA", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.i("UAA", "[SERVICE] ✅ 접근성 서비스 연결됨")

        // ServiceState 초기화 + 프로세스 재시작 시 마지막 상태 복원
        ServiceState.init(this)
        ServiceState.setAccessibilityConnected(true)
        val wasRestored = ServiceState.restoreIfNeeded()
        if (!ServiceState.isActive()) {
            Log.i(OFFER_LOGCAT_TAG, "[service_connected] activating ServiceState for debug-offer tracing")
            ServiceState.start("onServiceConnected_auto")
            RemoteLogger.logRecovery("engine", "service_connected_auto_start", true, mapOf("was_restored" to wasRestored))
        }

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

        // 엔진 START/STOP은 접근성 서비스가 최종 소유
        val engineFilter = IntentFilter().apply {
            addAction(ACTION_ENGINE_START)
            addAction(ACTION_ENGINE_STOP)
        }
        registerReceiver(engineControlReceiver, engineFilter, Context.RECEIVER_NOT_EXPORTED)

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

        // 서비스 연결 시 Uber 앱이 이미 열려있으면 즉시 Online으로 전환 + 오퍼 스캔
        val rootNode = rootInActiveWindow
        if (rootNode?.packageName == UBER_PACKAGE) {
            Log.i(TAG, "서비스 연결 시 Uber 앱 이미 열려있음 → Online 전환 + 즉시 오퍼 스캔")
            stateMachine.handleEvent(StateEvent.UberAppOpened)
            serviceScope.launch {
                delay(300)
                if (stateMachine.getCurrentState() is AppState.Online) {
                    val traceContext = newOfferTraceContext("service_connected", "startup_scan")
                    RemoteLogger.logOfferDetection("startup_scan", "service_connected", true, traceContext = traceContext)
                    val offerRoot = findOfferWindow("service_connected", traceContext)
                    if (offerRoot != null) {
                        Log.i(TAG, "재접속 시 오퍼 즉시 감지")
                        dispatchDetectedOffer(offerRoot, "service_connected", "startup_scan", traceContext = traceContext)
                    }
                }
            }
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

                // 수락 완료 시 쿨다운 타이머 시작
                if (state is AppState.Accepted) {
                    lastAcceptTimeMs = System.currentTimeMillis()
                    Log.i(TAG, "[COOLDOWN] 수락 완료 — 3초 쿨다운 시작")
                }

                // 상태에 맞는 핸들러 찾기
                val handler = stateHandlers.firstOrNull { it.canHandle(state) }

                if (handler != null) {
                    try {
                        val rootNode = if (state is AppState.OfferDetected) {
                            // 1순위: 최초 감지 시 저장한 rawNode 재사용 (오퍼 창이 사라져도 node 자체는 유효할 수 있음)
                            val stored = state.offer.acceptButtonNode
                            var root: android.view.accessibility.AccessibilityNodeInfo? = null
                            if (stored != null) {
                                try { stored.refresh() } catch (_: Exception) {}
                                val nodeOk = try { stored.childCount; true } catch (_: Exception) { false }
                                if (nodeOk) root = stored
                            }
                            // 2순위: stored node 무효 시 findOfferWindow() fallback
                            if (root == null) root = findOfferWindow("offer_detected_fallback", state.offer.traceContext, stored)
                            // childCnt=1 빈 트리 감지: React Native 렌더 전환 중 → 최대 3회 대기 후 재탐색
                            var waitAttempt = 3
                            while (root != null && root.childCount <= 1 &&
                                com.uber.autoaccept.utils.AccessibilityHelper.extractAllText(root).isBlank()
                                && waitAttempt < 3) {
                                waitAttempt++
                                Log.w(TAG, "[PARSE] childCnt=${root.childCount} 빈 트리 감지 → 300ms 대기 후 재탐색 ($waitAttempt/3)")
                                delay(300)
                                root = findOfferWindow("offer_detected_retry", state.offer.traceContext, stored)
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

        // Watchdog: Online 상태에서 10초마다 자동 오퍼 스캔 (재접속 후 이벤트 없는 경우 대비)
        serviceScope.launch {
            while (isActive) {
                delay(10_000)
                if (stateMachine.getCurrentState() is AppState.Online && ServiceState.isActive()) {
                    val traceContext = newOfferTraceContext("watchdog", "watchdog_scan")
                    val offerRoot = findOfferWindow("watchdog", traceContext)
                    if (offerRoot != null) {
                        Log.i(TAG, "[WATCHDOG] Online 상태 오퍼 감지 → 파싱 시작")
                        dispatchDetectedOffer(offerRoot, "watchdog", "watchdog_scan", traceContext = traceContext)
                    }
                }
            }
        }

        serviceScope.launch {
            while (isActive) {
                delay(600)
                if (stateMachine.getCurrentState() !is AppState.Online || !ServiceState.isActive()) {
                    continue
                }
                runVisualPollingPass()
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
        val className = event.className?.toString() ?: "null"
        val packageName = event.packageName?.toString() ?: "null"
        Log.i(TAG, "[WSC] className=$className state=${currentState::class.simpleName}")
        RemoteLogger.logDiagnostic("WSC", mapOf(
            "className" to className,
            "packageName" to packageName,
            "state" to (currentState::class.simpleName ?: "unknown"),
            "hasText" to (event.text?.joinToString("|")?.take(80) ?: "")
        ))
        logOfferSnapshot(
            "window_state_received",
            "window_state_changed",
            rootInActiveWindow,
            details = mapOf(
                "class_name" to className,
                "package_name" to packageName
            )
        )
        RemoteLogger.logOfferDetection(
            stage = "window_state_received",
            source = "window_state_changed",
            success = true,
            details = mapOf(
                "class_name" to className,
                "package_name" to packageName
            )
        )

        if (currentState is AppState.Idle) {
            stateMachine.handleEvent(StateEvent.UberAppOpened)
        }

        // 수락 후 쿨다운 중에는 오퍼 감지 스킵
        if (System.currentTimeMillis() - lastAcceptTimeMs < ACCEPT_COOLDOWN_MS) {
            Log.d(TAG, "[COOLDOWN] STATE_CHANGED 무시 (쿨다운 중)")
            return
        }

        // 콜 카드 className 게이트 — 리버스 엔지니어링으로 확인된 클래스명
        // Window-state changes are noisy; only continue if structured offer content is visible.
        if (currentState is AppState.Online) {
            offerDetectionJob?.cancel()
            offerDetectionJob = serviceScope.launch {
                delay(120)
                if (stateMachine.getCurrentState() !is AppState.Online) return@launch
                val traceContext = newOfferTraceContext("window_state_changed", "window_state_probe")
                val offerRoot = findOfferWindow("window_state_changed", traceContext, event.source)
                if (offerRoot != null) {
                    Log.i(TAG, "[WSC] structured offer confirmed after state change")
                    dispatchDetectedOffer(
                        offerRoot,
                        "window_state_changed",
                        "window_state_probe",
                        mapOf("class_name" to className),
                        traceContext
                    )
                }
            }
        }
    }

    /**
     * ?????????????????
     */
    private fun handleWindowContentChanged(event: AccessibilityEvent) {
        var currentState = stateMachine.getCurrentState()

        // Idle 상태에서 컨텐츠 변경 = Uber 앱이 이미 열려있음 → Online 전환
        if (currentState is AppState.Idle) {
            stateMachine.handleEvent(StateEvent.UberAppOpened)
            currentState = stateMachine.getCurrentState()
        }

        // 수락 후 쿨다운 중에는 오퍼 감지 스킵
        if (System.currentTimeMillis() - lastAcceptTimeMs < ACCEPT_COOLDOWN_MS) {
            Log.d(TAG, "[COOLDOWN] CONTENT_CHANGED 무시 (쿨다운 중)")
            return
        }

        // Online 상태에서만 새로운 오퍼 감지
        if (currentState is AppState.Online) {
            // debounce: 화면 점진적 로딩 중 과다 이벤트 필터링
            offerDetectionJob?.cancel()
            offerDetectionJob = serviceScope.launch {
                delay(50)
                if (stateMachine.getCurrentState() !is AppState.Online) return@launch
                val traceContext = newOfferTraceContext("window_content_changed", "content_changed")
                val eventSource = event.source
                logOfferSnapshot(
                    "content_changed_received",
                    "window_content_changed",
                    eventSource ?: rootInActiveWindow,
                    traceContext
                )
                val offerRoot = findOfferWindow("window_content_changed", traceContext, eventSource)
                if (offerRoot != null) {
                    Log.i(TAG, "🔔 새로운 오퍼 감지!")
                    Log.i("UAA", "[OFFER] 🔔 새 오퍼 화면 감지 → 파싱 시작")
                    dispatchDetectedOffer(offerRoot, "window_content_changed", "content_changed", traceContext = traceContext)
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

    private suspend fun runVisualPollingPass() {
        val bitmap = screenshotManager.capture() ?: return
        try {
            val cardResult = offerCardDetector.detect(bitmap) ?: return
            openCVButtonRect = cardResult.buttonRect

            val signature = listOf(
                cardResult.cardRect.left,
                cardResult.cardRect.top,
                cardResult.buttonRect.left,
                cardResult.buttonRect.top
            ).joinToString(":")
            val now = System.currentTimeMillis()
            if (signature == lastVisualCardSignature && now - lastVisualDetectionAtMs < 2500L) {
                return
            }

            lastVisualCardSignature = signature
            lastVisualDetectionAtMs = now
            RemoteLogger.logOpenCVDetection(
                true,
                cardResult.buttonCenterX,
                cardResult.buttonCenterY,
                "visual_poll_card_detected"
            )

            val root = rootInActiveWindow ?: windows?.mapNotNull { it.root }?.firstOrNull() ?: return
            val traceContext = newOfferTraceContext("visual_poll", "visual_poll_detected")
            dispatchDetectedOffer(
                root,
                "visual_poll",
                "visual_poll_detected",
                mapOf(
                    "button_center_x" to cardResult.buttonCenterX,
                    "button_center_y" to cardResult.buttonCenterY,
                    "card_left" to cardResult.cardRect.left,
                    "card_top" to cardResult.cardRect.top
                ),
                traceContext
            )
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    /**
     * 모든 윈도우에서 오퍼 창 탐색
     * - 비오퍼 화면(운행 리스트, 새로운 콜 배너 등) 제외
     * - 오퍼 화면 로딩 완료 확인 후 반환 ("대한민국" 주소 2개 이상)
     */
    @Volatile private var lastUiSummaryAtMs: Long = 0L
    private suspend fun findOfferWindow(
        source: String,
        traceContext: OfferTraceContext? = null,
        preferredRoot: AccessibilityNodeInfo? = null
    ): android.view.accessibility.AccessibilityNodeInfo? {
        val helper = com.uber.autoaccept.utils.AccessibilityHelper
        val roots = buildCandidateRoots(preferredRoot).ifEmpty {
            windows?.mapNotNull { it.root }?.ifEmpty { null }
                ?: listOfNotNull(rootInActiveWindow)
        }

        // 비오퍼 화면 판별 — extractAllText(depth 10)와 findAccessibilityNodeInfosByText(무제한) 병행
        fun isNonOfferRoot(root: android.view.accessibility.AccessibilityNodeInfo): Boolean {
            val allText = helper.extractAllText(root)
            val textCluster = helper.summarizeOfferTexts(
                helper.extractOrderedTexts(root),
                helper.collectResourceIds(root).keys
            )
            if (textCluster.blacklistTextHit != null) return true
            val nonOfferKeywords = listOf(
                "\uC6B4\uD589 \uB9AC\uC2A4\uD2B8",
                "\uC0C8\uB85C\uC6B4 \uCF5C",
                "\uC9C0\uAE08\uC740 \uC694\uCCAD\uC774 \uC5C6\uC2B5\uB2C8\uB2E4",
                "\uC694\uCCAD 1\uAC74 \uB9E4\uCE6D",
                "\uBAA9\uC801\uC9C0 \uB3C4\uCC29",
                "\uC6B4\uD589 \uBA85\uC138\uC11C",
                "\uC694\uAE08 \uC785\uB825\uD558\uAE30"
            )
            for (kw in nonOfferKeywords) {
                if (allText.contains(kw)) return true
                try {
                    if (root.findAccessibilityNodeInfosByText(kw)?.isNotEmpty() == true) return true
                } catch (_: Exception) {}
            }
            return false
        }

        for (root in roots) {
            if (isNonOfferRoot(root)) continue

            val contentSignal = helper.inspectOfferContent(root)
            if (contentSignal.isStructuredOffer) {
                val textCluster = contentSignal.textCluster
                maybeCachePrefetchedOffer(root, traceContext, contentSignal)
                logOfferSnapshot(
                    "content_signal_confirmed",
                    source,
                    root,
                    traceContext,
                    mapOf(
                        "reason" to contentSignal.reason,
                        "trip" to textCluster.tripDurationText,
                        "eta" to textCluster.pickupEtaText,
                        "pickup" to textCluster.pickupAddress,
                        "dropoff" to textCluster.dropoffAddress,
                        "has_pickup_dropoff" to contentSignal.hasPickupDropoffContent,
                        "has_time" to contentSignal.hasTimeContent,
                        "has_accept" to contentSignal.hasAcceptContent
                    )
                )
                RemoteLogger.logOfferDetection(
                    stage = "content_signal_confirmed",
                    source = source,
                    success = true,
                    details = mapOf(
                        "reason" to contentSignal.reason,
                        "trip" to (textCluster.tripDurationText ?: "null"),
                        "eta" to (textCluster.pickupEtaText ?: "null"),
                        "pickup" to (textCluster.pickupAddress ?: "null"),
                        "dropoff" to (textCluster.dropoffAddress ?: "null"),
                        "has_pickup_dropoff" to contentSignal.hasPickupDropoffContent,
                        "has_time" to contentSignal.hasTimeContent,
                        "has_accept" to contentSignal.hasAcceptContent,
                        "package_name" to (root.packageName?.toString() ?: "null")
                    ),
                    traceContext = traceContext
                )
                return root
            }
        }
        RemoteLogger.logOfferDetection(
            stage = "offer_window_not_found",
            source = source,
            success = false,
            details = mapOf("root_count" to roots.size),
            traceContext = traceContext,
            throttleKey = "offer_window_not_found:$source",
            throttleMs = 20_000L
        )
        val now = System.currentTimeMillis()
        if (now - lastUiSummaryAtMs > 20_000) {
            lastUiSummaryAtMs = now
            val sampleRoot = roots.firstOrNull()
            if (sampleRoot != null) {
                try {
                    val ids = helper.collectResourceIds(sampleRoot).entries
                        .sortedByDescending { it.value }
                        .take(30)
                        .joinToString(prefix = "[", postfix = "]") { "${it.key}:${it.value}" }
                    val addrs = helper.findAddressLikeNodes(sampleRoot, 10)
                        .joinToString(prefix = "[", postfix = "]") { (rid, cls, summary) ->
                            "($rid|$cls|${summary.replace("|", "/")})"
                        }
                    val btns = helper.findAcceptButtonCandidates(sampleRoot, 5)
                        .joinToString(prefix = "[", postfix = "]") { (rid, cls, summary) ->
                            "($rid|$cls|${summary.replace("|", "/")})"
                        }
                    logOfferSnapshot("offer_window_ui_summary", source, sampleRoot, traceContext, mapOf("root_count" to roots.size))
                    RemoteLogger.logOfferDetection(
                        stage = "offer_window_ui_summary",
                        source = source,
                        success = false,
                        details = mapOf(
                            "ui_summary" to "ids=$ids addrs=$addrs btns=$btns",
                            "package_name" to (sampleRoot.packageName?.toString() ?: "null")
                        ),
                        traceContext = traceContext
                    )
                } catch (_: Exception) {}
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
        ServiceState.setAccessibilityConnected(false)
        try { unregisterReceiver(configReloadReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(testTapReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(engineControlReceiver) } catch (_: Exception) {}
        com.uber.autoaccept.utils.ShizukuHelper.unbindService()
        RemoteLogger.logServiceDisconnected("onDestroy")
        RemoteLogger.shutdown()
        serviceScope.cancel()
        Log.i(TAG, "서비스 종료됨")
        Log.i("UAA", "[SERVICE] 서비스 정상 종료 (onDestroy)")
    }
}
