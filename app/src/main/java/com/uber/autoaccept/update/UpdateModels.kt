package com.uber.autoaccept.update

data class UpdateCheckResult(
    val allowed: Boolean,
    val hasUpdate: Boolean,
    val latestVersion: String?,
    val appUrl: String?,
    val shizukuUrl: String?,
    val message: String?
)
