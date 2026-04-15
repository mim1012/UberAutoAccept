package com.uber.autoaccept.utils

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.uber.autoaccept.logging.RemoteLogger
import com.uber.autoaccept.model.ParseConfidence
import com.uber.autoaccept.model.UberOffer
import java.util.UUID

class UberOfferParser {
    companion object {
        private const val TAG = "UberOfferParser"
        private val DROPOFF_FALLBACK_IDS = listOf(
            "uda_offer_details_title",
            "uda_offer_details_subtitle",
            "leg_dropoff",
            "leg_dropoff_label"
        )
    }

    private data class AddressMatch(
        val pickup: String,
        val dropoff: String,
        val confidence: ParseConfidence,
        val parserSource: String,
        val pickupViewId: String,
        val dropoffViewId: String,
        val pickupValidated: Boolean,
        val dropoffValidated: Boolean
    )

    fun parseOfferDetails(
        rootNode: AccessibilityNodeInfo,
        existingTraceContext: com.uber.autoaccept.model.OfferTraceContext? = null
    ): UberOffer? {
        return parseByViewId(rootNode, existingTraceContext)
    }

    private fun parseByViewId(
        rootNode: AccessibilityNodeInfo,
        existingTraceContext: com.uber.autoaccept.model.OfferTraceContext?
    ): UberOffer? {
        try {
            val addressMatch = findAddresses(rootNode) ?: run {
                Log.w(TAG, "Address extraction failed")
                try {
                    val ids = AccessibilityHelper.collectResourceIds(rootNode).entries
                        .sortedByDescending { it.value }
                        .take(30)
                        .joinToString(prefix = "[", postfix = "]") { "${it.key}:${it.value}" }
                    val addrs = AccessibilityHelper.findAddressLikeNodes(rootNode, 10)
                        .joinToString(prefix = "[", postfix = "]") { (rid, cls, summary) ->
                            "($rid|$cls|${summary.replace("|", "/")})"
                        }
                    val btns = AccessibilityHelper.findAcceptButtonCandidates(rootNode, 5)
                        .joinToString(prefix = "[", postfix = "]") { (rid, cls, summary) ->
                            "($rid|$cls|${summary.replace("|", "/")})"
                        }
                    RemoteLogger.logParseResult(
                        false,
                        null,
                        "ADDR_FAIL",
                        mapOf(
                            "trace_id" to existingTraceContext?.traceId,
                            "error_code" to "ADDR_FAIL",
                            "failure_stage" to "find_addresses",
                            "ui_summary_ids" to ids,
                            "ui_summary_addrs" to addrs,
                            "ui_summary_btns" to btns
                        )
                    )
                } catch (_: Exception) {
                    RemoteLogger.logParseResult(
                        false,
                        null,
                        "ADDR_FAIL",
                        mapOf(
                            "trace_id" to existingTraceContext?.traceId,
                            "error_code" to "ADDR_FAIL",
                            "failure_stage" to "find_addresses"
                        )
                    )
                }
                return null
            }

            val tripDistanceText = AccessibilityHelper.findNodeByViewId(rootNode, "uda_details_distance_text_view")
                ?.text?.toString()
            val durationText = AccessibilityHelper.findNodeByViewId(rootNode, "uda_details_duration_text_view")
                ?.text?.toString()

            val tripDistance = DistanceParser.parseDistance(tripDistanceText)
            val estimatedTime = DistanceParser.parseDuration(durationText)
            val customerDistance = extractCustomerDistance(rootNode)
            val acceptBtn = findAcceptButton(rootNode)

            Log.d(
                TAG,
                """
                Offer parsed (${addressMatch.confidence}):
                - pickup: ${addressMatch.pickup}
                - dropoff: ${addressMatch.dropoff}
                - customerDistance: ${customerDistance}km
                - tripDistance: ${tripDistance}km
                - acceptButtonFound: ${acceptBtn != null}
                - parserSource: ${addressMatch.parserSource}
                """.trimIndent()
            )

            val traceContext = existingTraceContext ?: com.uber.autoaccept.model.OfferTraceContext(
                traceId = UUID.randomUUID().toString(),
                detectionSource = "parser",
                detectionStage = "parse"
            )

            return UberOffer(
                offerUuid = traceContext.traceId,
                traceContext = traceContext,
                pickupLocation = addressMatch.pickup,
                dropoffLocation = addressMatch.dropoff,
                customerDistance = customerDistance,
                tripDistance = tripDistance,
                estimatedFare = 0,
                estimatedTime = estimatedTime,
                acceptButtonBounds = null,
                acceptButtonNode = acceptBtn,
                parseConfidence = addressMatch.confidence,
                parserSource = addressMatch.parserSource,
                pickupViewId = addressMatch.pickupViewId,
                dropoffViewId = addressMatch.dropoffViewId,
                pickupValidated = addressMatch.pickupValidated,
                dropoffValidated = addressMatch.dropoffValidated
            )
        } catch (e: Exception) {
            Log.e(TAG, "ViewId parse error: ${e.message}", e)
            RemoteLogger.logParseResult(
                false,
                null,
                "ViewId parse error: ${e.message}",
                mapOf(
                    "trace_id" to existingTraceContext?.traceId,
                    "error_code" to "PARSE_EXCEPTION",
                    "failure_stage" to "parse_by_view_id"
                )
            )
            return null
        }
    }

    private fun findAddresses(rootNode: AccessibilityNodeInfo): AddressMatch? {
        fun looksLikeAddress(text: String?): Boolean {
            if (text.isNullOrBlank()) return false
            if (text.length < 5) return false
            val terms = listOf("시", "구", "동", "로", "길", "역", "터미널")
            return terms.any { text.contains(it) }
        }

        fun findTextByViewId(viewId: String): String? {
            return AccessibilityHelper.findNodeByViewId(rootNode, viewId)?.text?.toString()
        }

        val primaryPickup = findTextByViewId("uda_details_pickup_address_text_view")
        val primaryDropoff = findTextByViewId("uda_details_dropoff_address_text_view")
        if (looksLikeAddress(primaryPickup) && looksLikeAddress(primaryDropoff)) {
            return AddressMatch(
                pickup = primaryPickup!!,
                dropoff = primaryDropoff!!,
                confidence = ParseConfidence.HIGH,
                parserSource = "uda_details",
                pickupViewId = "uda_details_pickup_address_text_view",
                dropoffViewId = "uda_details_dropoff_address_text_view",
                pickupValidated = true,
                dropoffValidated = true
            )
        }

        val cardPickup = findTextByViewId("pick_up_address")
        val cardDropoff = findTextByViewId("drop_off_address")
        if (looksLikeAddress(cardPickup) && looksLikeAddress(cardDropoff)) {
            return AddressMatch(
                pickup = cardPickup!!,
                dropoff = cardDropoff!!,
                confidence = ParseConfidence.MEDIUM,
                parserSource = "card_viewid",
                pickupViewId = "pick_up_address",
                dropoffViewId = "drop_off_address",
                pickupValidated = true,
                dropoffValidated = true
            )
        }

        val standardPickup = findTextByViewId("uda_offer_details_pickup_title")
        val standardDropoff = findTextByViewId("uda_offer_details_dropoff_title")
        if (looksLikeAddress(standardPickup) && looksLikeAddress(standardDropoff)) {
            return AddressMatch(
                pickup = standardPickup!!,
                dropoff = standardDropoff!!,
                confidence = ParseConfidence.MEDIUM,
                parserSource = "standard_offer",
                pickupViewId = "uda_offer_details_pickup_title",
                dropoffViewId = "uda_offer_details_dropoff_title",
                pickupValidated = true,
                dropoffValidated = true
            )
        }

        val legPickup = findTextByViewId("leg_pickup_address")
        val legDropoff = findTextByViewId("leg_dropoff_address")
        if (looksLikeAddress(legPickup) && looksLikeAddress(legDropoff)) {
            return AddressMatch(
                pickup = legPickup!!,
                dropoff = legDropoff!!,
                confidence = ParseConfidence.MEDIUM,
                parserSource = "leg_offer",
                pickupViewId = "leg_pickup_address",
                dropoffViewId = "leg_dropoff_address",
                pickupValidated = true,
                dropoffValidated = true
            )
        }

        val effectivePickup = standardPickup?.takeIf(::looksLikeAddress) ?: legPickup?.takeIf(::looksLikeAddress)
        if (effectivePickup != null) {
            var resolvedDropoffViewId: String? = null
            val dropoffAlt = DROPOFF_FALLBACK_IDS.firstNotNullOfOrNull { viewId ->
                findTextByViewId(viewId)?.takeIf(::looksLikeAddress)?.also {
                    resolvedDropoffViewId = viewId
                }
            }
            if (dropoffAlt != null) {
                return AddressMatch(
                    pickup = effectivePickup,
                    dropoff = dropoffAlt,
                    confidence = ParseConfidence.LOW,
                    parserSource = "alt_dropoff",
                    pickupViewId = if (effectivePickup == standardPickup) {
                        "uda_offer_details_pickup_title"
                    } else {
                        "leg_pickup_address"
                    },
                    dropoffViewId = resolvedDropoffViewId ?: "unknown",
                    pickupValidated = true,
                    dropoffValidated = true
                )
            }
        }

        return null
    }

    private fun extractCustomerDistance(rootNode: AccessibilityNodeInfo): Double {
        val mapLabel = AccessibilityHelper.findNodeByViewId(rootNode, "ub__upfront_offer_map_label")
        if (mapLabel != null) {
            val labelText = mapLabel.text?.toString() ?: ""
            val distance = DistanceParser.parseDistance(labelText)
            if (distance > 0) return distance
        }

        val allText = AccessibilityHelper.extractAllText(rootNode)
        val parenPattern = Regex("\\(([\\d.]+)km\\)")
        val parenMatch = parenPattern.find(allText)
        if (parenMatch != null) {
            val distance = parenMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            if (distance >= 0) return distance
        }

        val patterns = listOf(
            Regex("\\d+분\\(([\\d.]+)km\\)\\s*남음"),
            Regex("픽업까지\\s*([\\d.]+)\\s*km"),
            Regex("([\\d.]+)\\s*km\\s*away", RegexOption.IGNORE_CASE),
            Regex("([\\d.]+)\\s*km\\s*to\\s*pickup", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(allText)
            if (match != null) {
                val distance = match.groupValues[1].toDoubleOrNull() ?: 0.0
                if (distance > 0) return distance
            }
        }

        return 2.0
    }

    private fun findAcceptButton(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val textVariants = listOf("콜 수락", "수락", "Accept", "ACCEPT")
        for (text in textVariants) {
            try {
                val nodes = rootNode.findAccessibilityNodeInfosByText(text) ?: continue
                val node = nodes.firstOrNull { it.text?.toString() == text } ?: nodes.firstOrNull()
                if (node != null) {
                    Log.d(TAG, "Accept button found: text=${node.text} clickable=${node.isClickable}")
                    return node
                }
            } catch (_: Exception) {
            }
        }

        val viewIds = listOf(
            "uda_details_accept_button",
            "upfront_offer_configurable_details_accept_button",
            "upfront_offer_configurable_details_auditable_accept_button"
        )
        for (viewId in viewIds) {
            val button = AccessibilityHelper.findNodeByViewId(rootNode, viewId)
            if (button != null) return button
        }

        return null
    }
}
