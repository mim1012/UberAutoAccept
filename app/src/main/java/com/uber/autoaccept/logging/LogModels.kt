package com.uber.autoaccept.logging

import com.google.gson.annotations.SerializedName

/**
 * Log entry type
 */
enum class LogType {
    @SerializedName("parse") PARSE,
    @SerializedName("action") ACTION,
    @SerializedName("viewid_health") VIEWID_HEALTH,
    @SerializedName("lifecycle") LIFECYCLE,
    @SerializedName("heartbeat") HEARTBEAT,
    @SerializedName("debug") DEBUG,
    @SerializedName("shizuku") SHIZUKU
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
 * Parsed offer data included in parse log entries
 */
data class ParsedOfferData(
    @SerializedName("offer_uuid") val offerUuid: String,
    val pickup: String,
    val dropoff: String,
    @SerializedName("customer_distance") val customerDistance: Double,
    @SerializedName("trip_distance") val tripDistance: Double,
    @SerializedName("parse_confidence") val parseConfidence: String,
    @SerializedName("accept_button_found") val acceptButtonFound: Boolean
)

