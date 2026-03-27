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
        .daemon(false)
        .processNameSuffix("shizuku_service")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    @Volatile
    private var userService: IShizukuService? = null
    private var bindStartTime: Long = 0L

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val latency = System.currentTimeMillis() - bindStartTime
            if (binder.pingBinder()) {
                userService = IShizukuService.Stub.asInterface(binder)
                Log.i(TAG, "UserService connected (${latency}ms)")
                RemoteLogger.logShizukuBind(true, latency)
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

    fun isServiceBound(): Boolean = userService != null

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
        val available = isAvailable()
        val perm = hasPermission()
        val bound = userService != null
        RemoteLogger.logShizukuRebind(trigger, perm, bound,
            mapOf("available" to available))

        if (!perm) {
            Log.w(TAG, "Shizuku 바인딩 불가 — available=$available, perm=$perm (trigger=$trigger)")
            return
        }
        if (bound) {
            Log.d(TAG, "이미 바인딩됨 — 스킵")
            return
        }
        try {
            bindStartTime = System.currentTimeMillis()
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
            Log.i(TAG, "bindUserService 요청 (trigger=$trigger)")
        } catch (e: Exception) {
            Log.e(TAG, "bindUserService 실패: ${e.message}")
            RemoteLogger.logShizukuBind(false, 0, e.message)
        }
    }

    fun unbindService() {
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

    suspend fun tap(x: Float, y: Float, times: Int = 5): Boolean = withContext(Dispatchers.IO) {
        val svc = userService
        if (svc == null) {
            Log.e(TAG, "UserService 미연결 — 즉시 재바인딩 시도")
            RemoteLogger.logShizukuTap(false, 0, x.toInt(), y.toInt(), times)
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
            RemoteLogger.logShizukuTap(ok, latency, x.toInt(), y.toInt(), times)
            ok
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            Log.e(TAG, "tap IPC 실패: ${e.message} — 즉시 재바인딩 시도")
            RemoteLogger.logShizukuTap(false, latency, x.toInt(), y.toInt(), times)
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
