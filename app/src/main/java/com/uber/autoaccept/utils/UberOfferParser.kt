package com.uber.autoaccept.utils

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.uber.autoaccept.logging.RemoteLogger
import android.graphics.Rect
import com.uber.autoaccept.model.OfferSnapshot
import com.uber.autoaccept.model.OfferTraceContext
import com.uber.autoaccept.model.ParseConfidence
import com.uber.autoaccept.model.UberOffer
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

class UberOfferParser {
    companion object {
        private const val TAG = "UberOfferParser"
        private const val OFFER_LOGCAT_TAG = "UAA_OFFER"
        private val ADDRESS_TERMS = listOf("\uC2DC", "\uAD6C", "\uB3D9", "\uB85C", "\uAE38", "\uC5ED", "\uD130\uBBF8\uB110")
        private val CITY_ADDRESS_TERMS = listOf("\uD2B9\uBCC4\uC2DC", "\uAD11\uC5ED\uC2DC", "\uD2B9\uBCC4\uC790\uCE58\uC2DC")
        private val METRO_ADDRESS_TERMS = listOf(
            "\uC11C\uC6B8",
            "\uC778\uCC9C",
            "\uACBD\uAE30",
            "\uBD80\uC0B0",
            "\uB300\uAD6C",
            "\uB300\uC804",
            "\uAD11\uC8FC",
            "\uC6B8\uC0B0",
            "\uC138\uC885"
        )
        private val ACCEPT_TEXTS = listOf("\uCF5C \uC218\uB77D", "\uC218\uB77D", "Accept", "ACCEPT")
        private val DROPOFF_FALLBACK_IDS = listOf(
            "uda_offer_details_title",
            "uda_offer_details_subtitle",
            "leg_dropoff",
            "leg_dropoff_label"
        )
        private val prefetchedOffers = ConcurrentHashMap<String, UberOffer>()

        fun cachePrefetchedOffer(offer: UberOffer) {
            offer.traceContext?.traceId?.let { prefetchedOffers[it] = offer }
        }

        fun cacheSnapshotOffer(snapshot: OfferSnapshot, traceContext: OfferTraceContext) {
            buildPrefetchedOfferFromSnapshot(snapshot, traceContext)?.let { offer ->
                prefetchedOffers[traceContext.traceId] = offer
            }
        }

        private fun buildPrefetchedOfferFromSnapshot(
            snapshot: OfferSnapshot,
            traceContext: OfferTraceContext
        ): UberOffer? {
            val pickup = snapshot.pickupAddress
                ?: AccessibilityHelper.selectAddressCandidates(
                    snapshot.orderedTexts,
                    snapshot.virtualAddressCandidates
                ).getOrNull(0)
            val dropoff = snapshot.dropoffAddress
                ?: AccessibilityHelper.selectAddressCandidates(
                    snapshot.orderedTexts,
                    snapshot.virtualAddressCandidates
                ).getOrNull(1)

            if (!AccessibilityHelper.looksLikeAddressText(pickup) ||
                !AccessibilityHelper.looksLikeAddressText(dropoff)
            ) {
                return null
            }

            val confidence = when {
                snapshot.strongMarkers.isNotEmpty() && snapshot.hasPickupDropoffContent -> ParseConfidence.HIGH
                snapshot.hasPickupDropoffContent && snapshot.hasAcceptContent -> ParseConfidence.MEDIUM
                else -> ParseConfidence.LOW
            }

            val pickupNode = snapshot.addressCandidates.firstOrNull { candidate ->
                candidate.text == pickup || candidate.contentDescription == pickup
            }
            val dropoffNode = snapshot.addressCandidates.firstOrNull { candidate ->
                candidate.text == dropoff || candidate.contentDescription == dropoff
            }
            val acceptCandidate = snapshot.acceptButtonCandidates.firstOrNull()
            val customerDistance = DistanceParser.parsePickupEtaDistance(snapshot.pickupEtaText)
            val estimatedTime = DistanceParser.parseDuration(snapshot.tripDurationText)

            return UberOffer(
                offerUuid = traceContext.traceId,
                traceContext = traceContext,
                pickupLocation = pickup!!,
                dropoffLocation = dropoff!!,
                customerDistance = if (customerDistance > 0) customerDistance else 2.0,
                tripDistance = 0.0,
                estimatedFare = 0,
                estimatedTime = estimatedTime,
                acceptButtonBounds = acceptCandidate?.bounds,
                acceptButtonNode = null,
                parseConfidence = confidence,
                parserSource = "snapshot_prefetch",
                pickupViewId = pickupNode?.viewId ?: "snapshot_pickup",
                dropoffViewId = dropoffNode?.viewId ?: "snapshot_dropoff",
                pickupValidated = true,
                dropoffValidated = true,
                snapshot = snapshot
            )
        }
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
        existingTraceContext: OfferTraceContext? = null
    ): UberOffer? {
        return parseByViewId(rootNode, existingTraceContext)
    }

    private fun parseByViewId(
        rootNode: AccessibilityNodeInfo,
        existingTraceContext: OfferTraceContext?
    ): UberOffer? {
        try {
            existingTraceContext?.traceId?.let { traceId ->
                prefetchedOffers.remove(traceId)?.let { cachedOffer ->
                    val snapshotAgeMs = cachedOffer.snapshot?.let { System.currentTimeMillis() - it.capturedAtMs }
                    Log.i(
                        OFFER_LOGCAT_TAG,
                        "[trace=$traceId] parse_success parser=${cachedOffer.parserSource ?: "prefetched"} prefetched=true snapshotAgeMs=${snapshotAgeMs ?: -1}"
                    )
                    return cachedOffer
                }
            }

            val addressMatch = findAddresses(rootNode) ?: run {
                logParseFailure(rootNode, existingTraceContext)
                return null
            }

            val tripDistanceText = AccessibilityHelper.findNodeByViewId(rootNode, "uda_details_distance_text_view")
                ?.text?.toString()
            val durationText = AccessibilityHelper.findNodeByViewId(rootNode, "uda_details_duration_text_view")
                ?.text?.toString()
                ?: AccessibilityHelper.summarizeOfferTexts(
                    AccessibilityHelper.extractOrderedTexts(rootNode),
                    AccessibilityHelper.collectResourceIds(rootNode).keys
                ).tripDurationText

            val tripDistance = DistanceParser.parseDistance(tripDistanceText)
            val estimatedTime = DistanceParser.parseDuration(durationText)
            val customerDistance = extractCustomerDistance(rootNode)
            val acceptBtn = findAcceptButton(rootNode)

            val traceContext = existingTraceContext ?: OfferTraceContext(
                traceId = UUID.randomUUID().toString(),
                detectionSource = "parser",
                detectionStage = "parse"
            )

            Log.i(
                OFFER_LOGCAT_TAG,
                "[trace=${traceContext.traceId}] parse_success parser=${addressMatch.parserSource} pickupId=${addressMatch.pickupViewId} dropoffId=${addressMatch.dropoffViewId} acceptFound=${acceptBtn != null} customerKm=$customerDistance tripKm=$tripDistance"
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
            Log.e(OFFER_LOGCAT_TAG, "[trace=${existingTraceContext?.traceId ?: "none"}] parse_exception error=${e.message}")
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

    private fun logParseFailure(rootNode: AccessibilityNodeInfo, traceContext: OfferTraceContext?) {
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
            val snapshot = AccessibilityHelper.buildOfferDebugLine(rootNode)
            Log.w(OFFER_LOGCAT_TAG, "[trace=${traceContext?.traceId ?: "none"}] parse_fail ids=$ids addrs=$addrs btns=$btns snapshot=$snapshot")
            RemoteLogger.logParseResult(
                false,
                null,
                "ADDR_FAIL",
                mapOf(
                    "trace_id" to traceContext?.traceId,
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
                    "trace_id" to traceContext?.traceId,
                    "error_code" to "ADDR_FAIL",
                    "failure_stage" to "find_addresses"
                )
            )
        }
    }

    private fun findAddresses(rootNode: AccessibilityNodeInfo): AddressMatch? {
        fun looksLikeAddress(text: String?): Boolean {
            if (text.isNullOrBlank()) return false
            if (text.length < 5) return false
            return ADDRESS_TERMS.any { text.contains(it) }
        }

        fun looksLikeCityAddress(text: String?): Boolean {
            if (text.isNullOrBlank()) return false
            val candidate = text.trim()
            if (candidate.length < 10) return false
            if (candidate.endsWith("\uCABD")) return false
            return CITY_ADDRESS_TERMS.any { candidate.contains(it) }
        }

        fun looksLikeMetroAddress(text: String?): Boolean {
            if (text.isNullOrBlank()) return false
            val candidate = text.trim()
            if (candidate.length < 10) return false
            if (candidate.endsWith("\uCABD")) return false
            return METRO_ADDRESS_TERMS.any { candidate.contains(it) } && looksLikeAddress(candidate)
        }

        fun findTextByViewId(viewId: String): String? {
            return AccessibilityHelper.findNodeByViewId(rootNode, viewId)?.text?.toString()
        }

        fun findDistinctTextsByKeyword(
            keyword: String,
            predicate: (String?) -> Boolean
        ): List<String> {
            return try {
                rootNode.findAccessibilityNodeInfosByText(keyword)
                    ?.mapNotNull { node ->
                        node.text?.toString()?.trim()
                            ?.takeIf { it.isNotBlank() && predicate(it) }
                    }
                    ?.distinct()
                    ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }

        val cityAddressCandidates = linkedSetOf<String>()
        CITY_ADDRESS_TERMS.forEach { keyword ->
            cityAddressCandidates += findDistinctTextsByKeyword(keyword, ::looksLikeCityAddress)
        }
        if (cityAddressCandidates.isNotEmpty()) {
            val addresses = cityAddressCandidates.toList()
            return AddressMatch(
                pickup = addresses[0],
                dropoff = addresses.getOrElse(1) { addresses[0] },
                confidence = ParseConfidence.MEDIUM,
                parserSource = "virtual_city_keyword",
                pickupViewId = "virtual_text:\uD2B9\uBCC4/\uAD11\uC5ED\uC2DC",
                dropoffViewId = "virtual_text:\uD2B9\uBCC4/\uAD11\uC5ED\uC2DC",
                pickupValidated = true,
                dropoffValidated = true
            )
        }

        val metroAddressCandidates = linkedSetOf<String>()
        METRO_ADDRESS_TERMS.forEach { keyword ->
            metroAddressCandidates += findDistinctTextsByKeyword(keyword, ::looksLikeMetroAddress)
        }
        if (metroAddressCandidates.isNotEmpty()) {
            val addresses = metroAddressCandidates.toList()
            return AddressMatch(
                pickup = addresses[0],
                dropoff = addresses.getOrElse(1) { addresses[0] },
                confidence = ParseConfidence.LOW,
                parserSource = "virtual_metro_keyword",
                pickupViewId = "virtual_text:metro",
                dropoffViewId = "virtual_text:metro",
                pickupValidated = true,
                dropoffValidated = true
            )
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

        val orderedTexts = AccessibilityHelper.extractOrderedTexts(rootNode)
        val resourceIds = AccessibilityHelper.collectResourceIds(rootNode).keys
        val textCluster = AccessibilityHelper.summarizeOfferTexts(orderedTexts, resourceIds)
        if (textCluster.isLikelyOffer &&
            looksLikeAddress(textCluster.pickupAddress) &&
            looksLikeAddress(textCluster.dropoffAddress)
        ) {
            return AddressMatch(
                pickup = textCluster.pickupAddress!!,
                dropoff = textCluster.dropoffAddress!!,
                confidence = ParseConfidence.MEDIUM,
                parserSource = "text_cluster",
                pickupViewId = "text_cluster_pickup",
                dropoffViewId = "text_cluster_dropoff",
                pickupValidated = true,
                dropoffValidated = true
            )
        }

        val virtualAddressCandidates = AccessibilityHelper.selectAddressCandidates(
            orderedTexts,
            AccessibilityHelper.collectVirtualAddressTexts(rootNode)
        )
        val virtualPickup = virtualAddressCandidates.getOrNull(0)
        val virtualDropoff = virtualAddressCandidates.getOrNull(1)
        val hasOfferSupport = textCluster.acceptText != null ||
            textCluster.tripDurationText != null ||
            textCluster.pickupEtaText != null ||
            resourceIds.any { it.startsWith("map_marker") || it == "rxmap" || it == "map" }
        if (hasOfferSupport && looksLikeAddress(virtualPickup) && looksLikeAddress(virtualDropoff)) {
            return AddressMatch(
                pickup = virtualPickup!!,
                dropoff = virtualDropoff!!,
                confidence = ParseConfidence.LOW,
                parserSource = if (textCluster.isLikelyOffer) "virtual_text_cluster" else "virtual_text_search",
                pickupViewId = "virtual_text_pickup",
                dropoffViewId = "virtual_text_dropoff",
                pickupValidated = true,
                dropoffValidated = true
            )
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
        val patterns = listOf(
            Regex("\\(([\\d.]+)km\\)"),
            Regex("([\\d.]+)\\s*km\\s*away", RegexOption.IGNORE_CASE),
            Regex("([\\d.]+)\\s*km\\s*to\\s*pickup", RegexOption.IGNORE_CASE),
            Regex("([\\d.]+)\\s*km", RegexOption.IGNORE_CASE)
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
        for (text in ACCEPT_TEXTS) {
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

        val orderedTexts = AccessibilityHelper.extractOrderedTexts(rootNode)
        val acceptText = orderedTexts.firstOrNull { text ->
            ACCEPT_TEXTS.any { term -> text.contains(term, ignoreCase = true) }
        }
        if (acceptText != null) {
            for (term in ACCEPT_TEXTS) {
                val candidate = AccessibilityHelper.findNodeByText(rootNode, term)
                if (candidate != null) return candidate
            }
        }

        return null
    }
}
