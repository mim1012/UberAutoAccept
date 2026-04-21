package com.uber.autoaccept.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
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
import com.uber.autoaccept.utils.OfferDetectionNoiseFilter
import com.uber.autoaccept.utils.OfferSnapshotBuilder
import com.uber.autoaccept.utils.ScreenshotManager
import com.uber.autoaccept.utils.UberOfferGate
import com.uber.autoaccept.utils.UberOfferParser
import kotlinx.coroutines.*
import org.opencv.android.OpenCVLoader
import kotlinx.coroutines.flow.collect
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Uber ???筌???嚥???AccessibilityService
 * Vortex??癲ル슢??????룸Ŧ爾???????????ㅻ깹??
 */
class UberAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "UberAccessibilityService"
        private const val UBER_PACKAGE = UberOfferGate.UBER_DRIVER_PACKAGE
        private const val ACTION_ENGINE_START = "com.uber.autoaccept.ACTION_ENGINE_START"
        private const val ACTION_ENGINE_STOP = "com.uber.autoaccept.ACTION_ENGINE_STOP"
        private const val OFFER_LOGCAT_TAG = "UAA_OFFER"
        private const val DETECT_LOGCAT_TAG = "UAA_DETECT"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val stateMachine = StateMachine()

    private lateinit var parser: UberOfferParser
    private lateinit var filterEngine: FilterEngine
    private lateinit var config: AppConfig

    private val stateHandlers = mutableListOf<IStateHandler>()
    private var offerDetectionJob: Job? = null

    /** ??嚥?????3????얜굝???? ??釉먮뻤????ш낄援????stale ?嶺뚮ㅎ遊뉔걡???????袁⑸젻泳? */
    @Volatile private var lastAcceptTimeMs: Long = 0L
    private val ACCEPT_COOLDOWN_MS = 3000L
    @Volatile private var lastVisualCardSignature: String? = null
    @Volatile private var lastVisualDetectionAtMs: Long = 0L
    private val capturedOfferSnapshots = mutableSetOf<String>()

    private lateinit var screenshotManager: ScreenshotManager
    private val offerCardDetector = OfferCardDetector()
    var openCVButtonRect: android.graphics.Rect? = null
    var latestOfferCardRect: android.graphics.Rect? = null

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
                Log.w("UAA", "[TEST] stored lp target missing; place the target first")
                return
            }
            val (tx, ty) = lpToClickCoord(lpX, lpY)
            Log.i("UAA", "[TEST] executing test tap: lp=($lpX,$lpY) -> click=($tx,$ty)")

            if (com.uber.autoaccept.utils.ShizukuHelper.hasPermission()) {
                // Shizuku tap avoids accessibility-generated gesture tagging.
                serviceScope.launch {
                    FloatingWidgetService.disableTargetTouch()
                    val ok = com.uber.autoaccept.utils.ShizukuHelper.tap(tx, ty)
                    Log.i("UAA", "[TEST] Shizuku tap ${if (ok) "OK" else "FAIL"} ($tx,$ty)")
                    FloatingWidgetService.enableTargetTouch()
                }
            } else {
                Log.w("UAA", "[TEST] no Shizuku; using dispatchGesture fallback")
                FloatingWidgetService.disableTargetTouch()
                val path = android.graphics.Path().apply { moveTo(tx, ty) }
                val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 10L)
                dispatchGesture(
                    android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build(),
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(g: android.accessibilityservice.GestureDescription) {
                            Log.i("UAA", "[TEST] gesture completed ($tx, $ty)")
                            FloatingWidgetService.enableTargetTouch()
                        }

                        override fun onCancelled(g: android.accessibilityservice.GestureDescription) {
                            Log.w("UAA", "[TEST] gesture cancelled ($tx, $ty)")
                            FloatingWidgetService.enableTargetTouch()
                        }
                    },
                    null
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
                    Log.i("UAA", "[ENGINE] START ??筌뚯슜堉???active=true")
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
                    Log.i("UAA", "[ENGINE] STOP ??筌뚯슜堉???active=false")
                }
            }
            RemoteLogger.flushNow()
        }
    }

    private fun lpToClickCoord(lpX: Float, lpY: Float): Pair<Float, Float> {
        val halfPx = resources.displayMetrics.density * 60f  // 120dp??????고떘
        return Pair(lpX + halfPx, lpY + halfPx)
    }

    private fun newOfferTraceContext(source: String, stage: String): OfferTraceContext {
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull()
        return OfferTraceContext(
            traceId = UUID.randomUUID().toString(),
            detectionSource = source,
            detectionStage = stage,
            appVersion = versionName,
            gitTag = versionName
        )
    }

    private fun formatOfferLogDetails(details: Map<String, Any?>): String {
        if (details.isEmpty()) return "-"
        return details.entries.joinToString(",") { (key, value) -> "$key=${value ?: "null"}" }
    }

    private fun currentStateName(): String {
        return stateMachine.getCurrentState()::class.simpleName ?: "unknown"
    }

    private fun logDetectionStep(
        step: String,
        traceContext: OfferTraceContext? = null,
        details: Map<String, Any?> = emptyMap()
    ) {
        Log.i(
            DETECT_LOGCAT_TAG,
            "[trace=${traceContext?.traceId ?: "none"}][$step] state=${currentStateName()} details=${formatOfferLogDetails(details)}"
        )
    }

    private fun summarizeRootForDetection(root: AccessibilityNodeInfo?): String {
        if (root == null) return "root=null"
        val packageName = root.packageName?.toString() ?: "null"
        val className = root.className?.toString() ?: "null"
        val childCount = try { root.childCount } catch (_: Exception) { -1 }
        val snapshot = com.uber.autoaccept.utils.AccessibilityHelper.buildOfferDebugLine(root)
        return "pkg=$packageName class=$className childCount=$childCount $snapshot"
    }

    private fun summarizeRootBrief(root: AccessibilityNodeInfo?): String {
        if (root == null) return "root=null"
        val packageName = root.packageName?.toString() ?: "null"
        val className = root.className?.toString() ?: "null"
        val childCount = try { root.childCount } catch (_: Exception) { -1 }
        val viewId = try { root.viewIdResourceName?.substringAfter(":id/") ?: "-" } catch (_: Exception) { "-" }
        val text = try {
            root.text?.toString()?.trim()?.take(40)
                ?: root.contentDescription?.toString()?.trim()?.take(40)
                ?: "-"
        } catch (_: Exception) {
            "-"
        }
        return "pkg=$packageName class=$className childCount=$childCount viewId=$viewId text=$text"
    }

    private fun collectSourceViewIds(node: AccessibilityNodeInfo?, maxDepth: Int = 6): List<String> {
        val ids = linkedSetOf<String>()
        var current = node
        var depth = 0
        while (current != null && depth < maxDepth) {
            try {
                current.viewIdResourceName
                    ?.substringAfter(":id/")
                    ?.takeIf { it.isNotBlank() }
                    ?.let(ids::add)
            } catch (_: Exception) {
            }
            current = try { current.parent } catch (_: Exception) { null }
            depth++
        }
        return ids.toList()
    }

    private fun collectSourceTexts(node: AccessibilityNodeInfo?, maxDepth: Int = 6): List<String> {
        val texts = linkedSetOf<String>()
        var current = node
        var depth = 0
        while (current != null && depth < maxDepth) {
            try {
                current.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(texts::add)
                current.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(texts::add)
            } catch (_: Exception) {
            }
            current = try { current.parent } catch (_: Exception) { null }
            depth++
        }
        return texts.toList()
    }

    private fun shouldProbeRoot(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val helper = com.uber.autoaccept.utils.AccessibilityHelper
        val signal = helper.inspectOfferContent(root)
        if (signal.isStructuredOffer) {
            return true
        }
        if (candidateOfferReason(root) != null) {
            return true
        }
        return OfferDetectionNoiseFilter.shouldStartProbe(
            root.className?.toString(),
            helper.collectResourceIds(root).keys,
            helper.extractOrderedTexts(root)
        )
    }

    private fun candidateOfferReason(root: AccessibilityNodeInfo?): String? {
        if (root == null) return null
        val signal = com.uber.autoaccept.utils.AccessibilityHelper.inspectOfferContent(root)
        return when {
            signal.hasAcceptContent -> "accept_content_visible"
            signal.hasPickupDropoffContent -> "pickup_dropoff_visible"
            signal.hasTimeContent -> "time_content_visible"
            else -> null
        }
    }

    private fun shouldProbeFromEventSource(event: AccessibilityEvent): Boolean {
        val source = event.source
        val className = event.className?.toString()
        val sourceViewIds = collectSourceViewIds(source)
        val sourceTexts = buildList {
            addAll(collectSourceTexts(source))
            addAll(event.text?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) } ?: emptyList())
        }.distinct()
        if (OfferDetectionNoiseFilter.shouldStartProbe(className, sourceViewIds, sourceTexts)) {
            return true
        }
        val candidates = linkedMapOf<Int, AccessibilityNodeInfo>()

        fun addCandidate(node: AccessibilityNodeInfo?) {
            if (node == null) return
            candidates.putIfAbsent(System.identityHashCode(node), node)
        }

        // Prioritize the active/root windows before the event-source parent chain.
        addCandidate(rootInActiveWindow)
        windows?.mapNotNull { it.root }?.forEach(::addCandidate)
        buildCandidateRoots(source).forEach(::addCandidate)

        return candidates.values
            .take(8)
            .any(::shouldProbeRoot)
    }

    private fun shouldRunWatchdogScan(): Boolean {
        val root = rootInActiveWindow ?: return false
        return shouldProbeRoot(root)
    }

    private suspend fun runBurstOfferProbe(
        source: String,
        stage: String,
        eventSource: AccessibilityNodeInfo?,
        details: Map<String, Any?> = emptyMap()
    ) {
        val burstDelaysMs = longArrayOf(0L, 40L, 100L, 180L)
        val traceContext = newOfferTraceContext(source, stage)

        for ((attempt, delayMs) in burstDelaysMs.withIndex()) {
            if (delayMs > 0) {
                delay(delayMs)
            }
            if (stateMachine.getCurrentState() !is AppState.Online) {
                return
            }

            logDetectionStep(
                "burst_probe_attempt",
                traceContext,
                mapOf(
                    "source" to source,
                    "stage" to stage,
                    "attempt" to (attempt + 1),
                    "delay_ms" to delayMs,
                    "event_source_summary" to summarizeRootBrief(eventSource),
                    "active_root_summary" to summarizeRootBrief(rootInActiveWindow)
                ) + details
            )

            if (attempt == 0) {
                logOfferSnapshot(
                    "${stage}_received",
                    source,
                    eventSource ?: rootInActiveWindow,
                    traceContext,
                    mapOf("attempt" to (attempt + 1)) + details
                )
            }

            val offerRoot = findOfferWindow(
                source = source,
                traceContext = traceContext,
                preferredRoot = eventSource,
                allowLooseTimeSignal = attempt >= 1
            )
            if (offerRoot != null) {
                dispatchDetectedOffer(
                    offerRoot,
                    source,
                    stage,
                    mapOf(
                        "attempt" to (attempt + 1),
                        "delay_ms" to delayMs,
                        "allow_loose_time_signal" to (attempt >= 1)
                    ) + details,
                    traceContext
                )
                return
            }

            if (attempt in 1..2) {
                runVisualPollingPass()
                if (stateMachine.getCurrentState() !is AppState.Online) {
                    return
                }
            }
        }
    }

    private fun shouldCaptureOfferSnapshot(traceContext: OfferTraceContext?): Boolean {
        val traceId = traceContext?.traceId ?: return false
        synchronized(capturedOfferSnapshots) {
            if (capturedOfferSnapshots.contains(traceId)) return false
            capturedOfferSnapshots.add(traceId)
            return true
        }
    }

    private fun saveOfferSnapshotBitmap(
        bitmap: Bitmap,
        traceContext: OfferTraceContext?,
        source: String,
        stage: String
    ): String? {
        val traceId = traceContext?.traceId ?: return null
        return try {
            val dir = File(filesDir, "offer-snapshots").apply { mkdirs() }
            val file = File(dir, "${System.currentTimeMillis()}_${traceId}_${source}_${stage}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            logDetectionStep(
                "offer_snapshot_saved",
                traceContext,
                mapOf(
                    "source" to source,
                    "stage" to stage,
                    "path" to file.absolutePath,
                    "width" to bitmap.width,
                    "height" to bitmap.height
                )
            )
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save offer snapshot: ${e.message}", e)
            logDetectionStep(
                "offer_snapshot_save_failed",
                traceContext,
                mapOf(
                    "source" to source,
                    "stage" to stage,
                    "error" to (e.message ?: "unknown")
                )
            )
            null
        }
    }

    private fun captureOfferSnapshot(traceContext: OfferTraceContext?, source: String, stage: String) {
        if (!shouldCaptureOfferSnapshot(traceContext)) return
        serviceScope.launch {
            val traceId = traceContext?.traceId
            val bitmap = screenshotManager.capture()
            if (bitmap == null) {
                if (traceId != null) {
                    synchronized(capturedOfferSnapshots) {
                        capturedOfferSnapshots.remove(traceId)
                    }
                }
                logDetectionStep(
                    "offer_snapshot_capture_failed",
                    traceContext,
                    mapOf("source" to source, "stage" to stage, "reason" to "capture_null")
                )
                return@launch
            }
            try {
                val saved = saveOfferSnapshotBitmap(bitmap, traceContext, source, stage)
                if (saved == null && traceId != null) {
                    synchronized(capturedOfferSnapshots) {
                        capturedOfferSnapshots.remove(traceId)
                    }
                }
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
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

    private suspend fun captureOfferSnapshots(
        source: String,
        preferredRoot: AccessibilityNodeInfo?
    ): List<Pair<AccessibilityNodeInfo, OfferSnapshot>> {
        val sampleOffsets = listOf(0L, 50L, 120L, 250L)
        val results = mutableListOf<Pair<AccessibilityNodeInfo, OfferSnapshot>>()
        var previousOffset = 0L
        sampleOffsets.forEachIndexed { sampleIndex, offset ->
            val delta = offset - previousOffset
            if (delta > 0) delay(delta)
            previousOffset = offset
            val sampleRoots = buildCandidateRoots(preferredRoot)
            sampleRoots.forEach { root ->
                results += root to OfferSnapshotBuilder.build(
                    root = root,
                    source = source,
                    sampleIndex = sampleIndex,
                    rootCount = sampleRoots.size
                )
            }
        }
        return results
    }

    private fun snapshotTraceContext(
        traceContext: OfferTraceContext?,
        snapshot: OfferSnapshot
    ): OfferTraceContext? {
        if (traceContext == null) return null
        return traceContext.copy(
            snapshotSource = snapshot.source,
            snapshotIndex = snapshot.sampleIndex,
            snapshotNodeCount = snapshot.nodes.size,
            snapshotCandidateCount = snapshot.addressCandidates.size + snapshot.acceptButtonCandidates.size
        )
    }

    private fun dispatchDetectedOffer(
        offerRoot: AccessibilityNodeInfo,
        source: String,
        stage: String,
        details: Map<String, Any?> = emptyMap(),
        traceContext: OfferTraceContext = newOfferTraceContext(source, stage)
    ) {
        logDetectionStep(
            "dispatch_detected_offer",
            traceContext,
            mapOf(
                "source" to source,
                "stage" to stage,
                "root_summary" to summarizeRootForDetection(offerRoot)
            ) + details
        )
        captureOfferSnapshot(traceContext, source, stage)
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
            customerDistance = com.uber.autoaccept.utils.DistanceParser.parsePickupEtaDistance(contentSignal.textCluster.pickupEtaText),
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

        // ServiceState ?縕?猿녿뎨??+ ??ш끽維곩ㅇ?嶺뚮ㅎ?ц짆????????癲ル슢???癲????ㅺ컼???怨뚮옖甕??
        ServiceState.init(this)
        ServiceState.setAccessibilityConnected(true)
        val wasRestored = ServiceState.restoreIfNeeded()
        if (!ServiceState.isActive()) {
            Log.i(OFFER_LOGCAT_TAG, "[service_connected] activating ServiceState for debug-offer tracing")
            ServiceState.start("onServiceConnected_auto")
            RemoteLogger.logRecovery("engine", "service_connected_auto_start", true, mapOf("was_restored" to wasRestored))
        }

        // ???源놁젳 ?棺??짆?삠궘?
        config = loadConfig()

        // ????????⑤베肄??縕?猿녿뎨??
        parser = UberOfferParser()
        filterEngine = FilterEngine(config.filterSettings)

        // Config reload receiver ?濚밸Ŧ援욃ㅇ?
        registerReceiver(configReloadReceiver, IntentFilter("com.uber.autoaccept.RELOAD_CONFIG"),
            Context.RECEIVER_NOT_EXPORTED)

        // TEST_TAP receiver ?濚밸Ŧ援욃ㅇ?
        registerReceiver(testTapReceiver, IntentFilter("com.uber.autoaccept.TEST_TAP"),
            Context.RECEIVER_NOT_EXPORTED)

        // ??釉먯뒭??START/STOP?? ???쒋닪?????筌먐삳４??? 癲ル슔?됭짆?륂렭?????
        val engineFilter = IntentFilter().apply {
            addAction(ACTION_ENGINE_START)
            addAction(ACTION_ENGINE_STOP)
        }
        registerReceiver(engineControlReceiver, engineFilter, Context.RECEIVER_NOT_EXPORTED)

        // ??????棺??짆???縕?猿녿뎨??(Supabase ????
        RemoteLogger.initialize(this, config.deviceId, config.remoteLoggingEnabled)
        RemoteLogger.currentStateSupplier = { stateMachine.getCurrentState()::class.simpleName ?: "unknown" }
        RemoteLogger.logServiceConnected()
        if (wasRestored) {
            RemoteLogger.logRecovery("service_state", "process_restart", true,
                mapOf("restored_from_prefs" to true))
        }
        RemoteLogger.flushNow()

        // Shizuku UserService ?袁⑸즴????
        if (config.enableShizuku) {
            com.uber.autoaccept.utils.ShizukuHelper.bindService()
        }

        // OpenCV ?縕?猿녿뎨??
        screenshotManager = ScreenshotManager(this)
        if (!OpenCVLoader.initLocal()) {
            Log.e(TAG, "OpenCV ?縕?猿녿뎨??????됰꽡")
        } else {            Log.i(TAG, "OpenCV initialized successfully")
        }

        // ???ㅺ컼???嶺뚮ㅎ?볠뤃???濚밸Ŧ援욃ㅇ?
        registerStateHandlers()

        // ???ㅺ컼?????굿癲???筌믨퀣援?
        observeState()

        // ??筌먐삳４?????ㅼ뒦????Uber ?濚밸Ŧ?깁????? ???????源끹걬癲?癲ル슣鍮뽳쭕??Online???⑥????ш낄援??+ ????됰군 ????몃펽
        val rootNode = rootInActiveWindow
        if (rootNode?.packageName == UBER_PACKAGE) {
            Log.i(TAG, "??筌먐삳４?????ㅼ뒦????Uber ?????? ???????源낆쓱 ??Online ??ш낄援??+ 癲ル슣鍮뽳쭕??????됰군 ????몃펽")
            stateMachine.handleEvent(StateEvent.UberAppOpened)
            serviceScope.launch {
                delay(300)
                if (stateMachine.getCurrentState() is AppState.Online) {
                    if (!shouldRunWatchdogScan()) {
                        logDetectionStep(
                            "service_connected_scan_skipped",
                            details = mapOf(
                                "active_root_summary" to summarizeRootBrief(rootInActiveWindow)
                            )
                        )
                        return@launch
                    }
                    val traceContext = newOfferTraceContext("service_connected", "startup_scan")
                    RemoteLogger.logOfferDetection("startup_scan", "service_connected", true, traceContext = traceContext)
                    val offerRoot = findOfferWindow("service_connected", traceContext)
                    if (offerRoot != null) {
                        Log.i(TAG, "???????????됰군 癲ル슣鍮뽳쭕????좊즴??")
                        dispatchDetectedOffer(offerRoot, "service_connected", "startup_scan", traceContext = traceContext)
                    }
                }
            }
        }

        Log.i("UAA", "[SERVICE] ?縕?猿녿뎨????ш끽維??| 癲ル슢?꾤땟??? ${config.filterSettings.mode} | 癲ル슔?됭짆? ??關履??癲꾧퀗???? ${config.filterSettings.maxCustomerDistance}km")
        Log.i(TAG, "?縕?猿녿뎨????ш끽維?? 癲ル슢?꾤땟??? ${config.filterSettings.mode}")
    }

    /**
     * ???ㅺ컼???嶺뚮ㅎ?볠뤃???濚밸Ŧ援욃ㅇ?
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
        stateHandlers.add(
            OfferDetectedHandler(
                parser = parser,
                screenshotProvider = { screenshotManager.capture() },
                ocrScopeRectProvider = { latestOfferCardRect }
            )
        )
        stateHandlers.add(OfferAnalyzingHandler(filterEngine))
        stateHandlers.add(ReadyToAcceptHandler(config))
        stateHandlers.add(acceptingHandler)
        stateHandlers.add(AcceptedHandler())
        stateHandlers.add(RejectedHandler())
        stateHandlers.add(ErrorHandler())
    }

    /**
     * ???ㅺ컼?????굿癲??????筌?癲ル슪?ｇ몭??
     * Vortex??癲ル슢??????룸Ŧ爾???????
     */
    private fun observeState() {
        serviceScope.launch {
            stateMachine.currentState.collect { state ->
                Log.d(TAG, "Current State: ${state::class.simpleName}")

                // ??嚥?????ш끽維??????얜굝?????????????筌믨퀣援?
                if (state is AppState.Accepted) {
                    lastAcceptTimeMs = System.currentTimeMillis()
                    Log.i(TAG, "[COOLDOWN] accepted state entered; cooldown started")
                }

                // ???ㅺ컼???癲ル슢?????嶺뚮ㅎ?볠뤃??癲ル슓??젆癒る뎨?
                val handler = stateHandlers.firstOrNull { it.canHandle(state) }

                if (handler != null) {
                    try {
                        val rootNode = if (state is AppState.OfferDetected) {
                            // 1??筌믨퀡彛? 癲ル슔?됭짆????좊즴?? ?????濚왿몾??rawNode ??雅??(????됰군 癲ル슓????????嶺뚮ㅎ?볩쭩?node ?????????レ챺????????源낆쓱)
                            val stored = state.offer.acceptButtonNode
                            var root: android.view.accessibility.AccessibilityNodeInfo? = null
                            if (stored != null) {
                                try { stored.refresh() } catch (_: Exception) {}
                                val nodeOk = try { stored.childCount; true } catch (_: Exception) { false }
                                if (nodeOk) root = stored
                            }
                            // 2??筌믨퀡彛? stored node ???뺤깙????findOfferWindow() fallback
                            if (root == null) root = findOfferWindow("offer_detected_fallback", state.offer.traceContext, stored)
                            // childCnt=1 ???嶺뚮ㅎ遊뉔걡???좊즴??: React Native ???????ш낄援??濚???癲ル슔?됭짆? 3??????????濚??
                            var waitAttempt = 3
                            while (root != null && root.childCount <= 1 &&
                                com.uber.autoaccept.utils.AccessibilityHelper.extractAllText(root).isBlank()
                                && waitAttempt < 3) {
                                waitAttempt++
                                Log.w(TAG, "[PARSE] childCnt=${root.childCount} ???嶺뚮ㅎ遊뉔걡???좊즴?? ??300ms ????????濚??($waitAttempt/3)")
                                delay(300)
                                root = findOfferWindow("offer_detected_retry", state.offer.traceContext, stored)
                            }
                            root
                        } else {
                            rootInActiveWindow
                        }
                        // Accepting ???ㅺ컼?? ?????癲ル슣?????癲ル슢?꾤땟???????됤뵣????룸Ŧ爾????낆뒩???
                if (handler is AcceptingHandler) {
                    handler.allWindowRoots = windows?.mapNotNull { it.root } ?: emptyList()
                    handler.openCVButtonRect = openCVButtonRect
                    handler.serviceRef = this@UberAccessibilityService
                    Log.d(TAG, "AcceptingHandler roots injected: ${handler.allWindowRoots.size}")
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

        // Watchdog: Online ???ㅺ컼??????10?縕?袁?맪?????筌?????됰군 ????몃펽 (??????????濚??????몄툗 ?濡ろ뜑???????
        serviceScope.launch {
            while (isActive) {
                delay(10_000)
                if (stateMachine.getCurrentState() is AppState.Online && ServiceState.isActive()) {
                    if (!shouldRunWatchdogScan()) {
                        continue
                    }
                    val traceContext = newOfferTraceContext("watchdog", "watchdog_scan")
                    val offerRoot = findOfferWindow("watchdog", traceContext)
                    if (offerRoot != null) {                        Log.i(TAG, "[WATCHDOG] Online state; watchdog found an offer root")
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

        Log.i(TAG, "??Processing accessibility event: type=${event.eventType}")
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
     * ????됤뵣?????ㅺ컼???怨뚮뼚???癲ル슪?ｇ몭??
     * STATE_CHANGED = ????釉먮뻤??????곷츉???源낇꼧 ?濚밸Ŧ?????????됰군 ??좊즴?? 癲ル슣鍮뽳쭕????筌먲퐣??
     */
    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val currentState = stateMachine.getCurrentState()
        val className = event.className?.toString() ?: "null"
        val packageName = event.packageName?.toString() ?: "null"
        logDetectionStep(
            "window_state_changed_received",
            details = mapOf(
                "class_name" to className,
                "package_name" to packageName,
                "event_text" to (event.text?.joinToString("|")?.take(120) ?: ""),
                "source_summary" to summarizeRootBrief(event.source ?: rootInActiveWindow)
            )
        )
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

        // ??嚥???????얜굝????濚욌꼬?댄꺍???????됰군 ??좊즴?? ???袁⑤툞
        if (System.currentTimeMillis() - lastAcceptTimeMs < ACCEPT_COOLDOWN_MS) {
            Log.d(TAG, "[COOLDOWN] STATE_CHANGED ???뺤깓??(??얜굝????濚?")
            logDetectionStep(
                "window_state_changed_skipped_cooldown",
                details = mapOf(
                    "class_name" to className,
                    "cooldown_remaining_ms" to (ACCEPT_COOLDOWN_MS - (System.currentTimeMillis() - lastAcceptTimeMs)).coerceAtLeast(0L)
                )
            )
            return
        }

        // ???怨멸텭???className ?濡ろ뜐???????域밸Ŧ留?????????怨쀪퐨癲ル슢?????肉??嶺뚮Ĳ?됮??????????용럡
        // Window-state changes are noisy; only continue if structured offer content is visible.
        if (currentState is AppState.Online) {
            if (!shouldProbeFromEventSource(event)) {
                logDetectionStep(
                    "window_state_changed_probe_skipped",
                    details = mapOf(
                        "class_name" to className,
                        "package_name" to packageName,
                        "source_summary" to summarizeRootBrief(event.source),
                        "active_root_summary" to summarizeRootBrief(rootInActiveWindow)
                    )
                )
                return
            }
            logDetectionStep(
                "window_content_changed_received",
                details = mapOf(
                    "content_change_types" to event.contentChangeTypes,
                    "class_name" to (event.className?.toString() ?: "null"),
                    "package_name" to (event.packageName?.toString() ?: "null"),
                    "source_summary" to summarizeRootBrief(event.source ?: rootInActiveWindow)
                )
            )
            offerDetectionJob?.cancel()
            offerDetectionJob = serviceScope.launch {
                runBurstOfferProbe(
                    source = "window_state_changed",
                    stage = "window_state_probe",
                    eventSource = event.source,
                    details = mapOf("class_name" to className)
                )
            }
        }
    }

    /**
     * ?????????????????
     */
    private fun handleWindowContentChanged(event: AccessibilityEvent) {
        var currentState = stateMachine.getCurrentState()
        val probeGatePassed = shouldProbeFromEventSource(event)
        if (currentState is AppState.Online && !probeGatePassed) {
            logDetectionStep(
                "window_content_changed_probe_gate_bypassed",
                details = mapOf(
                    "content_change_types" to event.contentChangeTypes,
                    "class_name" to (event.className?.toString() ?: "null"),
                    "package_name" to (event.packageName?.toString() ?: "null"),
                    "source_summary" to summarizeRootBrief(event.source),
                    "active_root_summary" to summarizeRootBrief(rootInActiveWindow)
                )
            )
        }        // If Uber is open while idle, transition to Online before probing.
        if (currentState is AppState.Idle) {
            stateMachine.handleEvent(StateEvent.UberAppOpened)
            currentState = stateMachine.getCurrentState()
        }

        // ??嚥???????얜굝????濚욌꼬?댄꺍???????됰군 ??좊즴?? ???袁⑤툞
        if (System.currentTimeMillis() - lastAcceptTimeMs < ACCEPT_COOLDOWN_MS) {
            Log.d(TAG, "[COOLDOWN] CONTENT_CHANGED ???뺤깓??(??얜굝????濚?")
            logDetectionStep(
                "window_content_changed_skipped_cooldown",
                details = mapOf(
                    "cooldown_remaining_ms" to (ACCEPT_COOLDOWN_MS - (System.currentTimeMillis() - lastAcceptTimeMs)).coerceAtLeast(0L),
                    "source_summary" to summarizeRootBrief(event.source)
                )
            )
            return
        }

        // Online ???ㅺ컼?????節뉗땡?????궈??????됰군 ??좊즴??
        if (currentState is AppState.Online) {
            // debounce: ??釉먮뻤?????????棺??짆?승?濚???貫??????濚????ш낄援?轅곗땡?
            offerDetectionJob?.cancel()
            offerDetectionJob = serviceScope.launch {
                runBurstOfferProbe(
                    source = "window_content_changed",
                    stage = "content_changed",
                    eventSource = event.source,
                    details = mapOf(
                        "probe_gate_passed" to probeGatePassed,
                        "content_change_types" to event.contentChangeTypes
                    )
                )
            }
        }
    }

    /**
     * 癲ル슣???? ??ш끽維??癲ル슢?꾤땟???????됤뵣???Β?爰?????몄릇?嶺? UAA_DUMP ??癰궽쇱읇????⑥レ툓??
     */
    private fun dumpWindowTexts() {
        val wins = windows ?: emptyList()
        val roots = wins.mapNotNull { it.root }.ifEmpty { listOfNotNull(rootInActiveWindow) }
        Log.d("UAA_DUMP", "=== DUMP ????됤뵣???Β?ル묄:${roots.size} ===")
        roots.forEachIndexed { wi, root ->
            val winInfo = if (wi < wins.size) "type=${wins[wi].type} layer=${wins[wi].layer}" else "rootInActive"
            Log.d("UAA_DUMP", "[$wi] $winInfo pkg=${root.packageName} childCnt=${root.childCount}")
            // text + contentDescription 癲ル슢?꾤땟?嶺????쒓낯??
            val sb = StringBuilder()
            dumpNodeRecursive(root, sb, 0)
            val out = sb.toString().take(500)
            Log.d("UAA_DUMP", "[$wi] ????몄릇?? $out")
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
            latestOfferCardRect = cardResult.cardRect
            openCVButtonRect = cardResult.buttonRect

            val signature = listOf(
                cardResult.cardRect.left,
                cardResult.cardRect.top,
                cardResult.buttonRect.left,
                cardResult.buttonRect.top
            ).joinToString(":")
            val now = System.currentTimeMillis()
            if (signature == lastVisualCardSignature && now - lastVisualDetectionAtMs < 2500L) {
                logDetectionStep(
                    "visual_poll_skipped_duplicate",
                    details = mapOf(
                        "signature" to signature,
                        "age_ms" to (now - lastVisualDetectionAtMs)
                    )
                )
                return
            }

            lastVisualCardSignature = signature
            lastVisualDetectionAtMs = now
            val traceContext = newOfferTraceContext("visual_poll", "visual_poll_detected")
            logDetectionStep(
                "visual_poll_detected",
                traceContext,
                mapOf(
                    "signature" to signature,
                    "button_center_x" to cardResult.buttonCenterX,
                    "button_center_y" to cardResult.buttonCenterY,
                    "card_left" to cardResult.cardRect.left,
                    "card_top" to cardResult.cardRect.top,
                    "active_root_summary" to summarizeRootForDetection(rootInActiveWindow)
                )
            )
            if (shouldCaptureOfferSnapshot(traceContext)) {
                saveOfferSnapshotBitmap(bitmap, traceContext, "visual_poll", "visual_poll_detected")
            }
            RemoteLogger.logOpenCVDetection(
                true,
                cardResult.buttonCenterX,
                cardResult.buttonCenterY,
                "visual_poll_card_detected"
            )

            val root = rootInActiveWindow ?: windows?.mapNotNull { it.root }?.firstOrNull() ?: return
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
     * 癲ル슢?꾤땟???????됤뵣???Β??군??????됰군 癲??????
     * - ????????釉먮뻤????熬곣뫀類??域밸Ŧ遊얕짆?? ????궈?????袁⑸즲??먮き??? ??筌믨퀡??
     * - ????됰군 ??釉먮뻤???棺??짆?승???ш끽維???嶺뚮Ĳ?됮????袁⑸즵???("??????? ??낆뒩???2?????⑤?彛?
     */
    @Volatile private var lastUiSummaryAtMs: Long = 0L
    private suspend fun findOfferWindow(
        source: String,
        traceContext: OfferTraceContext? = null,
        preferredRoot: AccessibilityNodeInfo? = null,
        allowLooseTimeSignal: Boolean = false
    ): android.view.accessibility.AccessibilityNodeInfo? {
        val helper = com.uber.autoaccept.utils.AccessibilityHelper
        val snapshots = captureOfferSnapshots(source, preferredRoot)
        val roots = snapshots.map { it.first }.ifEmpty {
            buildCandidateRoots(preferredRoot).ifEmpty {
                windows?.mapNotNull { it.root }?.ifEmpty { null }
                    ?: listOfNotNull(rootInActiveWindow)
            }
        }
        logDetectionStep(
            "find_offer_window_start",
            traceContext,
            mapOf(
                "source" to source,
                "root_count" to roots.size,
                "preferred_root_summary" to summarizeRootBrief(preferredRoot),
                "active_root_summary" to summarizeRootBrief(rootInActiveWindow)
            )
        )

        val cityAddressTerms = listOf("특별시", "광역시", "특별자치시")
        val timePatterns = listOf(
            Regex("""\d+\s*분\s*\([\d.]+\s*km\)\s*남음"""),
            Regex("""(?:\d+\s*시간\s*)?\d+\s*분\s*운행""")
        )

        fun looksLikeLegacyAddress(text: String?): Boolean {
            if (text.isNullOrBlank()) return false
            val candidate = text.trim()
            if (candidate.length < 10) return false
            if (candidate.endsWith("쪽")) return false
            return cityAddressTerms.any { candidate.contains(it) }
        }

        fun hasLegacyAccept(root: AccessibilityNodeInfo): Boolean {
            val texts = helper.extractOrderedTexts(root)
            if (texts.any { value -> value.contains("콜 수락") || value.equals("수락", ignoreCase = true) }) {
                return true
            }
            return helper.findNodeByText(root, "콜 수락", exactMatch = true) != null
        }

        fun legacyAddressTexts(root: AccessibilityNodeInfo): List<String> {
            return helper.extractOrderedTexts(root)
                .filter(::looksLikeLegacyAddress)
                .distinct()
        }

        fun hasLegacyTime(root: AccessibilityNodeInfo): Boolean {
            return helper.extractOrderedTexts(root).any { value ->
                timePatterns.any { it.containsMatchIn(value) }
            }
        }

        fun isNonOfferRoot(root: android.view.accessibility.AccessibilityNodeInfo): Boolean {
            val allText = helper.extractAllText(root)
            val textCluster = helper.summarizeOfferTexts(
                helper.extractOrderedTexts(root),
                helper.collectResourceIds(root).keys
            )
            if (textCluster.blacklistTextHit != null) return true
            val nonOfferKeywords = listOf(
                "운행 리스트",
                "새로운 콜",
                "지금은 요청이 없습니다",
                "요청 1건 매칭",
                "목적지 도착",
                "운행 명세서",
                "요금 입력하기"
            )
            for (kw in nonOfferKeywords) {
                if (allText.contains(kw)) return true
                try {
                    if (root.findAccessibilityNodeInfosByText(kw)?.isNotEmpty() == true) return true
                } catch (_: Exception) {
                }
            }
            return false
        }

        val bestSnapshotMatch = snapshots
            .filter { (_, snapshot) ->
                val digest = snapshot.textDigest
                snapshot.reason != "blacklist_text_present" && !digest.contains("?? ???") && !digest.contains("??? ??? ????")
            }
            .maxWithOrNull(compareBy<Pair<AccessibilityNodeInfo, OfferSnapshot>> { OfferSnapshotBuilder.score(it.second) }
                .thenBy { it.second.sampleIndex }
                .thenBy { it.second.nodes.size })

        if (bestSnapshotMatch != null && OfferSnapshotBuilder.score(bestSnapshotMatch.second) >= 10) {
            val (root, snapshot) = bestSnapshotMatch
            val updatedTraceContext = snapshotTraceContext(traceContext, snapshot)
            maybeCachePrefetchedOffer(root, updatedTraceContext ?: traceContext, helper.inspectOfferContent(root))
            UberOfferParser.cacheSnapshotOffer(snapshot, updatedTraceContext ?: traceContext ?: newOfferTraceContext(source, "snapshot_cache"))
            logOfferSnapshot(
                "snapshot_confirmed",
                source,
                root,
                updatedTraceContext,
                mapOf(
                    "reason" to snapshot.reason,
                    "sample_index" to snapshot.sampleIndex,
                    "node_count" to snapshot.nodes.size,
                    "addr_candidates" to snapshot.addressCandidates.size,
                    "accept_candidates" to snapshot.acceptButtonCandidates.size,
                    "pickup" to snapshot.pickupAddress,
                    "dropoff" to snapshot.dropoffAddress,
                    "snapshot_score" to OfferSnapshotBuilder.score(snapshot)
                )
            )
            RemoteLogger.logOfferDetection(
                stage = "snapshot_confirmed",
                source = source,
                success = true,
                details = mapOf(
                    "reason" to snapshot.reason,
                    "sample_index" to snapshot.sampleIndex,
                    "node_count" to snapshot.nodes.size,
                    "addr_candidates" to snapshot.addressCandidates.size,
                    "accept_candidates" to snapshot.acceptButtonCandidates.size,
                    "pickup" to (snapshot.pickupAddress ?: "null"),
                    "dropoff" to (snapshot.dropoffAddress ?: "null"),
                    "package_name" to snapshot.packageName
                ),
                traceContext = updatedTraceContext ?: traceContext
            )
            return root
        }

        for ((index, root) in roots.withIndex()) {
            logDetectionStep(
                "find_offer_window_candidate",
                traceContext,
                mapOf(
                    "source" to source,
                    "candidate_index" to index,
                    "candidate_summary" to summarizeRootBrief(root)
                )
            )
            if (isNonOfferRoot(root)) {
                logDetectionStep(
                    "find_offer_window_candidate_rejected_non_offer",
                    traceContext,
                    mapOf(
                        "source" to source,
                        "candidate_index" to index,
                        "candidate_summary" to summarizeRootBrief(root)
                    )
                )
                continue
            }

            val legacyAccept = hasLegacyAccept(root)
            val legacyAddresses = legacyAddressTexts(root)
            val legacyTime = hasLegacyTime(root)
            val legacyPickup = helper.findNodeByViewId(root, "uda_details_pickup_address_text_view")
                ?: helper.findNodeByViewId(root, "pick_up_address")
            val legacyDropoff = helper.findNodeByViewId(root, "uda_details_dropoff_address_text_view")
                ?: helper.findNodeByViewId(root, "drop_off_address")
            if (legacyAccept || legacyAddresses.isNotEmpty() || (legacyTime && allowLooseTimeSignal) || (legacyPickup != null && legacyDropoff != null)) {
                val legacyReason = when {
                    legacyAccept -> "legacy_accept_text"
                    legacyAddresses.isNotEmpty() -> "legacy_city_address_visible"
                    legacyPickup != null && legacyDropoff != null -> "legacy_pickup_dropoff_viewid"
                    else -> "legacy_time_signal"
                }
                logDetectionStep(
                    "find_offer_window_candidate_confirmed_legacy",
                    traceContext,
                    mapOf(
                        "source" to source,
                        "candidate_index" to index,
                        "reason" to legacyReason,
                        "allow_loose_time_signal" to allowLooseTimeSignal,
                        "legacy_accept" to legacyAccept,
                        "legacy_time" to legacyTime,
                        "legacy_address_count" to legacyAddresses.size,
                        "pickup" to legacyAddresses.getOrNull(0),
                        "dropoff" to legacyAddresses.getOrNull(1),
                        "pickup_viewid_found" to (legacyPickup != null),
                        "dropoff_viewid_found" to (legacyDropoff != null)
                    )
                )
                logOfferSnapshot(
                    "content_signal_confirmed_legacy",
                    source,
                    root,
                    traceContext,
                    mapOf(
                        "reason" to legacyReason,
                        "legacy_accept" to legacyAccept,
                        "legacy_time" to legacyTime,
                        "legacy_address_count" to legacyAddresses.size,
                        "pickup" to legacyAddresses.getOrNull(0),
                        "dropoff" to legacyAddresses.getOrNull(1)
                    )
                )
                RemoteLogger.logOfferDetection(
                    stage = "content_signal_confirmed_legacy",
                    source = source,
                    success = true,
                    details = mapOf(
                        "reason" to legacyReason,
                        "allow_loose_time_signal" to allowLooseTimeSignal,
                        "legacy_accept" to legacyAccept,
                        "legacy_time" to legacyTime,
                        "legacy_address_count" to legacyAddresses.size,
                        "pickup" to (legacyAddresses.getOrNull(0) ?: "null"),
                        "dropoff" to (legacyAddresses.getOrNull(1) ?: "null"),
                        "pickup_viewid_found" to (legacyPickup != null),
                        "dropoff_viewid_found" to (legacyDropoff != null),
                        "package_name" to (root.packageName?.toString() ?: "null")
                    ),
                    traceContext = traceContext
                )
                return root
            }

            val contentSignal = helper.inspectOfferContent(root)
            if (contentSignal.isStructuredOffer) {
                val textCluster = contentSignal.textCluster
                logDetectionStep(
                    "find_offer_window_candidate_confirmed",
                    traceContext,
                    mapOf(
                        "source" to source,
                        "candidate_index" to index,
                        "reason" to contentSignal.reason,
                        "pickup" to textCluster.pickupAddress,
                        "dropoff" to textCluster.dropoffAddress,
                        "trip" to textCluster.tripDurationText,
                        "eta" to textCluster.pickupEtaText,
                        "region" to textCluster.regionText,
                        "accept" to textCluster.acceptText,
                        "has_pickup_dropoff" to contentSignal.hasPickupDropoffContent,
                        "has_time" to contentSignal.hasTimeContent,
                        "has_accept" to contentSignal.hasAcceptContent,
                        "has_region" to contentSignal.hasRegionContent
                    )
                )
                logOfferSnapshot(
                    "content_signal_confirmed",
                    source,
                    root,
                    traceContext,
                    mapOf(
                        "reason" to contentSignal.reason,
                        "trip" to textCluster.tripDurationText,
                        "eta" to textCluster.pickupEtaText,
                        "region" to textCluster.regionText,
                        "pickup" to textCluster.pickupAddress,
                        "dropoff" to textCluster.dropoffAddress,
                        "has_pickup_dropoff" to contentSignal.hasPickupDropoffContent,
                        "has_time" to contentSignal.hasTimeContent,
                        "has_accept" to contentSignal.hasAcceptContent,
                        "has_region" to contentSignal.hasRegionContent
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
                        "region" to (textCluster.regionText ?: "null"),
                        "pickup" to (textCluster.pickupAddress ?: "null"),
                        "dropoff" to (textCluster.dropoffAddress ?: "null"),
                        "has_pickup_dropoff" to contentSignal.hasPickupDropoffContent,
                        "has_time" to contentSignal.hasTimeContent,
                        "has_accept" to contentSignal.hasAcceptContent,
                        "has_region" to contentSignal.hasRegionContent,
                        "package_name" to (root.packageName?.toString() ?: "null")
                    ),
                    traceContext = traceContext
                )
                return root
            }

            val candidateReason = candidateOfferReason(root)
            val allowLooseCandidate = when (candidateReason) {
                "accept_content_visible", "pickup_dropoff_visible" -> true
                "time_content_visible" -> allowLooseTimeSignal
                else -> false
            }
            if (allowLooseCandidate) {
                val textCluster = contentSignal.textCluster
                logDetectionStep(
                    "find_offer_window_candidate_confirmed_loose",
                    traceContext,
                    mapOf(
                        "source" to source,
                        "candidate_index" to index,
                        "reason" to candidateReason,
                        "allow_loose_time_signal" to allowLooseTimeSignal,
                        "pickup" to textCluster.pickupAddress,
                        "dropoff" to textCluster.dropoffAddress,
                        "trip" to textCluster.tripDurationText,
                        "eta" to textCluster.pickupEtaText,
                        "accept" to textCluster.acceptText,
                        "has_pickup_dropoff" to contentSignal.hasPickupDropoffContent,
                        "has_time" to contentSignal.hasTimeContent,
                        "has_accept" to contentSignal.hasAcceptContent
                    )
                )
                logOfferSnapshot(
                    "content_signal_confirmed_loose",
                    source,
                    root,
                    traceContext,
                    mapOf(
                        "reason" to candidateReason,
                        "allow_loose_time_signal" to allowLooseTimeSignal,
                        "trip" to textCluster.tripDurationText,
                        "eta" to textCluster.pickupEtaText,
                        "pickup" to textCluster.pickupAddress,
                        "dropoff" to textCluster.dropoffAddress,
                        "accept" to textCluster.acceptText
                    )
                )
                RemoteLogger.logOfferDetection(
                    stage = "content_signal_confirmed_loose",
                    source = source,
                    success = true,
                    details = mapOf(
                        "reason" to candidateReason,
                        "allow_loose_time_signal" to allowLooseTimeSignal,
                        "trip" to (textCluster.tripDurationText ?: "null"),
                        "eta" to (textCluster.pickupEtaText ?: "null"),
                        "pickup" to (textCluster.pickupAddress ?: "null"),
                        "dropoff" to (textCluster.dropoffAddress ?: "null"),
                        "accept" to (textCluster.acceptText ?: "null"),
                        "has_pickup_dropoff" to contentSignal.hasPickupDropoffContent,
                        "has_time" to contentSignal.hasTimeContent,
                        "has_accept" to contentSignal.hasAcceptContent,
                        "package_name" to (root.packageName?.toString() ?: "null")
                    ),
                    traceContext = traceContext
                )
                return root
            }

            logDetectionStep(
                "find_offer_window_candidate_rejected_signal",
                traceContext,
                mapOf(
                    "source" to source,
                    "candidate_index" to index,
                    "reason" to contentSignal.reason,
                    "candidate_reason" to candidateReason,
                    "allow_loose_time_signal" to allowLooseTimeSignal,
                    "has_pickup_dropoff" to contentSignal.hasPickupDropoffContent,
                    "has_time" to contentSignal.hasTimeContent,
                    "has_accept" to contentSignal.hasAcceptContent,
                    "has_region" to contentSignal.hasRegionContent,
                    "candidate_summary" to summarizeRootBrief(root)
                )
            )
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
        logDetectionStep(
            "find_offer_window_not_found",
            traceContext,
            mapOf(
                "source" to source,
                "root_count" to roots.size
            )
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
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    /**
     * ???源놁젳 ?棺??짆?삠궘?
     */
    private fun loadConfig(): AppConfig {
        val prefs = getSharedPreferences("uber_auto_accept", Context.MODE_PRIVATE)

        val enabled = prefs.getString("filter_mode", "ENABLED") != "DISABLED"
        val maxDist = prefs.getFloat("max_customer_distance",
            prefs.getFloat("airport_pickup_max_distance", 5.0f)  // ??れ삀???洹μ씀?癲ル슢???鈺곗슜?η춯琉얩뜑????⑤젰??
        ).toDouble()

        // device_id: read or generate once
        var deviceId = prefs.getString("device_id", null)
        if (deviceId.isNullOrBlank()) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", deviceId).apply()
        }

        val pickupKeywords = listOf("\uD2B9\uBCC4\uC2DC")

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
        Log.w(TAG, "Service interrupted; attempting recovery")
        Log.e("UAA", "[SERVICE] Accessibility service interrupted (onInterrupt); recovering")
        RemoteLogger.logServiceDisconnected("onInterrupt")

        // ServiceState ??? ?????濚????????????筌??怨뚮옖甕걔???嚥▲꺃???
        // config/filterEngine ??繞????モ봼??stale ???ㅺ컼???袁⑸젻泳?
        try {
            config = loadConfig()
            filterEngine = FilterEngine(config.filterSettings)
            Log.i("UAA", "[SERVICE] onInterrupt config/filterEngine reloaded")
            RemoteLogger.logRecovery("accessibility", "on_interrupt", true,
                mapOf("config_reloaded" to true, "shizuku_enabled" to config.enableShizuku))
        } catch (e: Exception) {
            Log.e("UAA", "[SERVICE] onInterrupt recovery failed: ${e.message}")
            RemoteLogger.logRecovery("accessibility", "on_interrupt", false,
                mapOf("error" to (e.message ?: "unknown")))
        }

        // Shizuku ????嶺뚮ㅎ?????筌먲퐣??
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
        Log.i(TAG, "Service destroyed")
        Log.i("UAA", "[SERVICE] Service destroyed cleanly (onDestroy)")
    }
}

