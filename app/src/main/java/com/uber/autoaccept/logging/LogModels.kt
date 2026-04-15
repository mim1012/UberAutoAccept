package com.uber.autoaccept.logging

import com.google.gson.annotations.SerializedName

enum class LogType {
    @SerializedName("parse") PARSE,
    @SerializedName("action") ACTION,
    @SerializedName("viewid_health") VIEWID_HEALTH,
    @SerializedName("lifecycle") LIFECYCLE,
    @SerializedName("heartbeat") HEARTBEAT,
    @SerializedName("debug") DEBUG,
    @SerializedName("shizuku") SHIZUKU
}

data class LogEntry(
    val type: LogType,
    val timestamp: Long = System.currentTimeMillis(),
    val data: Map<String, Any?>
)

data class ParsedOfferData(
    @SerializedName("offer_uuid") val offerUuid: String,
    @SerializedName("trace_id") val traceId: String? = null,
    val pickup: String,
    val dropoff: String,
    @SerializedName("customer_distance") val customerDistance: Double,
    @SerializedName("trip_distance") val tripDistance: Double,
    @SerializedName("parse_confidence") val parseConfidence: String,
    @SerializedName("accept_button_found") val acceptButtonFound: Boolean,
    @SerializedName("parser_source") val parserSource: String? = null,
    @SerializedName("pickup_view_id") val pickupViewId: String? = null,
    @SerializedName("dropoff_view_id") val dropoffViewId: String? = null,
    @SerializedName("pickup_validated") val pickupValidated: Boolean = false,
    @SerializedName("dropoff_validated") val dropoffValidated: Boolean = false
)
