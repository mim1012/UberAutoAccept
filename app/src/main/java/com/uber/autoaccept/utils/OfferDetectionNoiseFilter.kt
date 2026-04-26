package com.uber.autoaccept.utils

object OfferDetectionNoiseFilter {
    private val NOISY_CLASS_TOKENS = listOf(
        "RecyclerView",
        "ScrollView",
        "ViewPager",
        "Toolbar",
        "NavigationBar",
        "EditText",
        "Switch",
        "SeekBar"
    )

    private val NON_OFFER_TEXT_TOKENS = listOf(
        "\uC6B4\uD589 \uB9AC\uC2A4\uD2B8",
        "\uC9C0\uAE08\uC740 \uC694\uCCAD\uC774 \uC5C6\uC2B5\uB2C8\uB2E4",
        "\uB354 \uB9CE\uC740 \uC694\uCCAD\uC774 \uB4E4\uC5B4\uC624\uBA74",
        "\uACC4\uC815",
        "\uC124\uC815"
    )

    fun shouldStartProbe(
        className: String?,
        resourceIds: Iterable<String>,
        texts: Iterable<String>
    ): Boolean {
        val ids = resourceIds.toList()
        if (UberOfferGate.isOfferWindowStateClass(className)) return true
        if (UberOfferGate.hasOfferMarker(ids)) return true

        val textList = texts
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(80)

        if (textList.any { text -> NON_OFFER_TEXT_TOKENS.any { text.contains(it, ignoreCase = true) } }) {
            return false
        }

        val cluster = AccessibilityHelper.summarizeOfferTexts(textList, ids)
        if (cluster.isLikelyOffer) return true

        val noisyClass = className?.let { name ->
            NOISY_CLASS_TOKENS.any { token -> name.contains(token, ignoreCase = true) }
        } ?: false

        return !noisyClass && (
            cluster.acceptText != null ||
                cluster.addressCandidates.size >= 2 ||
                (cluster.hasTripTiming() && cluster.regionText != null)
            )
    }

    private fun OfferTextCluster.hasTripTiming(): Boolean {
        return tripDurationText != null || pickupEtaText != null
    }
}
