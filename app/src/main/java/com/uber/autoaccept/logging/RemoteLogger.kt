package com.uber.autoaccept.logging

import android.content.Context
import android.os.Build
import android.util.Log
import com.uber.autoaccept.auth.AuthManager
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
        flushJob?.cancel()
        heartbeatJob?.cancel()
        scope?.launch {
            // 서비스 종료 상태 기록
            runCatching { markServiceDisconnected() }
            flush()
            scope?.cancel()
            scope = null
        }
        viewIdLastState.clear()
        Log.i(TAG, "Shutdown")
    }

    fun logServiceConnected() {
        enqueue(LogEntry(
            type = LogType.LIFECYCLE,
            data = mapOf("event_type" to "connected", "details" to "Service onServiceConnected")
        ))
    }

    fun logServiceDisconnected(reason: String) {
        enqueue(LogEntry(
            type = LogType.LIFECYCLE,
            data = mapOf("event_type" to "disconnected", "details" to reason)
        ))
        scope?.launch {
            runCatching { markServiceDisconnected() }
            flush()
        }
    }

    fun logParseResult(success: Boolean, offerData: ParsedOfferData?, error: String?) {
        enqueue(LogEntry(
            type = LogType.PARSE,
            data = mapOf("success" to success, "offer" to offerData, "error_message" to error)
        ))
    }

    fun logActionResult(action: String, success: Boolean, details: String?) {
        enqueue(LogEntry(
            type = LogType.ACTION,
            data = mapOf("action" to action, "success" to success, "details" to details)
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
