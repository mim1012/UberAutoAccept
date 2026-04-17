package com.uber.autoaccept.utils

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Screenshot OCR fallback for short-lived Uber offer cards.
 *
 * Primary parser path should stay accessibility-first.
 * This utility is only used after accessibility/virtual-text parsing fails.
 */
data class OcrTextBlock(
    val text: String,
    val bounds: Rect? = null
)

data class OcrOfferCandidate(
    val pickup: String,
    val dropoff: String,
    val tripDurationText: String? = null,
    val pickupEtaText: String? = null,
    val rawBlocks: List<OcrTextBlock> = emptyList()
)

object OcrOfferParser {
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognize(bitmap: Bitmap, scopeRect: Rect? = null): List<OcrTextBlock> {
        val scopedBitmap = cropToScope(bitmap, scopeRect)
        val image = InputImage.fromBitmap(scopedBitmap, 0)
        val blocks = suspendCancellableCoroutine<List<OcrTextBlock>> { cont ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    cont.resume(result.toBlocks(scopeRect))
                }
                .addOnFailureListener {
                    cont.resume(emptyList())
                }
        }
        if (scopedBitmap !== bitmap && !scopedBitmap.isRecycled) {
            scopedBitmap.recycle()
        }
        return blocks
    }

    fun findOfferCandidate(blocks: List<OcrTextBlock>): OcrOfferCandidate? {
        if (blocks.isEmpty()) return null

        val orderedBlocks = blocks.sortedWith(
            compareBy<OcrTextBlock>({ it.bounds?.top ?: Int.MAX_VALUE }, { it.bounds?.left ?: Int.MAX_VALUE })
        )
        val orderedTexts = orderedBlocks.map { it.text }
        val addressCandidates = AccessibilityHelper.selectAddressCandidates(orderedTexts)
        if (addressCandidates.size < 2) return null

        val tripDurationText = orderedTexts.firstOrNull {
            Regex("""(?:\d+\s*시간\s*)?\d+\s*분\s*운행""").containsMatchIn(it)
        }
        val pickupEtaText = orderedTexts.firstOrNull {
            Regex("""\d+\s*분\s*\([\d.]+\s*km\)\s*남음""").containsMatchIn(it)
        }

        return OcrOfferCandidate(
            pickup = addressCandidates[0],
            dropoff = addressCandidates[1],
            tripDurationText = tripDurationText,
            pickupEtaText = pickupEtaText,
            rawBlocks = orderedBlocks
        )
    }

    private fun Text.toBlocks(scopeRect: Rect?): List<OcrTextBlock> {
        return textBlocks.mapNotNull { block ->
            val text = block.text?.trim().orEmpty()
            if (text.isBlank()) return@mapNotNull null
            val adjustedBounds = block.boundingBox?.let { bounds ->
                if (scopeRect == null) bounds
                else Rect(
                    bounds.left + scopeRect.left,
                    bounds.top + scopeRect.top,
                    bounds.right + scopeRect.left,
                    bounds.bottom + scopeRect.top
                )
            }
            OcrTextBlock(text = text, bounds = adjustedBounds)
        }
    }

    private fun cropToScope(bitmap: Bitmap, scopeRect: Rect?): Bitmap {
        if (scopeRect == null) return bitmap
        val left = scopeRect.left.coerceAtLeast(0)
        val top = scopeRect.top.coerceAtLeast(0)
        val right = scopeRect.right.coerceAtMost(bitmap.width)
        val bottom = scopeRect.bottom.coerceAtMost(bitmap.height)
        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)
        if (left == 0 && top == 0 && width == bitmap.width && height == bitmap.height) {
            return bitmap
        }
        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }
}
