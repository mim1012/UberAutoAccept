package com.uber.autoaccept.utils

import com.uber.autoaccept.model.OfferSnapshot
import com.uber.autoaccept.model.SnapshotNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferSnapshotBuilderTest {

    private fun snapshot(
        sampleIndex: Int,
        strongMarkers: Set<String> = emptySet(),
        hasPickupDropoff: Boolean = false,
        hasTime: Boolean = false,
        hasAccept: Boolean = false,
        addressCount: Int = 0,
        buttonCount: Int = 0,
        reason: String = "missing_structured_offer_content"
    ): OfferSnapshot {
        return OfferSnapshot(
            capturedAtMs = 1L,
            source = "test",
            sampleIndex = sampleIndex,
            packageName = "com.ubercab.driver",
            rootClassName = "FrameLayout",
            rootCount = 1,
            nodes = List(addressCount + buttonCount + 1) { SnapshotNode() },
            orderedTexts = emptyList(),
            virtualAddressCandidates = emptyList(),
            resourceIdCounts = emptyMap(),
            strongMarkers = strongMarkers,
            addressCandidates = List(addressCount) { SnapshotNode(text = "서울특별시 중구 퇴계로 $it") },
            acceptButtonCandidates = List(buttonCount) { SnapshotNode(text = "콜 수락") },
            hasPickupDropoffContent = hasPickupDropoff,
            hasTimeContent = hasTime,
            hasAcceptContent = hasAccept,
            reason = reason,
            pickupAddress = if (addressCount > 0) "서울특별시 중구 퇴계로 1" else null,
            dropoffAddress = if (addressCount > 1) "대한민국 인천광역시 중구 공항로 272" else null,
            tripDurationText = if (hasTime) "45분 운행" else null,
            pickupEtaText = if (hasTime) "6분(1.2km) 남음" else null,
            textDigest = "digest-$sampleIndex"
        )
    }

    @Test
    fun `score prefers structured snapshot with markers and accept button`() {
        val weak = snapshot(sampleIndex = 0, hasPickupDropoff = true, addressCount = 2)
        val strong = snapshot(
            sampleIndex = 1,
            strongMarkers = setOf("primary_touch_area"),
            hasPickupDropoff = true,
            hasTime = true,
            hasAccept = true,
            addressCount = 2,
            buttonCount = 1,
            reason = "address_time_accept_visible"
        )

        assertTrue(OfferSnapshotBuilder.score(strong) > OfferSnapshotBuilder.score(weak))
        assertEquals(strong, OfferSnapshotBuilder.pickBest(listOf(weak, strong)))
    }
}
