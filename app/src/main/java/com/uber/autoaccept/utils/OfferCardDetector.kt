package com.uber.autoaccept.utils

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * OpenCV 기반 오퍼 카드 및 수락 버튼 감지기
 *
 * 감지 전략:
 * 1단계: 화면 하단 50%에서 흰색 큰 직사각형 = 오퍼 카드
 * 2단계: 카드 하단 35% 영역에서 어두운 직사각형 = 수락 버튼
 *
 * 실제 Uber 오퍼 화면 기준:
 * - 오퍼 카드: 흰색/연회색 배경 라운드 카드 (화면 하단 ~45%)
 * - 수락 버튼: 짙은 회색/검정 배경 + "콜 수락" 흰색 텍스트
 */
data class OfferCardResult(
    val cardRect: Rect,
    val buttonRect: Rect,
    val buttonCenterX: Float,
    val buttonCenterY: Float
)

class OfferCardDetector {

    companion object {
        private const val TAG = "OfferCardDetector"

        // 오퍼 카드 조건: 화면 너비의 60% 이상, 화면 하단 절반 높이의 25% 이상
        private const val CARD_MIN_WIDTH_RATIO = 0.60f
        private const val CARD_MIN_HEIGHT_RATIO = 0.25f

        // 버튼 조건: 카드 너비의 60% 이상, 최소 40px 높이
        private const val BTN_MIN_WIDTH_RATIO = 0.60f
        private const val BTN_MIN_HEIGHT_PX = 40

        // 흰색 카드 임계값 (그레이스케일 > 210 = 밝은 영역)
        private const val WHITE_THRESHOLD = 210.0

        // 어두운 버튼 임계값 (그레이스케일 < 100 = 어두운 영역)
        private const val DARK_THRESHOLD = 100.0
    }

    /**
     * Bitmap에서 오퍼 카드와 수락 버튼을 감지
     * @return 감지 성공 시 OfferCardResult, 실패 시 null
     */
    fun detect(bitmap: Bitmap): OfferCardResult? {
        return try {
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)

            val cardRect = findWhiteCard(mat, bitmap.width, bitmap.height)
            if (cardRect == null) {
                Log.d(TAG, "오퍼 카드 미감지")
                mat.release()
                return null
            }
            Log.d(TAG, "오퍼 카드 감지: $cardRect")

            val buttonRect = findDarkButton(mat, cardRect)
            if (buttonRect == null) {
                Log.d(TAG, "수락 버튼 미감지 (카드는 감지됨)")
                mat.release()
                return null
            }
            Log.i(TAG, "수락 버튼 감지: center=(${buttonRect.exactCenterX()}, ${buttonRect.exactCenterY()})")

            mat.release()

            OfferCardResult(
                cardRect = cardRect,
                buttonRect = buttonRect,
                buttonCenterX = buttonRect.exactCenterX(),
                buttonCenterY = buttonRect.exactCenterY()
            )
        } catch (e: Exception) {
            Log.e(TAG, "OpenCV 감지 예외: ${e.message}", e)
            null
        }
    }

    /**
     * 화면 하단 50% 영역에서 흰색 큰 직사각형(오퍼 카드) 검출
     */
    private fun findWhiteCard(mat: Mat, screenW: Int, screenH: Int): Rect? {
        val roiTop = screenH / 2
        val roiH = screenH - roiTop

        val roi = Mat(mat, org.opencv.core.Rect(0, roiTop, screenW, roiH))
        val gray = Mat()
        Imgproc.cvtColor(roi, gray, Imgproc.COLOR_RGBA2GRAY)

        val mask = Mat()
        Imgproc.threshold(gray, mask, WHITE_THRESHOLD, 255.0, Imgproc.THRESH_BINARY)

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, org.opencv.core.Size(20.0, 20.0))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(mask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        gray.release(); mask.release(); roi.release()

        val minW = screenW * CARD_MIN_WIDTH_RATIO
        val minH = roiH * CARD_MIN_HEIGHT_RATIO

        val best = contours
            .map { Imgproc.boundingRect(it) }
            .filter { r -> r.width >= minW && r.height >= minH }
            .maxByOrNull { it.area() } ?: return null

        // ROI 기준 좌표를 화면 전체 기준으로 변환
        return Rect(best.x, best.y + roiTop, best.x + best.width, best.y + roiTop + best.height)
    }

    /**
     * 카드 하단 35% 영역에서 어두운 직사각형(수락 버튼) 검출
     */
    private fun findDarkButton(mat: Mat, cardRect: Rect): Rect? {
        val searchTop = cardRect.top + (cardRect.height() * 0.65f).toInt()
        val searchH = cardRect.bottom - searchTop
        val searchW = cardRect.width()

        if (searchH <= 0 || searchW <= 0) return null

        val roi = Mat(mat, org.opencv.core.Rect(cardRect.left, searchTop, searchW, searchH))
        val gray = Mat()
        Imgproc.cvtColor(roi, gray, Imgproc.COLOR_RGBA2GRAY)

        val mask = Mat()
        Imgproc.threshold(gray, mask, DARK_THRESHOLD, 255.0, Imgproc.THRESH_BINARY_INV)

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, org.opencv.core.Size(10.0, 10.0))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(mask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        gray.release(); mask.release(); roi.release()

        val minW = searchW * BTN_MIN_WIDTH_RATIO

        val best = contours
            .map { Imgproc.boundingRect(it) }
            .filter { r -> r.width >= minW && r.height >= BTN_MIN_HEIGHT_PX }
            .maxByOrNull { it.area() } ?: return null

        // ROI 기준 → 화면 전체 기준 변환
        return Rect(
            cardRect.left + best.x,
            searchTop + best.y,
            cardRect.left + best.x + best.width,
            searchTop + best.y + best.height
        )
    }
}
