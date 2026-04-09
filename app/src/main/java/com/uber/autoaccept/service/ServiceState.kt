package com.uber.autoaccept.service

import android.content.Context
import android.content.SharedPreferences
import com.uber.autoaccept.logging.RemoteLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared state between FloatingWidgetService and UberAccessibilityService.
 * When active=false, the accessibility service ignores incoming offers.
 *
 * 프로세스 kill 후 접근성 서비스가 재시작되면 SharedPreferences에서
 * 마지막 active 상태를 복원하여 사용자가 START를 다시 누를 필요 없도록 한다.
 */
object ServiceState {
    private const val PREFS_NAME = "uber_auto_accept"
    private const val KEY_LAST_ACTIVE = "service_state_last_active"

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    // UberAccessibilityService 생존 여부 (onServiceConnected/onDestroy에서 갱신)
    private val _accessibilityConnected = MutableStateFlow(false)
    val accessibilityConnected: StateFlow<Boolean> = _accessibilityConnected.asStateFlow()

    private var prefs: SharedPreferences? = null

    /** 앱/서비스 시작 시 한 번 호출하여 SharedPreferences 연결 */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun start(source: String = "unknown") {
        _active.value = true
        prefs?.edit()?.putBoolean(KEY_LAST_ACTIVE, true)?.apply()
        RemoteLogger.logServiceStateChange(
            active = true,
            source = source,
            details = mapOf(
                "accessibility_connected" to _accessibilityConnected.value
            )
        )
    }

    fun stop(source: String = "unknown") {
        _active.value = false
        prefs?.edit()?.putBoolean(KEY_LAST_ACTIVE, false)?.apply()
        RemoteLogger.logServiceStateChange(
            active = false,
            source = source,
            details = mapOf(
                "accessibility_connected" to _accessibilityConnected.value
            )
        )
    }

    fun isActive(): Boolean = _active.value

    fun setAccessibilityConnected(connected: Boolean) {
        _accessibilityConnected.value = connected
    }

    /**
     * 프로세스 재시작 후 마지막 상태 복원.
     * SharedPreferences에 active=true가 저장되어 있으면 자동 start.
     * @return true if state was restored from SharedPreferences
     */
    fun restoreIfNeeded(): Boolean {
        val lastActive = prefs?.getBoolean(KEY_LAST_ACTIVE, false) ?: false
        if (lastActive && !_active.value) {
            _active.value = true
            return true
        }
        return false
    }
}
