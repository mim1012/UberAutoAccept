package com.uber.autoaccept.utils

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.uber.autoaccept.BuildConfig
import com.uber.autoaccept.IShizukuService
import com.uber.autoaccept.logging.RemoteLogger
import com.uber.autoaccept.service.ShizukuUserService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

object ShizukuHelper {
    private const val TAG = "ShizukuHelper"

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ShizukuUserService::class.java.name)
    )
        .daemon(true)
        .processNameSuffix("shizuku_service")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    @Volatile
    private var userService: IShizukuService? = null
    private var bindStartTime: Long = 0L

    private enum class ShizukuState {
        UNAVAILABLE,
        AVAILABLE_NO_PERMISSION,
        AVAILABLE_PERMISSION_NO_SERVICE,
        BOUND_OK,
        BOUND_STALE
    }

    private val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingRetry: Runnable? = null
    private var retryDelayMs: Long = 3000L
    private var availabilityBurstAttempts: Int = 0
    private var lastBindAttemptAt: Long = 0L
    private const val RETRY_MIN_MS = 3000L
    private const val RETRY_MAX_MS = 30000L
    private const val AVAILABILITY_BURST_LIMIT = 4
    private const val AVAILABILITY_BURST_DELAY_MS = 1500L
    private const val BIND_THROTTLE_MS = 1200L

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val latency = System.currentTimeMillis() - bindStartTime
            if (binder.pingBinder()) {
                userService = IShizukuService.Stub.asInterface(binder)
                Log.i(TAG, "UserService connected (${latency}ms)")
                RemoteLogger.logShizukuBind(true, latency)
                availabilityBurstAttempts = 0
                cancelPendingRetry()
                retryDelayMs = RETRY_MIN_MS
            } else {
                Log.e(TAG, "Invalid binder received")
                RemoteLogger.logShizukuBind(false, latency, "invalid_binder")
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            userService = null
            Log.w(TAG, "UserService disconnected — 3초 후 재바인딩 시도")
            RemoteLogger.logShizukuDisconnect("service_disconnected")
            scheduleRebind(3000, "service_disconnected")
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received — 자동 바인딩 시도")
        tryBind("binder_received")
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder dead — 3초 후 재바인딩 시도")
        userService = null
        RemoteLogger.logShizukuDisconnect("binder_dead")
        scheduleRebind(3000, "binder_dead")
    }

    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Exception) {
        false
    }

    fun hasPermission(): Boolean = try {
        isAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }

    fun isServiceBound(): Boolean = currentState() == ShizukuState.BOUND_OK

    private fun currentState(): ShizukuState {
        val available = isAvailable()
        if (!available) return ShizukuState.UNAVAILABLE

        val svc = userService
        if (svc != null) {
            return try {
                val binder = svc.asBinder()
                if (binder != null && binder.pingBinder()) ShizukuState.BOUND_OK else ShizukuState.BOUND_STALE
            } catch (_: Exception) {
                ShizukuState.BOUND_STALE
            }
        }

        return if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            ShizukuState.AVAILABLE_PERMISSION_NO_SERVICE
        } else {
            ShizukuState.AVAILABLE_NO_PERMISSION
        }
    }

    fun requestPermissionIfNeeded() {
        if (!isAvailable()) {
            Log.w(TAG, "Shizuku 서비스 미실행")
            return
        }
        if (!hasPermission()) {
            try {
                Shizuku.requestPermission(1001)
            } catch (e: Exception) {
                Log.e(TAG, "권한 요청 실패: ${e.message}")
            }
        }
    }

    /** Shizuku 리스너 등록 + 즉시 바인딩 시도 */
    fun bindService() {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        } catch (e: Exception) {
            Log.w(TAG, "리스너 등록 실패: ${e.message}")
        }

        tryBind("bind_service")
    }

    /** 순수 바인딩 시도 (리스너 등록 없이) — 재바인딩에도 사용 */
    private fun tryBind(trigger: String = "manual") {
        val now = System.currentTimeMillis()
        if (now - lastBindAttemptAt < BIND_THROTTLE_MS && !trigger.startsWith("retry_")) {
            Log.d(TAG, "bind throttled — trigger=$trigger")
            return
        }
        lastBindAttemptAt = now

        val state = currentState()
        val available = state != ShizukuState.UNAVAILABLE
        val perm = state == ShizukuState.AVAILABLE_PERMISSION_NO_SERVICE || state == ShizukuState.BOUND_OK || state == ShizukuState.BOUND_STALE
        val bound = state == ShizukuState.BOUND_OK
        RemoteLogger.logShizukuRebind(trigger, perm, bound,
            mapOf("available" to available, "state" to state.name, "burst_attempts" to availabilityBurstAttempts))

        when (state) {
            ShizukuState.UNAVAILABLE -> {
                userService = null
                if (availabilityBurstAttempts < AVAILABILITY_BURST_LIMIT) {
                    availabilityBurstAttempts += 1
                    Log.w(TAG, "Shizuku binder 미수신 — 빠른 재시도 ${availabilityBurstAttempts}/$AVAILABILITY_BURST_LIMIT (trigger=$trigger)")
                    scheduleRebind(AVAILABILITY_BURST_DELAY_MS, "binder_unavailable")
                } else {
                    Log.w(TAG, "Shizuku binder 미수신 지속 — 백오프 재시도 전환 (trigger=$trigger)")
                    scheduleRetry("binder_unavailable")
                }
                return
            }
            ShizukuState.AVAILABLE_NO_PERMISSION -> {
                availabilityBurstAttempts = 0
                Log.w(TAG, "Shizuku 권한 없음 (trigger=$trigger) — ${retryDelayMs}ms 후 재시도")
                scheduleRetry("no_permission")
                return
            }
            ShizukuState.BOUND_OK -> {
                availabilityBurstAttempts = 0
                Log.d(TAG, "이미 바인딩됨 — 스킵")
                cancelPendingRetry()
                return
            }
            ShizukuState.BOUND_STALE -> {
                Log.w(TAG, "Stale binder 감지 — userService 초기화 후 재바인딩")
                userService = null
            }
            ShizukuState.AVAILABLE_PERMISSION_NO_SERVICE -> {
                // proceed to bind
            }
        }

        availabilityBurstAttempts = 0
        try {
            bindStartTime = System.currentTimeMillis()
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
            Log.i(TAG, "bindUserService 요청 (trigger=$trigger, state=$state)")
        } catch (e: Exception) {
            Log.e(TAG, "bindUserService 실패: ${e.message}")
            RemoteLogger.logShizukuBind(false, 0, e.message)
            scheduleRetry("bind_exception")
        }
    }

    /** 백오프 재시도 예약 (3s → 6s → 12s → 24s → 30s max). 성공 시 cancelPendingRetry로 취소. */
    private fun scheduleRetry(reason: String) {
        cancelPendingRetry()
        val delay = retryDelayMs
        val runnable = Runnable {
            pendingRetry = null
            tryBind("retry_$reason")
        }
        pendingRetry = runnable
        retryHandler.postDelayed(runnable, delay)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(RETRY_MAX_MS)
    }

    private fun cancelPendingRetry() {
        pendingRetry?.let { retryHandler.removeCallbacks(it) }
        pendingRetry = null
    }

    fun unbindService() {
        cancelPendingRetry()
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        } catch (_: Exception) {}
        try {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
            userService = null
            Log.i(TAG, "unbindUserService 완료")
        } catch (e: Exception) {
            Log.e(TAG, "unbindUserService 실패: ${e.message}")
        }
    }

    suspend fun tap(
        x: Float,
        y: Float,
        times: Int = 5,
        traceId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val svc = userService
        if (svc == null) {
            Log.e(TAG, "UserService 미연결 — 즉시 재바인딩 시도")
            RemoteLogger.logShizukuTap(false, 0, x.toInt(), y.toInt(), times, traceId)
            RemoteLogger.logRecovery("shizuku", "tap_null_service", false,
                mapOf("x" to x.toInt(), "y" to y.toInt()))
            scheduleRebind(0, "tap_null_service")
            return@withContext false
        }

        val startTime = System.currentTimeMillis()
        try {
            val ok = svc.tapRepeat(x.toInt(), y.toInt(), times, 30)
            val latency = System.currentTimeMillis() - startTime
            Log.i(TAG, "tap(${x.toInt()},${y.toInt()}) x$times ${if (ok) "✅" else "❌"} (${latency}ms)")
            RemoteLogger.logShizukuTap(ok, latency, x.toInt(), y.toInt(), times, traceId)
            ok
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            Log.e(TAG, "tap IPC 실패: ${e.message} — 즉시 재바인딩 시도")
            RemoteLogger.logShizukuTap(false, latency, x.toInt(), y.toInt(), times, traceId)
            RemoteLogger.logRecovery("shizuku", "tap_ipc_failure", false,
                mapOf("error" to (e.message ?: "unknown"), "latency_ms" to latency))
            userService = null
            scheduleRebind(1000, "tap_ipc_failure")
            false
        }
    }

    /** 메인 스레드에서 지연 후 재바인딩 시도 */
    private fun scheduleRebind(delayMs: Long, trigger: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Log.i(TAG, "재바인딩 시도 (trigger=$trigger, delayMs=$delayMs)")
            tryBind(trigger)
        }, delayMs)
    }
}
