package com.uber.autoaccept.utils

object UberOfferGate {
    const val UBER_DRIVER_PACKAGE = "com.ubercab.driver"

    private val WINDOW_STATE_CLASSES = setOf(
        "com.ubercab.dispatch.DispatchView",
        "com.ubercab.carbon.core.dispatch.BasicDispatchView",
        "com.uber.upfront_driver_assignment_offer_card.UpfrontOfferViewV2View"
    )

    val CONFIRMATION_VIEW_IDS = setOf(
        "ub__upfront_offer_view_v2",
        "pulse_view"
    )

    val SUPPLEMENTAL_VIEW_IDS = setOf(
        "dispatch_view",
        "dispatch_container",
        "offer_container",
        "upfront_offer_view_v2_workflow",
        "ub__upfront_offer_map_container",
        "secondary_touch_area",
        "ub_pulse_view",
        "upfront_offer_configurable_details_accept_button",
        "uda_details_accept_button",
        "uda_details_pickup_address_text_view",
        "uda_details_dropoff_address_text_view"
    )

    private val ALL_MARKER_VIEW_IDS = CONFIRMATION_VIEW_IDS + SUPPLEMENTAL_VIEW_IDS

    fun isUberPackage(packageName: CharSequence?): Boolean {
        return packageName?.toString() == UBER_DRIVER_PACKAGE
    }

    fun isOfferWindowStateClass(className: CharSequence?): Boolean {
        val value = className?.toString() ?: return false
        return value in WINDOW_STATE_CLASSES
    }

    fun hasOfferMarker(viewIds: Iterable<String>): Boolean {
        return viewIds.any { it in ALL_MARKER_VIEW_IDS }
    }

    fun confirmationMarkers(viewIds: Iterable<String>): Set<String> {
        return viewIds.filterTo(linkedSetOf()) { it in CONFIRMATION_VIEW_IDS }
    }

    fun allMarkerViewIds(): Set<String> = ALL_MARKER_VIEW_IDS
}
