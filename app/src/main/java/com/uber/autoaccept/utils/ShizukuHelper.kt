package com.uber.autoaccept.utils

object ShizukuHelper {
    fun isAvailable(): Boolean = ShizukuConnectionManager.isAvailable()

    fun hasPermission(): Boolean = ShizukuConnectionManager.hasPermission()

    fun isServiceBound(): Boolean = ShizukuConnectionManager.isServiceBound()

    fun currentStateName(): String = ShizukuConnectionManager.currentStateName()

    fun requestPermissionIfNeeded() {
        ShizukuConnectionManager.requestPermissionIfNeeded()
    }

    fun bindService(trigger: String = "legacy_bind") {
        ShizukuConnectionManager.start(true, trigger)
    }

    fun unbindService(reason: String = "legacy_unbind") {
        ShizukuConnectionManager.stop(reason)
    }

    suspend fun tap(x: Float, y: Float, times: Int = 5): Boolean {
        return ShizukuConnectionManager.executeTap(x, y, times).success
    }
}
