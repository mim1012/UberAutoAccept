package com.uber.autoaccept.utils

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.uber.autoaccept.BuildConfig
import com.uber.autoaccept.IShizukuService
import com.uber.autoaccept.logging.RemoteLogger
import com.uber.autoaccept.service.ShizukuUserService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

data class ShizukuStatusSnapshot(
    val enabled: Boolean,
    val available: Boolean,
    val hasPermission: Boolean,
    val isBound: Boolean,
    val state: String,
    val retryDelayMs: Long,
    val availabilityBurstAttempts: Int,
    val pendingRebind: Boolean
)

data class ShizukuTapResult(
    val success: Boolean,
    val reason: String,
    val latencyMs: Long,
    val x: Int,
    val y: Int,
    val times: Int,
    val state: String,
    val error: String? = null,
    val rebindScheduled: Boolean = false
)

object ShizukuConnectionManager {
    private const val TAG = "ShizukuConnectionMgr"
    private const val RETRY_MIN_MS = 3000L
    private const val RETRY_MAX_MS = 30000L
    private const val AVAILABILITY_BURST_LIMIT = 4
    private const val AVAILABILITY_BURST_DELAY_MS = 1500L
    private const val BIND_THROTTLE_MS = 1200L
    private const val PERMISSION_REQUEST_CODE = 1001

    private enum class ConnectionState {
        DISABLED,
        UNAVAILABLE,
        AVAILABLE_NO_PERMISSION,
        AVAILABLE_PERMISSION_NO_SERVICE,
        BINDING,
        BOUND_OK,
        BOUND_STALE,
        STOPPED
    }

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ShizukuUserService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("shizuku_service")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile
    private var userService: IShizukuService? = null
    private var bindStartTime: Long = 0L
    private var lastBindAttemptAt: Long = 0L
    private var retryDelayMs: Long = RETRY_MIN_MS
    private var availabilityBurstAttempts: Int = 0
    private var pendingRetry: Runnable? = null
    private var listenersRegistered = false
    private var enabled = false
    private var connectionState = ConnectionState.STOPPED

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val latency = System.currentTimeMillis() - bindStartTime
            if (binder.pingBinder()) {
                synchronized(lock) {
                    userService = IShizukuService.Stub.asInterface(binder)
                    availabilityBurstAttempts = 0
                    retryDelayMs = RETRY_MIN_MS
                    cancelPendingRetryLocked()
                    updateStateLocked(ConnectionState.BOUND_OK, "service_connected", mapOf("latency_ms" to latency))
                }
                Log.i(TAG, "UserService connected (${latency}ms)")
                RemoteLogger.logShizukuBind(true, latency)
            } else {
                Log.e(TAG, "Invalid binder received")
                synchronized(lock) {
                    updateStateLocked(ConnectionState.BOUND_STALE, "invalid_binder")
                }
                RemoteLogger.logShizukuBind(false, latency, "invalid_binder")
                scheduleRebind(1000, "invalid_binder")
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.w(TAG, "UserService disconnected")
            synchronized(lock) {
                userService = null
                updateStateLocked(ConnectionState.AVAILABLE_PERMISSION_NO_SERVICE, "service_disconnected")
            }
            RemoteLogger.logShizukuDisconnect("service_disconnected")
            scheduleRebind(3000, "service_disconnected")
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received")
        var shouldConnect = false
        synchronized(lock) {
            if (!enabled) {
                updateStateLocked(ConnectionState.DISABLED, "binder_received_while_disabled")
            } else {
                updateStateLocked(resolveConnectionStateLocked(), "binder_received")
                shouldConnect = true
            }
        }
        if (shouldConnect) {
            ensureConnected("binder_received")
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder dead")
        synchronized(lock) {
            userService = null
            updateStateLocked(ConnectionState.UNAVAILABLE, "binder_dead")
        }
        RemoteLogger.logShizukuDisconnect("binder_dead")
        scheduleRebind(3000, "binder_dead")
    }

    fun start(isEnabled: Boolean, trigger: String = "start") {
        synchronized(lock) {
            enabled = isEnabled
            if (!enabled) {
                stopLocked("disabled_$trigger", removeListeners = true)
                updateStateLocked(ConnectionState.DISABLED, trigger)
                return
            }
            registerListenersLocked()
            updateStateLocked(resolveConnectionStateLocked(), "start_$trigger")
        }
        ensureConnected(trigger)
    }

    fun refreshConfig(isEnabled: Boolean, trigger: String) {
        start(isEnabled, trigger)
    }

    fun stop(reason: String = "stop") {
        synchronized(lock) {
            enabled = false
            stopLocked(reason, removeListeners = true)
            updateStateLocked(ConnectionState.STOPPED, reason)
        }
    }

    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Exception) {
        false
    }

    fun hasPermission(): Boolean = try {
        isAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    fun isServiceBound(): Boolean = snapshot().isBound

    fun currentStateName(): String = snapshot().state

    fun snapshot(): ShizukuStatusSnapshot = synchronized(lock) {
        val state = effectiveStateLocked()
        ShizukuStatusSnapshot(
            enabled = enabled,
            available = state != ConnectionState.UNAVAILABLE && state != ConnectionState.DISABLED && state != ConnectionState.STOPPED,
            hasPermission = hasPermissionLocked(),
            isBound = state == ConnectionState.BOUND_OK,
            state = state.name,
            retryDelayMs = retryDelayMs,
            availabilityBurstAttempts = availabilityBurstAttempts,
            pendingRebind = pendingRetry != null
        )
    }

    fun requestPermissionIfNeeded() {
        if (!isAvailable()) {
            Log.w(TAG, "Shizuku unavailable")
            synchronized(lock) {
                updateStateLocked(ConnectionState.UNAVAILABLE, "request_permission_unavailable")
            }
            return
        }
        if (!hasPermission()) {
            try {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
                synchronized(lock) {
                    updateStateLocked(ConnectionState.AVAILABLE_NO_PERMISSION, "request_permission")
                }
            } catch (e: Exception) {
                Log.e(TAG, "requestPermission failed: ${e.message}")
                RemoteLogger.logShizukuDisconnect("permission_request_failed:${e.message ?: "unknown"}")
            }
        }
    }

    fun ensureConnected(trigger: String = "manual") {
        val shouldBind = synchronized(lock) {
            if (!enabled) {
                updateStateLocked(ConnectionState.DISABLED, "ensure_connected_ignored")
                false
            } else {
                val now = System.currentTimeMillis()
                if (now - lastBindAttemptAt < BIND_THROTTLE_MS && !trigger.startsWith("retry_")) {
                    Log.d(TAG, "bind throttled: $trigger")
                    false
                } else {
                    lastBindAttemptAt = now

                    val state = resolveConnectionStateLocked()
                    val snapshot = snapshotLocked(state)
                    RemoteLogger.logShizukuRebind(
                        trigger = trigger,
                        hasPermission = snapshot.hasPermission,
                        alreadyBound = snapshot.isBound,
                        extra = mapOf(
                            "available" to snapshot.available,
                            "state" to snapshot.state,
                            "enabled" to snapshot.enabled,
                            "burst_attempts" to snapshot.availabilityBurstAttempts,
                            "pending_rebind" to snapshot.pendingRebind
                        )
                    )

                    when (state) {
                        ConnectionState.DISABLED, ConnectionState.STOPPED -> false
                        ConnectionState.UNAVAILABLE -> {
                            userService = null
                            if (availabilityBurstAttempts < AVAILABILITY_BURST_LIMIT) {
                                availabilityBurstAttempts += 1
                                updateStateLocked(ConnectionState.UNAVAILABLE, "binder_unavailable")
                                scheduleRebindLocked(AVAILABILITY_BURST_DELAY_MS, "binder_unavailable")
                            } else {
                                updateStateLocked(ConnectionState.UNAVAILABLE, "binder_unavailable_backoff")
                                scheduleRetryLocked("binder_unavailable")
                            }
                            false
                        }
                        ConnectionState.AVAILABLE_NO_PERMISSION -> {
                            availabilityBurstAttempts = 0
                            updateStateLocked(ConnectionState.AVAILABLE_NO_PERMISSION, "no_permission")
                            scheduleRetryLocked("no_permission")
                            false
                        }
                        ConnectionState.BOUND_OK -> {
                            availabilityBurstAttempts = 0
                            cancelPendingRetryLocked()
                            false
                        }
                        ConnectionState.BOUND_STALE -> {
                            userService = null
                            updateStateLocked(ConnectionState.AVAILABLE_PERMISSION_NO_SERVICE, "stale_binder")
                            true
                        }
                        ConnectionState.BINDING,
                        ConnectionState.AVAILABLE_PERMISSION_NO_SERVICE -> {
                            updateStateLocked(ConnectionState.BINDING, trigger)
                            true
                        }
                    }
                }
            }
        }

        if (!shouldBind) return

        try {
            bindStartTime = System.currentTimeMillis()
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
            Log.i(TAG, "bindUserService requested (trigger=$trigger)")
        } catch (e: Exception) {
            Log.e(TAG, "bindUserService failed: ${e.message}")
            synchronized(lock) {
                updateStateLocked(resolveConnectionStateLocked(), "bind_exception", mapOf("error" to (e.message ?: "unknown")))
                scheduleRetryLocked("bind_exception")
            }
            RemoteLogger.logShizukuBind(false, 0, e.message)
        }
    }

    suspend fun executeTap(x: Float, y: Float, times: Int = 5): ShizukuTapResult = withContext(Dispatchers.IO) {
        val px = x.toInt()
        val py = y.toInt()
        val svc = synchronized(lock) { userService }
        if (svc == null) {
            val result = ShizukuTapResult(
                success = false,
                reason = "tap_null_service",
                latencyMs = 0,
                x = px,
                y = py,
                times = times,
                state = currentStateName(),
                rebindScheduled = true
            )
            recordTapResult(result)
            scheduleRebind(0, "tap_null_service")
            return@withContext result
        }

        val startTime = System.currentTimeMillis()
        try {
            val ok = svc.tapRepeat(px, py, times, 30)
            val latency = System.currentTimeMillis() - startTime
            val result = ShizukuTapResult(
                success = ok,
                reason = if (ok) "tap_ok" else "tap_returned_false",
                latencyMs = latency,
                x = px,
                y = py,
                times = times,
                state = currentStateName()
            )
            recordTapResult(result)
            result
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            synchronized(lock) {
                userService = null
                updateStateLocked(ConnectionState.BOUND_STALE, "tap_ipc_failure", mapOf("error" to (e.message ?: "unknown")))
            }
            val result = ShizukuTapResult(
                success = false,
                reason = "tap_ipc_failure",
                latencyMs = latency,
                x = px,
                y = py,
                times = times,
                state = currentStateName(),
                error = e.message,
                rebindScheduled = true
            )
            recordTapResult(result)
            scheduleRebind(1000, "tap_ipc_failure")
            result
        }
    }

    private fun recordTapResult(result: ShizukuTapResult) {
        RemoteLogger.logShizukuTap(
            success = result.success,
            latencyMs = result.latencyMs,
            x = result.x,
            y = result.y,
            times = result.times,
            extra = mapOf(
                "reason" to result.reason,
                "state" to result.state,
                "error" to result.error,
                "rebind_scheduled" to result.rebindScheduled
            )
        )
        if (!result.success) {
            RemoteLogger.logRecovery(
                component = "shizuku",
                trigger = result.reason,
                success = false,
                details = mapOf(
                    "x" to result.x,
                    "y" to result.y,
                    "times" to result.times,
                    "latency_ms" to result.latencyMs,
                    "error" to result.error,
                    "state" to result.state,
                    "rebind_scheduled" to result.rebindScheduled
                )
            )
        }
    }

    private fun resolveConnectionStateLocked(): ConnectionState {
        if (!enabled) return ConnectionState.DISABLED
        if (!isAvailable()) return ConnectionState.UNAVAILABLE
        if (!hasPermissionLocked()) return ConnectionState.AVAILABLE_NO_PERMISSION

        val svc = userService
        if (svc == null) {
            return if (pendingRetry != null) ConnectionState.BINDING else ConnectionState.AVAILABLE_PERMISSION_NO_SERVICE
        }

        return try {
            if (svc.asBinder()?.pingBinder() == true) ConnectionState.BOUND_OK else ConnectionState.BOUND_STALE
        } catch (_: Exception) {
            ConnectionState.BOUND_STALE
        }
    }

    private fun hasPermissionLocked(): Boolean = try {
        isAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    private fun effectiveStateLocked(): ConnectionState {
        val resolved = resolveConnectionStateLocked()
        return when (connectionState) {
            ConnectionState.BINDING,
            ConnectionState.DISABLED,
            ConnectionState.STOPPED -> connectionState
            else -> resolved
        }
    }

    private fun snapshotLocked(state: ConnectionState = effectiveStateLocked()): ShizukuStatusSnapshot {
        return ShizukuStatusSnapshot(
            enabled = enabled,
            available = state != ConnectionState.UNAVAILABLE && state != ConnectionState.DISABLED && state != ConnectionState.STOPPED,
            hasPermission = hasPermissionLocked(),
            isBound = state == ConnectionState.BOUND_OK,
            state = state.name,
            retryDelayMs = retryDelayMs,
            availabilityBurstAttempts = availabilityBurstAttempts,
            pendingRebind = pendingRetry != null
        )
    }

    private fun registerListenersLocked() {
        if (listenersRegistered) return
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            listenersRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "listener registration failed: ${e.message}")
            RemoteLogger.logShizukuDisconnect("listener_registration_failed:${e.message ?: "unknown"}")
        }
    }

    private fun stopLocked(reason: String, removeListeners: Boolean) {
        cancelPendingRetryLocked()
        userService = null
        if (removeListeners && listenersRegistered) {
            try {
                Shizuku.removeBinderReceivedListener(binderReceivedListener)
                Shizuku.removeBinderDeadListener(binderDeadListener)
            } catch (_: Exception) {
            }
            listenersRegistered = false
        }
        try {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
            RemoteLogger.logShizukuDisconnect(reason)
        } catch (e: Exception) {
            Log.e(TAG, "unbindUserService failed: ${e.message}")
            RemoteLogger.logShizukuDisconnect("unbind_failed:${e.message ?: "unknown"}")
        }
    }

    private fun scheduleRetryLocked(reason: String) {
        cancelPendingRetryLocked()
        val delay = retryDelayMs
        val runnable = Runnable {
            synchronized(lock) {
                pendingRetry = null
            }
            ensureConnected("retry_$reason")
        }
        pendingRetry = runnable
        mainHandler.postDelayed(runnable, delay)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(RETRY_MAX_MS)
        updateStateLocked(resolveConnectionStateLocked(), "schedule_retry", mapOf("reason" to reason, "delay_ms" to delay))
    }

    private fun scheduleRebind(delayMs: Long, trigger: String) {
        synchronized(lock) {
            scheduleRebindLocked(delayMs, trigger)
        }
    }

    private fun scheduleRebindLocked(delayMs: Long, trigger: String) {
        cancelPendingRetryLocked()
        val runnable = Runnable {
            synchronized(lock) {
                pendingRetry = null
            }
            Log.i(TAG, "Rebind attempt (trigger=$trigger delayMs=$delayMs)")
            ensureConnected(trigger)
        }
        pendingRetry = runnable
        mainHandler.postDelayed(runnable, delayMs)
        updateStateLocked(resolveConnectionStateLocked(), "schedule_rebind", mapOf("trigger" to trigger, "delay_ms" to delayMs))
    }

    private fun cancelPendingRetryLocked() {
        pendingRetry?.let { mainHandler.removeCallbacks(it) }
        pendingRetry = null
    }

    private fun updateStateLocked(
        state: ConnectionState,
        reason: String,
        details: Map<String, Any?> = emptyMap()
    ) {
        connectionState = state
        RemoteLogger.logShizukuState(
            state = state.name,
            reason = reason,
            details = details + mapOf(
                "enabled" to enabled,
                "available" to (state != ConnectionState.UNAVAILABLE && state != ConnectionState.DISABLED && state != ConnectionState.STOPPED),
                "has_permission" to hasPermissionLocked(),
                "is_bound" to (state == ConnectionState.BOUND_OK),
                "retry_delay_ms" to retryDelayMs,
                "pending_rebind" to (pendingRetry != null),
                "burst_attempts" to availabilityBurstAttempts
            )
        )
    }
}
