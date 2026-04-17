package com.uber.autoaccept.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityHelperTextClusterTest {

    @Test
    fun `offer text cluster is detected from observed live sample`() {
        val texts = listOf(
            "🏆 가맹 전용 콜 🏆",
            "23분 운행",
            "5.00",
            "7분(2.0km) 남음",
            "김성조치과의원, 서울특별시 종로구 혜화동 대학로 149",
            "대한민국 서울특별시 동대문구 전농동 124-12",
            "동쪽",
            "콜 수락"
        )
        val resourceIds = listOf("rxmap", "map", "map_marker", "map_marker_pin_head", "map_marker_anchor")

        val cluster = AccessibilityHelper.summarizeOfferTexts(texts, resourceIds)

        assertTrue(cluster.isLikelyOffer)
        assertEquals("23분 운행", cluster.tripDurationText)
        assertEquals("7분(2.0km) 남음", cluster.pickupEtaText)
        assertEquals("김성조치과의원, 서울특별시 종로구 혜화동 대학로 149", cluster.pickupAddress)
        assertEquals("대한민국 서울특별시 동대문구 전농동 124-12", cluster.dropoffAddress)
        assertEquals("콜 수락", cluster.acceptText)
    }

    @Test
    fun `job board empty state is rejected as non offer`() {
        val texts = listOf(
            "지금은 요청이 없습니다",
            "더 많은 요청이 들어오면 알려드리겠습니다.",
            "운행 리스트"
        )
        val resourceIds = listOf("driver_offers_job_board_content_container", "driver_offers_job_board_toolbar")

        val cluster = AccessibilityHelper.summarizeOfferTexts(texts, resourceIds)

        assertFalse(cluster.isLikelyOffer)
        assertEquals("blacklist_text_present", cluster.reason)
        assertTrue(cluster.blacklistTextHit?.contains("요청") == true || cluster.blacklistTextHit?.contains("운행") == true)
    }

    @Test
    fun `partial card without eta and two addresses stays non offer`() {
        val texts = listOf(
            "일반 콜",
            "1시간 25분 운행"
        )
        val resourceIds = listOf("rxmap", "map", "map_marker", "map_marker_pin_head", "map_marker_anchor")

        val cluster = AccessibilityHelper.summarizeOfferTexts(texts, resourceIds)

        assertFalse(cluster.isLikelyOffer)
        assertEquals("missing_offer_text_cluster", cluster.reason)
        assertEquals("1시간 25분 운행", cluster.tripDurationText)
        assertEquals(null, cluster.pickupEtaText)
        assertEquals(emptyList<String>(), cluster.addressCandidates)
    }

    @Test
    fun `select address candidates prefers full address strings from mixed text`() {
        val texts = listOf(
            "콜 수락",
            "7분(2.0km) 남음",
            "대한민국 서울특별시 중구 을지로 30",
            "공중전화, 서울특별시 중구 남대문로2가",
            "홈"
        )

        val candidates = AccessibilityHelper.selectAddressCandidates(texts)

        assertEquals(
            listOf(
                "공중전화, 서울특별시 중구 남대문로2가",
                "대한민국 서울특별시 중구 을지로 30"
            ),
            candidates.take(2)
        )
    }

    @Test
    fun `looksLikeAddressText accepts short but specific address lines`() {
        assertTrue(AccessibilityHelper.looksLikeAddressText("대한민국 서울특별시 중구 을지로 30"))
        assertTrue(AccessibilityHelper.looksLikeAddressText("공중전화, 서울특별시 중구 남대문로2가"))
        assertFalse(AccessibilityHelper.looksLikeAddressText("콜 수락"))
        assertFalse(AccessibilityHelper.looksLikeAddressText("7분(2.0km) 남음"))
    }
}
