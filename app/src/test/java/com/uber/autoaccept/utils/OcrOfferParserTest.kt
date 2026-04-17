package com.uber.autoaccept.utils

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrOfferParserTest {

    @Test
    fun `findOfferCandidate extracts pickup and dropoff from ordered OCR blocks`() {
        val blocks = listOf(
            OcrTextBlock("가맹 전용 콜", Rect(0, 0, 100, 20)),
            OcrTextBlock("23분 운행", Rect(0, 30, 100, 50)),
            OcrTextBlock("7분(2.0km) 남음", Rect(0, 60, 100, 80)),
            OcrTextBlock("공중전화, 서울특별시 중구 남대문로2가", Rect(0, 90, 200, 110)),
            OcrTextBlock("대한민국 서울특별시 중구 을지로 30", Rect(0, 120, 200, 140)),
            OcrTextBlock("콜 수락", Rect(0, 150, 100, 170))
        )

        val candidate = OcrOfferParser.findOfferCandidate(blocks)

        requireNotNull(candidate)
        assertEquals("공중전화, 서울특별시 중구 남대문로2가", candidate.pickup)
        assertEquals("대한민국 서울특별시 중구 을지로 30", candidate.dropoff)
        assertEquals("23분 운행", candidate.tripDurationText)
        assertEquals("7분(2.0km) 남음", candidate.pickupEtaText)
    }

    @Test
    fun `findOfferCandidate rejects non offer OCR text`() {
        val blocks = listOf(
            OcrTextBlock("홈", Rect(0, 0, 50, 20)),
            OcrTextBlock("수입", Rect(0, 30, 50, 50)),
            OcrTextBlock("메시지", Rect(0, 60, 60, 80)),
            OcrTextBlock("콜 수락", Rect(0, 90, 80, 110))
        )

        val candidate = OcrOfferParser.findOfferCandidate(blocks)

        assertNull(candidate)
    }

    @Test
    fun `findOfferCandidate requires two address candidates`() {
        val blocks = listOf(
            OcrTextBlock("23분 운행", Rect(0, 0, 100, 20)),
            OcrTextBlock("7분(2.0km) 남음", Rect(0, 30, 100, 50)),
            OcrTextBlock("대한민국 서울특별시 중구 을지로 30", Rect(0, 60, 200, 80))
        )

        val candidate = OcrOfferParser.findOfferCandidate(blocks)

        assertNull(candidate)
    }
}
