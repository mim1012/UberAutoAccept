package com.uber.autoaccept.utils

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

object AccessibilityHelper {
    private const val TAG = "AccessibilityHelper"
    private const val UBER_PACKAGE = "com.ubercab.driver"
    
    fun findNodeByViewId(rootNode: AccessibilityNodeInfo?, viewId: String): AccessibilityNodeInfo? {
        if (rootNode == null) return null
        val fullViewId = "$UBER_PACKAGE:id/$viewId"
        return try {
            rootNode.findAccessibilityNodeInfosByViewId(fullViewId).firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "findNodeByViewId error: ${e.message}")
            null
        }
    }
    
    fun findNodeByText(rootNode: AccessibilityNodeInfo?, text: String, exactMatch: Boolean = false): AccessibilityNodeInfo? {
        if (rootNode == null) return null
        return try {
            val nodes = rootNode.findAccessibilityNodeInfosByText(text)
            if (exactMatch) {
                nodes.firstOrNull { it.text?.toString() == text }
            } else {
                nodes.firstOrNull()
            }
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
