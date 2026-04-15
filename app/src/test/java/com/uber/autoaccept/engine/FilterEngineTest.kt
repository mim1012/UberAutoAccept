package com.uber.autoaccept.engine

import com.uber.autoaccept.model.FilterMode
import com.uber.autoaccept.model.FilterResult
import com.uber.autoaccept.model.FilterSettings
import com.uber.autoaccept.model.OfferTraceContext
import com.uber.autoaccept.model.ParseConfidence
import com.uber.autoaccept.model.UberOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterEngineTest {

    @Test
    fun `accepted result exposes matched condition and keyword hits`() {
        val engine = FilterEngine(
            FilterSettings(
                mode = FilterMode.ENABLED,
                maxCustomerDistance = 5.0,
                pickupKeywords = listOf("특별시"),
                airportKeywords = listOf("인천공항"),
                enabledConditions = setOf(4)
            )
        )

        val result = engine.isEligible(
            offer(
                pickup = "서울특별시 강남구",
                dropoff = "인천공항 제1터미널",
                customerDistance = 3.2
            )
        )

        assertTrue(result is FilterResult.Accepted)
        val accepted = result as FilterResult.Accepted
        assertEquals(listOf(4), accepted.matchedConditions)
        assertEquals(listOf("인천공항"), accepted.keywordHits["dropoff_airport"])
    }

    @Test
    fun `rejected result exposes reject code and matched conditions`() {
        val engine = FilterEngine(
            FilterSettings(
                mode = FilterMode.ENABLED,
                maxCustomerDistance = 2.0,
                pickupKeywords = listOf("특별시"),
                airportKeywords = listOf("인천공항"),
                enabledConditions = setOf(4)
            )
        )

        val result = engine.isEligible(
            offer(
                pickup = "서울특별시 강남구",
                dropoff = "인천공항 제2터미널",
                customerDistance = 3.5
            )
        )

        assertTrue(result is FilterResult.Rejected)
        val rejected = result as FilterResult.Rejected
        assertEquals("CUSTOMER_DISTANCE_EXCEEDED", rejected.rejectCode)
        assertEquals(listOf(4), rejected.matchedConditions)
    }

    private fun offer(
        pickup: String,
        dropoff: String,
        customerDistance: Double
    ) = UberOffer(
        offerUuid = "trace-1",
        traceContext = OfferTraceContext(traceId = "trace-1", detectionSource = "test", detectionStage = "unit"),
        pickupLocation = pickup,
        dropoffLocation = dropoff,
        customerDistance = customerDistance,
        tripDistance = 10.0,
        estimatedFare = 0,
        estimatedTime = 0,
        acceptButtonBounds = null,
        acceptButtonNode = null,
        parseConfidence = ParseConfidence.HIGH
    )
}
