package com.uber.autoaccept.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UberOfferGateTest {

    @Test
    fun `matches known Uber offer overlay classes`() {
        assertTrue(UberOfferGate.isOfferWindowStateClass("android.widget.FrameLayout"))
        assertTrue(UberOfferGate.isOfferWindowStateClass("android.widget.LinearLayout"))
        assertTrue(UberOfferGate.isOfferWindowStateClass("com.ubercab.dispatch.DispatchView"))
        assertTrue(UberOfferGate.isOfferWindowStateClass("com.ubercab.carbon.core.dispatch.BasicDispatchView"))
        assertTrue(UberOfferGate.isOfferWindowStateClass("com.uber.upfront_driver_assignment_offer_card.UpfrontOfferViewV2View"))
    }

    @Test
    fun `rejects unrelated window state classes`() {
        assertFalse(UberOfferGate.isOfferWindowStateClass("android.widget.TextView"))
        assertFalse(UberOfferGate.isOfferWindowStateClass("com.ubercab.driver.MainActivity"))
        assertFalse(UberOfferGate.isOfferWindowStateClass(null))
    }

    @Test
    fun `detects offer markers from confirmation or supplemental ids`() {
        assertTrue(UberOfferGate.hasOfferMarker(listOf("primary_touch_area")))
        assertTrue(UberOfferGate.hasOfferMarker(listOf("dispatch_view")))
        assertFalse(UberOfferGate.hasOfferMarker(listOf("recycler_view", "toolbar")))
    }

    @Test
    fun `returns only strong confirmation markers`() {
        val markers = UberOfferGate.confirmationMarkers(
            listOf("dispatch_view", "pulse_view", "primary_touch_area", "toolbar")
        )

        assertEquals(linkedSetOf("pulse_view", "primary_touch_area"), markers)
    }
}
