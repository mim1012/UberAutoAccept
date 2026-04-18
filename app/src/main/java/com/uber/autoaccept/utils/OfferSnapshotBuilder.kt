package com.uber.autoaccept.utils

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.uber.autoaccept.model.OfferSnapshot
import com.uber.autoaccept.model.SnapshotNode

object OfferSnapshotBuilder {
    private const val MAX_NODE_COUNT = 240
    private const val MAX_DEPTH = 10

    fun build(
        root: AccessibilityNodeInfo,
        source: String,
        sampleIndex: Int,
        rootCount: Int
    ): OfferSnapshot {
        val nodes = mutableListOf<SnapshotNode>()

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_DEPTH || nodes.size >= MAX_NODE_COUNT) return
            val bounds = runCatching {
                Rect().also { node.getBoundsInScreen(it) }
            }.getOrNull()
            nodes += SnapshotNode(
                viewId = node.viewIdResourceName?.substringAfter(":id/", node.viewIdResourceName),
                className = node.className?.toString(),
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                bounds = bounds,
                clickable = node.isClickable
            )
            for (index in 0 until node.childCount) {
                walk(runCatching { node.getChild(index) }.getOrNull(), depth + 1)
            }
        }

        walk(root, 0)

        val orderedTexts = AccessibilityHelper.extractOrderedTexts(root)
        val virtualAddressCandidates = AccessibilityHelper.collectVirtualAddressTexts(root)
        val resourceIdCounts = AccessibilityHelper.collectResourceIds(root)
        val contentSignal = AccessibilityHelper.inspectOfferContent(root)
        val strongMarkers = UberOfferGate.confirmationMarkers(resourceIdCounts.keys)
        val addressCandidates = nodes.filter { node ->
            AccessibilityHelper.looksLikeAddressText(node.text) ||
                AccessibilityHelper.looksLikeAddressText(node.contentDescription)
        }
        val acceptButtonCandidates = nodes.filter { node ->
            val haystack = listOfNotNull(node.text, node.contentDescription).joinToString(" ").lowercase()
            node.viewId in setOf(
                "uda_details_accept_button",
                "upfront_offer_configurable_details_accept_button",
                "upfront_offer_configurable_details_auditable_accept_button"
            ) || listOf("콜 수락", "수락", "accept", "확인").any { haystack.contains(it.lowercase()) }
        }

        return OfferSnapshot(
            capturedAtMs = System.currentTimeMillis(),
            source = source,
            sampleIndex = sampleIndex,
            packageName = root.packageName?.toString() ?: UberOfferGate.UBER_DRIVER_PACKAGE,
            rootClassName = root.className?.toString(),
            rootCount = rootCount,
            nodes = nodes,
            orderedTexts = orderedTexts,
            virtualAddressCandidates = virtualAddressCandidates,
            resourceIdCounts = resourceIdCounts,
            strongMarkers = strongMarkers,
            addressCandidates = addressCandidates,
            acceptButtonCandidates = acceptButtonCandidates,
            hasPickupDropoffContent = contentSignal.hasPickupDropoffContent,
            hasTimeContent = contentSignal.hasTimeContent,
            hasAcceptContent = contentSignal.hasAcceptContent,
            reason = contentSignal.reason,
            pickupAddress = contentSignal.textCluster.pickupAddress,
            dropoffAddress = contentSignal.textCluster.dropoffAddress,
            tripDurationText = contentSignal.textCluster.tripDurationText,
            pickupEtaText = contentSignal.textCluster.pickupEtaText,
            textDigest = orderedTexts.take(12).joinToString(" | ").take(500)
        )
    }

    fun score(snapshot: OfferSnapshot): Int {
        var score = 0
        if (snapshot.strongMarkers.isNotEmpty()) score += 6
        if (snapshot.hasPickupDropoffContent) score += 5
        if (snapshot.hasTimeContent) score += 3
        if (snapshot.hasAcceptContent) score += 4
        score += snapshot.addressCandidates.size.coerceAtMost(4)
        score += snapshot.acceptButtonCandidates.size.coerceAtMost(3)
        if (snapshot.pickupAddress != null) score += 2
        if (snapshot.dropoffAddress != null) score += 2
        if (snapshot.reason.contains("structured", ignoreCase = true)) score += 2
        return score
    }

    fun pickBest(snapshots: List<OfferSnapshot>): OfferSnapshot? {
        return snapshots.maxWithOrNull(
            compareBy<OfferSnapshot> { score(it) }
                .thenBy { it.sampleIndex }
                .thenBy { it.nodes.size }
        )
    }
}
