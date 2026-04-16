package com.uber.autoaccept.utils

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.uber.autoaccept.logging.RemoteLogger

private data class OfferDebugSnapshot(
    val markerIds: List<String>,
    val offerishIds: List<String>,
    val topIds: List<String>,
    val topClasses: List<String>,
    val addressCandidates: List<String>,
    val acceptCandidates: List<String>
) {
    fun toLogLine(): String {
        fun List<String>.orDash(): String = if (isEmpty()) "-" else joinToString(",")
        return "markers=${markerIds.orDash()} offerish=${offerishIds.orDash()} topIds=${topIds.orDash()} classes=${topClasses.orDash()} addrs=${addressCandidates.orDash()} btns=${acceptCandidates.orDash()}"
    }
}

object AccessibilityHelper {
    private const val TAG = "AccessibilityHelper"

    private val TRACKED_VIEW_IDS = setOf(
        "uda_details_pickup_address_text_view",
        "uda_details_dropoff_address_text_view",
        "uda_details_distance_text_view",
        "uda_details_duration_text_view",
        "uda_details_accept_button",
        "ub__upfront_offer_map_label",
        "pick_up_address",
        "drop_off_address"
    )

    private val ADDRESS_TERMS = listOf("\uC2DC", "\uAD6C", "\uB3D9", "\uB85C", "\uAE38", "\uC5ED", "\uD130\uBBF8\uB110")
    private val ACCEPT_TERMS = listOf("\uCF5C \uC218\uB77D", "\uC218\uB77D", "\uD655\uC778", "accept")
    private val OFFERISH_ID_TOKENS = listOf("offer", "dispatch", "pickup", "dropoff", "accept", "upfront", "pulse", "fare", "map")

    fun collectResourceIds(root: AccessibilityNodeInfo?): Map<String, Int> {
        if (root == null) return emptyMap()
        val counts = linkedMapOf<String, Int>()

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            try {
                val rid = node.viewIdResourceName ?: ""
                if (rid.isNotBlank()) {
                    val short = rid.substringAfter(":id/", rid)
                    counts[short] = (counts[short] ?: 0) + 1
                }
                for (i in 0 until node.childCount) {
                    walk(node.getChild(i))
                }
            } catch (_: Exception) {
            }
        }

        walk(root)
        return counts
    }

    private fun collectClassNames(root: AccessibilityNodeInfo?): Map<String, Int> {
        if (root == null) return emptyMap()
        val counts = linkedMapOf<String, Int>()

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            try {
                val className = node.className?.toString()?.substringAfterLast('.') ?: ""
                if (className.isNotBlank()) {
                    counts[className] = (counts[className] ?: 0) + 1
                }
                for (i in 0 until node.childCount) {
                    walk(node.getChild(i))
                }
            } catch (_: Exception) {
            }
        }

        walk(root)
        return counts
    }

    private fun summarizeKeywordHit(raw: String, keywords: List<String>, prefix: String): String {
        val hits = keywords.filter { raw.contains(it, ignoreCase = true) }.distinct()
        val hitSummary = if (hits.isEmpty()) "unknown" else hits.joinToString("/")
        return "<$prefix len=${raw.length} hit=$hitSummary>"
    }

    fun findAddressLikeNodes(root: AccessibilityNodeInfo?, limit: Int = 20): List<Triple<String, String, String>> {
        if (root == null) return emptyList()
        val result = mutableListOf<Triple<String, String, String>>()

        fun isAddress(text: String) = text.length > 5 && ADDRESS_TERMS.any { text.contains(it) }

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || result.size >= limit) return
            try {
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                val rid = (node.viewIdResourceName ?: "").substringAfter(":id/", "")
                if (isAddress(text) || isAddress(desc)) {
                    val raw = if (isAddress(text)) text else desc
                    result.add(
                        Triple(
                            rid,
                            node.className?.toString() ?: "",
                            summarizeKeywordHit(raw, ADDRESS_TERMS, "ADDR")
                        )
                    )
                }
                for (i in 0 until node.childCount) {
                    walk(node.getChild(i))
                }
            } catch (_: Exception) {
            }
        }

        walk(root)
        return result
    }

    fun findAcceptButtonCandidates(root: AccessibilityNodeInfo?, limit: Int = 10): List<Triple<String, String, String>> {
        if (root == null) return emptyList()
        val result = mutableListOf<Triple<String, String, String>>()

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || result.size >= limit) return
            try {
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                val haystack = "$text $desc".lowercase()
                if (ACCEPT_TERMS.any { haystack.contains(it.lowercase()) }) {
                    val rid = (node.viewIdResourceName ?: "").substringAfter(":id/", "")
                    val raw = text.ifBlank { desc }
                    result.add(
                        Triple(
                            rid,
                            node.className?.toString() ?: "",
                            summarizeKeywordHit(raw, ACCEPT_TERMS, "BTN")
                        )
                    )
                }
                for (i in 0 until node.childCount) {
                    walk(node.getChild(i))
                }
            } catch (_: Exception) {
            }
        }

        walk(root)
        return result
    }

    fun buildOfferDebugLine(root: AccessibilityNodeInfo?): String {
        if (root == null) return "markers=- offerish=- topIds=- classes=- addrs=- btns=-"

        val resourceCounts = collectResourceIds(root)
        val sortedIds = resourceCounts.entries.sortedByDescending { it.value }
        val topIds = sortedIds.take(12).map { "${it.key}:${it.value}" }
        val markerIds = sortedIds.map { it.key }
            .filter { it in UberOfferGate.allMarkerViewIds() }
            .distinct()
        val offerishIds = sortedIds.map { it.key }
            .filter { id -> OFFERISH_ID_TOKENS.any { token -> id.contains(token, ignoreCase = true) } }
            .distinct()
            .take(12)
        val topClasses = collectClassNames(root).entries
            .sortedByDescending { it.value }
            .take(8)
            .map { "${it.key}:${it.value}" }
        val addressCandidates = findAddressLikeNodes(root, 6).map { (rid, cls, summary) ->
            "${rid.ifBlank { "no_id" }}|${cls.substringAfterLast('.')}|$summary"
        }
        val acceptCandidates = findAcceptButtonCandidates(root, 6).map { (rid, cls, summary) ->
            "${rid.ifBlank { "no_id" }}|${cls.substringAfterLast('.')}|$summary"
        }

        return OfferDebugSnapshot(
            markerIds = markerIds,
            offerishIds = offerishIds,
            topIds = topIds,
            topClasses = topClasses,
            addressCandidates = addressCandidates,
            acceptCandidates = acceptCandidates
        ).toLogLine()
    }

    fun findNodeByViewId(rootNode: AccessibilityNodeInfo?, viewId: String): AccessibilityNodeInfo? {
        if (rootNode == null) return null
        val fullViewId = "${UberOfferGate.UBER_DRIVER_PACKAGE}:id/$viewId"
        return try {
            val node = rootNode.findAccessibilityNodeInfosByViewId(fullViewId).firstOrNull()
            if (viewId in TRACKED_VIEW_IDS) {
                RemoteLogger.logViewIdHealth(viewId, node != null)
            }
            node
        } catch (e: Exception) {
            Log.e(TAG, "findNodeByViewId error: ${e.message}")
            if (viewId in TRACKED_VIEW_IDS) {
                RemoteLogger.logViewIdHealth(viewId, false)
            }
            null
        }
    }

    fun findFirstNodeByViewIds(rootNode: AccessibilityNodeInfo?, viewIds: Iterable<String>): Pair<String, AccessibilityNodeInfo>? {
        if (rootNode == null) return null
        for (viewId in viewIds) {
            val node = findNodeByViewId(rootNode, viewId)
            if (node != null) return viewId to node
        }
        return null
    }

    fun findNodeByText(rootNode: AccessibilityNodeInfo?, text: String, exactMatch: Boolean = false): AccessibilityNodeInfo? {
        if (rootNode == null) return null
        return try {
            val nodes = rootNode.findAccessibilityNodeInfosByText(text)
            if (exactMatch) nodes.firstOrNull { it.text?.toString() == text } else nodes.firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "findNodeByText error: ${e.message}")
            null
        }
    }

    fun extractAllText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = StringBuilder()
        extractTextRecursive(node, sb)
        return sb.toString()
    }

    private fun extractTextRecursive(node: AccessibilityNodeInfo, sb: StringBuilder) {
        try {
            node.text?.let { sb.append(it).append(" ") }
            node.contentDescription?.takeIf { it != node.text }?.let { sb.append(it).append(" ") }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { extractTextRecursive(it, sb) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractTextRecursive error: ${e.message}")
        }
    }

    fun getBounds(node: AccessibilityNodeInfo): Rect {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect
    }

    fun getCenter(node: AccessibilityNodeInfo): Pair<Int, Int> {
        val rect = getBounds(node)
        return Pair(rect.centerX(), rect.centerY())
    }

    fun findClickableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isClickable) return node
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) return parent
            parent = parent.parent
        }
        return null
    }

    fun findNodesByTextOrDesc(root: AccessibilityNodeInfo?, keyword: String): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        collectNodesWithKeyword(root, keyword, result)
        return result
    }

    private fun collectNodesWithKeyword(
        node: AccessibilityNodeInfo,
        keyword: String,
        result: MutableList<AccessibilityNodeInfo>
    ) {
        try {
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            if (text.contains(keyword) || desc.contains(keyword)) {
                result.add(node)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { collectNodesWithKeyword(it, keyword, result) }
            }
        } catch (_: Exception) {
        }
    }

    fun isNodeValid(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return try {
            node.childCount
            true
        } catch (e: IllegalStateException) {
            false
        }
    }
}

object DistanceParser {
    fun parseDistance(text: String?): Double {
        if (text.isNullOrBlank()) return 0.0
        return try {
            text.replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0
        } catch (e: Exception) {
            0.0
        }
    }

    fun parseDuration(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        return try {
            text.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    fun parseFare(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        return try {
            text.replace(Regex("[^0-9,]"), "").replace(",", "").toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        }
    }
}
