package com.uber.autoaccept.logging

import com.google.gson.annotations.SerializedName

/**
 * Log entry type
 */
enum class LogType {
    @SerializedName("heartbeat") HEARTBEAT,
    @SerializedName("parse") PARSE,
    @SerializedName("action") ACTION,
    @SerializedName("viewid_health") VIEWID_HEALTH,
    @SerializedName("lifecycle") LIFECYCLE
}

/**
 * Single log entry queued for batch sending
 */
data class LogEntry(
    val type: LogType,
    val timestamp: Long = System.currentTimeMillis(),
    val data: Map<String, Any?>
)

/**
 * Heartbeat payload sent every 60 seconds
 */
data class HeartbeatPayload(
    @SerializedName("device_id") val deviceId: String,
    val timestamp: Long = System.currentTimeMillis(),
    @SerializedName("service_connected") val serviceConnected: Boolean,
    @SerializedName("current_state") val currentState: String,
    @SerializedName("uptime_seconds") val uptimeSeconds: Long
)

/**
 * Parsed offer data included in parse log entries
 */
data class ParsedOfferData(
    @SerializedName("offer_uuid") val offerUuid: String,
    val pickup: String,
    val dropoff: String,
    @SerializedName("customer_distance") val customerDistance: Double,
    @SerializedName("trip_distance") val tripDistance: Double,
    @SerializedName("parse_confidence") val parseConfidence: String
)

/**
 * Batch payload wrapping multiple log entries
 */
data class BatchPayload(
    @SerializedName("device_id") val deviceId: String,
    val entries: List<LogEntry>
)
