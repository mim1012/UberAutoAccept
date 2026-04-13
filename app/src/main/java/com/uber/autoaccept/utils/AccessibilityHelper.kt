package com.uber.autoaccept.utils

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.uber.autoaccept.logging.RemoteLogger

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

    private fun summarizeKeywordHit(raw: String, keywords: List<String>, prefix: String): String {
        val hits = keywords.filter { raw.contains(it, ignoreCase = true) }.distinct()
        val hitSummary = if (hits.isEmpty()) "unknown" else hits.joinToString("/")
        return "<$prefix len=${raw.length} hit=$hitSummary>"
    }

    fun findAddressLikeNodes(root: AccessibilityNodeInfo?, limit: Int = 20): List<Triple<String, String, String>> {
        if (root == null) return emptyList()
        val result = mutableListOf<Triple<String, String, String>>()
        val addrTerms = listOf("시", "구", "동", "로", "길", "역", "터미널")

        fun isAddress(text: String) = text.length > 5 && addrTerms.any { text.contains(it) }

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
                            summarizeKeywordHit(raw, addrTerms, "ADDR")
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
        val keywords = listOf("콜 수락", "수락", "accept")
        val result = mutableListOf<Triple<String, String, String>>()

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || result.size >= limit) return
            try {
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                val haystack = "$text $desc".lowercase()
                if (keywords.any { haystack.contains(it) }) {
                    val rid = (node.viewIdResourceName ?: "").substringAfter(":id/", "")
                    val raw = text.ifBlank { desc }
                    result.add(
                        Triple(
                            rid,
                            node.className?.toString() ?: "",
                            summarizeKeywordHit(raw, keywords, "BTN")
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
