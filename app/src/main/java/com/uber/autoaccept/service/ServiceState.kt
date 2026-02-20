package com.uber.autoaccept.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared state between FloatingWidgetService and UberAccessibilityService.
 * When active=false, the accessibility service ignores incoming offers.
 */
object ServiceState {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun start() { _active.value = true }
    fun stop() { _active.value = false }
    fun isActive(): Boolean = _active.value
}
