package com.drivesplit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareLinkTest {

    @Test
    fun payloadWithKoreanNamesRoundTripsThroughUrl() {
        val payload = SharePayload(
            distanceKm = 440.0,
            liters = 44.0,
            pricePerLiter = 1650,
            totalWon = 44_000,
            people = listOf(ShareLine("운전자", 13_000, isDriver = true), ShareLine("하준", 5_000)),
            tossId = "driver01",
        )

        val url = ShareLink.encode("https://example.netlify.app/s", payload)

        assertTrue(url.startsWith("https://example.netlify.app/s#"))
        assertEquals(payload, ShareLink.decode(url))
    }
}
