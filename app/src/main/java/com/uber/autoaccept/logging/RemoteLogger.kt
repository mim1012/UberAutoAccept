package com.uber.autoaccept.logging

import android.content.Context
import android.os.Build
import android.util.Log
import com.uber.autoaccept.auth.AuthManager
import com.uber.autoaccept.model.FilterResult
import com.uber.autoaccept.model.OfferTraceContext
import com.uber.autoaccept.model.UberOffer
import com.uber.autoaccept.supabase.SupabaseClient
import com.uber.autoaccept.supabase.SupabaseConfig
import kotlinx.coroutines.*
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 원격 로깅 싱글톤.
 * - uber_logs: 파싱/액션/라이프사이클 로그 (anon INSERT)
 * - uber_users: 기기 상태 UPSERT (device_id 기준, 10초마다 heartbeat)
 */
object RemoteLogger {
    private const val TAG = "RemoteLogger"
    private const val MAX_QUEUE_SIZE = 200
    private const val FLUSH_INTERVAL_MS = 10_000L
    private const val HEARTBEAT_INTERVAL_MS = 60_000L

    private val queue = ConcurrentLinkedQueue<LogEntry>()
    private val viewIdLastState = ConcurrentHashMap<String, Boolean>()
    private val throttleState = ConcurrentHashMap<String, Long>()

    private var scope: CoroutineScope? = null
    private var flushJob: Job? = null
    private var heartbeatJob: Job? = null

    private var deviceId: String = ""
    private var phoneNumber: String = ""
    private var appVersion: String = ""
    private var enabled: Boolean = false
    private var serviceConnectedTime: Long = 0L
    private var appContext: Context? = null

    var currentStateSupplier: (() -> String)? = null

    private data class OfferLogRow(
        val device_id: String,
        val log_type: String,
        val data: Map<String, Any?>
    )

    private fun shouldLog(throttleKey: String?, throttleMs: Long): Boolean {
        if (throttleKey.isNullOrBlank() || throttleMs <= 0L) return true
        val now = System.currentTimeMillis()
        val last = throttleState[throttleKey]
        if (last != null && now - last < throttleMs) return false
        throttleState[throttleKey] = now
        return true
    }

    private fun withTrace(details: Map<String, Any?>, traceContext: OfferTraceContext?): Map<String, Any?> {
        if (traceContext == null) return details
        return mapOf(
            "trace_id" to traceContext.traceId,
            "detected_at_ms" to traceContext.detectedAtMs,
            "detection_source" to traceContext.detectionSource,
            "detection_stage" to traceContext.detectionStage
        ) + details
    }

    private fun withOffer(details: Map<String, Any?>, offer: UberOffer?): Map<String, Any?> {
        if (offer == null) return details
        return withTrace(
            mapOf(
                "offer_uuid" to offer.offerUuid,
                "pickup" to offer.pickupLocation,
                "dropoff" to offer.dropoffLocation,
                "customer_distance" to offer.customerDistance,
                "trip_distance" to offer.tripDistance,
                "parser_source" to offer.parserSource
            ) + details,
            offer.traceContext
        )
    }

    fun initialize(context: Context, deviceId: String, enabled: Boolean) {
        this.appContext = context.applicationContext
        this.deviceId = deviceId.ifBlank {
            AuthManager.getInstance(context).getDeviceId()
        }
        this.phoneNumber = AuthManager.getInstance(context).getPhoneNumber() ?: ""
        this.enabled = enabled
        this.appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }

        if (!enabled) {
            Log.d(TAG, "Remote logging disabled")
            return
        }

        serviceConnectedTime = System.currentTimeMillis()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        flushJob = scope?.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flush()
            }
        }

        heartbeatJob = scope?.launch {
            while (isActive) {
                sendHeartbeat()
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }

        Log.i(TAG, "Initialized: device=$deviceId phone=$phoneNumber")
    }

    fun shutdown() {
        val currentScope = scope
        scope = null            // 즉시 null — initialize() race 방지
        flushJob?.cancel()
        heartbeatJob?.cancel()
        flushJob = null
        heartbeatJob = null
        currentScope?.launch {
            // 서비스 종료 상태 기록
            runCatching { markServiceDisconnected() }
            flush()
            currentScope.cancel()
        }
        viewIdLastState.clear()
        throttleState.clear()
        Log.i(TAG, "Shutdown")
    }

    fun logServiceConnected() {
        enqueue(LogEntry(
            type = LogType.LIFECYCLE,
            data = mapOf(
                "event_type" to "connected",
                "details" to "Service onServiceConnected",
                "timestamp" to System.currentTimeMillis()
            )
        ))
    }

    fun logServiceDisconnected(reason: String) {
        enqueue(LogEntry(
            type = LogType.LIFECYCLE,
            data = mapOf(
                "event_type" to "disconnected",
                "details" to reason,
                "timestamp" to System.currentTimeMillis()
            )
        ))
        scope?.launch {
            runCatching { markServiceDisconnected() }
            flush()
        }
    }

    /**
     * 복구 이벤트 로그 — 원인 추적의 핵심.
     * @param component 복구 대상 ("service_state", "shizuku", "accessibility")
     * @param trigger 복구를 유발한 원인 ("on_interrupt", "tap_ipc_failure", "binder_dead", "event_auto_recover", "process_restart")
     * @param success 복구 성공 여부
     * @param details 추가 컨텍스트 (에러 메시지, 시도 횟수 등)
     */
    fun logRecovery(component: String, trigger: String, success: Boolean, details: Map<String, Any?> = emptyMap()) {
        enqueue(LogEntry(
            type = LogType.LIFECYCLE,
            data = mapOf(
                "event_type" to "recovery",
                "component" to component,
                "trigger" to trigger,
                "success" to success,
                "shizuku_bound" to com.uber.autoaccept.utils.ShizukuHelper.isServiceBound(),
                "service_active" to com.uber.autoaccept.service.ServiceState.isActive(),
                "timestamp" to System.currentTimeMillis()
            ) + details
        ))
        // 복구 이벤트는 즉시 flush — 디바이스가 다시 죽을 수 있음
        flushNow()
    }

    fun logParseResult(
        success: Boolean,
        offerData: ParsedOfferData?,
        error: String?,
        details: Map<String, Any?> = emptyMap()
    ) {
        val traceId = offerData?.traceId ?: details["trace_id"] as? String
        enqueue(LogEntry(
            type = LogType.PARSE,
            data = mapOf(
                "success" to success,
                "offer" to offerData,
                "error_message" to error,
                "trace_id" to traceId
            ) + details
        ))
    }

    fun logActionResult(
        action: String,
        success: Boolean,
        details: String?,
        offer: UberOffer? = null,
        extra: Map<String, Any?> = emptyMap()
    ) {
        enqueue(LogEntry(
            type = LogType.ACTION,
            data = withOffer(
                mapOf(
                    "action" to action,
                    "success" to success,
                    "details" to details,
                    "state" to (currentStateSupplier?.invoke() ?: "unknown")
                ) + extra,
                offer
            )
        ))
    }

    fun logFilterResult(offer: UberOffer, result: FilterResult) {
        val decision = when (result) {
            is FilterResult.Accepted -> "accepted"
            is FilterResult.Rejected -> "rejected"
        }
        val rejectCode = (result as? FilterResult.Rejected)?.rejectCode
        enqueue(LogEntry(
            type = LogType.DEBUG,
            data = withOffer(
                mapOf(
                    "event_type" to "filter_result",
                    "decision" to decision,
                    "reasons" to result.reasons,
                    "summary" to result.summary,
                    "matched_conditions" to result.matchedConditions,
                    "enabled_conditions" to result.enabledConditions.toList().sorted(),
                    "keyword_hits" to result.keywordHits,
                    "reject_code" to rejectCode,
                    "timestamp" to System.currentTimeMillis()
                ),
                offer
            )
        ))
    }

    fun logEngineCommand(source: String, action: String, details: Map<String, Any?> = emptyMap()) {
        enqueue(LogEntry(
            type = LogType.LIFECYCLE,
            data = mapOf(
                "event_type" to "engine_command",
                "source" to source,
                "action" to action,
                "service_active" to com.uber.autoaccept.service.ServiceState.isActive(),
                "timestamp" to System.currentTimeMillis()
            ) + details
        ))
    }

    fun logServiceStateChange(active: Boolean, source: String, details: Map<String, Any?> = emptyMap()) {
        enqueue(LogEntry(
            type = LogType.LIFECYCLE,
            data = mapOf(
                "event_type" to "service_state_change",
                "active" to active,
                "source" to source,
                "timestamp" to System.currentTimeMillis()
            ) + details
        ))
    }

    fun logViewIdHealth(viewId: String, found: Boolean) {
        val lastState = viewIdLastState[viewId]
        if (lastState == found) return
        viewIdLastState[viewId] = found
        enqueue(LogEntry(
            type = LogType.VIEWID_HEALTH,
            data = mapOf("view_id" to viewId, "found" to found)
        ))
    }

    fun logAccessibilityEvent(packageName: String, eventType: Int, serviceActive: Boolean) {
        enqueue(LogEntry(
            type = LogType.DEBUG,
            data = mapOf(
                "event_type" to "accessibility_event",
                "package_name" to packageName,
                "event_code" to eventType,
                "service_active" to serviceActive,
                "timestamp" to System.currentTimeMillis()
            )
        ))
    }

    fun logOpenCVDetection(detected: Boolean, buttonCx: Float? = null, buttonCy: Float? = null, reason: String? = null) {
        enqueue(LogEntry(
            type = LogType.DEBUG,
            data = mapOf(
                "event_type" to "opencv_detection",
                "detected" to detected,
                "button_cx" to buttonCx,
                "button_cy" to buttonCy,
                "reason" to (reason ?: if (detected) "ok" else "not_found"),
                "timestamp" to System.currentTimeMillis()
            )
        ))
    }

    fun logDiagnostic(reason: String, details: Map<String, Any?> = emptyMap()) {
        enqueue(LogEntry(
            type = LogType.DEBUG,
            data = mapOf(
                "event_type" to "diagnostic",
                "reason" to reason,
                "details" to details,
                "timestamp" to System.currentTimeMillis()
            ) + details
        ))
    }

    fun logOfferDetection(
        stage: String,
        source: String,
        success: Boolean,
        details: Map<String, Any?> = emptyMap(),
        traceContext: OfferTraceContext? = null,
        throttleKey: String? = null,
        throttleMs: Long = 0L
    ) {
        if (!shouldLog(throttleKey, throttleMs)) return
        enqueue(LogEntry(
            type = LogType.DEBUG,
            data = withTrace(
                mapOf(
                    "event_type" to "offer_detection",
                    "stage" to stage,
                    "source" to source,
                    "success" to success,
                    "state" to (currentStateSupplier?.invoke() ?: "unknown"),
                    "timestamp" to System.currentTimeMillis()
                ) + details,
                traceContext
            )
        ))
    }

    fun logShizukuBind(success: Boolean, latencyMs: Long, error: String? = null) {
        enqueue(LogEntry(
            type = LogType.SHIZUKU,
            data = mapOf(
                "event_type" to "bind",
                "success" to success,
                "latency_ms" to latencyMs,
                "error" to error,
                "timestamp" to System.currentTimeMillis()
            )
        ))
    }

    fun logShizukuTap(
        success: Boolean,
        latencyMs: Long,
        x: Int,
        y: Int,
        times: Int,
        traceId: String? = null
    ) {
        enqueue(LogEntry(
            type = LogType.SHIZUKU,
            data = mapOf(
                "event_type" to "tap",
                "success" to success,
                "latency_ms" to latencyMs,
                "x" to x,
                "y" to y,
                "times" to times,
                "trace_id" to traceId,
                "timestamp" to System.currentTimeMillis()
            )
        ))
    }

    fun logShizukuDisconnect(reason: String) {
        enqueue(LogEntry(
            type = LogType.SHIZUKU,
            data = mapOf(
                "event_type" to "disconnect",
                "reason" to reason,
                "timestamp" to System.currentTimeMillis()
            )
        ))
    }

    fun logShizukuRebind(trigger: String, hasPermission: Boolean, alreadyBound: Boolean,
                         extra: Map<String, Any?> = emptyMap()) {
        enqueue(LogEntry(
            type = LogType.SHIZUKU,
            data = mapOf(
                "event_type" to "rebind_attempt",
                "trigger" to trigger,
                "has_permission" to hasPermission,
                "already_bound" to alreadyBound,
                "timestamp" to System.currentTimeMillis()
            ) + extra
        ))
    }

    fun flushNow() {
        if (!enabled) return
        scope?.launch { flush() }
    }

    private fun enqueue(entry: LogEntry) {
        if (!enabled) return
        queue.offer(entry)
        while (queue.size > MAX_QUEUE_SIZE) queue.poll()
    }

    private suspend fun flush() {
        if (queue.isEmpty()) return

        val entries = mutableListOf<LogEntry>()
        while (entries.size < MAX_QUEUE_SIZE) {
            val entry = queue.poll() ?: break
            entries.add(entry)
        }
        if (entries.isEmpty()) return

        try {
            val rows = entries.map { entry ->
                OfferLogRow(
                    device_id = deviceId,
                    log_type = entry.type.name.lowercase(),
                    data = entry.data
                )
            }
            SupabaseClient.restPost("uber_logs", rows, SupabaseConfig.SUPABASE_ANON_KEY)
        } catch (e: Exception) {
            Log.w(TAG, "Flush failed: ${e.message}")
            for (entry in entries) {
                if (queue.size < MAX_QUEUE_SIZE) queue.offer(entry)
            }
        }
    }

    /** uber_users 테이블에 기기 상태 UPSERT (device_id 기준 merge) */
    private suspend fun sendHeartbeat() {
        try {
            val now = Instant.now().toString()
            val filterMode = appContext
                ?.getSharedPreferences("uber_auto_accept", Context.MODE_PRIVATE)
                ?.getString("filter_mode", "BOTH") ?: "BOTH"

            val row = mapOf(
                "device_id" to deviceId,
                "phone_number" to phoneNumber.ifEmpty { null },
                "service_connected" to true,
                "current_state" to (currentStateSupplier?.invoke() ?: "unknown"),
                "last_heartbeat_at" to now,
                "uptime_seconds" to (System.currentTimeMillis() - serviceConnectedTime) / 1000,
                "device_name" to Build.MODEL,
                "os_version" to Build.VERSION.RELEASE,
                "app_version" to appVersion,
                "filter_mode" to filterMode,
                "updated_at" to now
            )
            SupabaseClient.restUpsert("uber_users", row, SupabaseConfig.SUPABASE_ANON_KEY, onConflict = "device_id")
        } catch (e: Exception) {
            Log.w(TAG, "Heartbeat failed: ${e.message}")
        }
    }

    /** 서비스 종료 시 uber_users의 service_connected를 false로 업데이트 */
    private suspend fun markServiceDisconnected() {
        val now = Instant.now().toString()
        val row = mapOf(
            "device_id" to deviceId,
            "service_connected" to false,
            "current_state" to "Idle",
            "updated_at" to now
        )
        SupabaseClient.restUpsert("uber_users", row, SupabaseConfig.SUPABASE_ANON_KEY, onConflict = "device_id")
    }
}
