package com.drivesplit.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareLinkTest {

    @Test
    fun payloadWithKoreanNamesRoundTripsThroughUrl() {
        val payload = SharePayload(
            distanceKm = 440.0,
            energy = 44.0,
            pricePerUnit = 1650,
            totalWon = 44_000,
            people = listOf(ShareLine("운전자", 13_000, isDriver = true), ShareLine("하준", 5_000)),
            tossId = "driver01",
            kakaoPayCode = "Ej8x1y2z",
        )

        val url = ShareLink.encode("https://example.netlify.app/s", payload)

        assertTrue(url.startsWith("https://example.netlify.app/s#"))
        assertEquals(payload, ShareLink.decode(url))
    }

    @Test
    fun dropOffPayloadCoversOnlySegmentsThatPersonRode() {
        val passengers = listOf(Passenger("a", "운전자", isDriver = true), Passenger("d", "하준"))
        val segments = listOf(
            Segment(setOf("a", "d"), 200.0, 0, 0, energyFromRate = 20.0, energyFromLevel = null),
            Segment(setOf("a"), 240.0, 0, 0, energyFromRate = 24.0, energyFromLevel = null),
        )
        val settlement = SettlementCalculator.settle(
            passengers, segments, VehicleProfile(10.0), SettlementOptions(pricePerUnit = 1000.0),
        )

        val payload = ShareLink.payloadForDropOff(settlement, passengers[1], 1000.0, tossId = "driver01")

        assertEquals(200.0, payload.distanceKm, 0.0)
        assertEquals(20.0, payload.energy, 0.0)
        assertEquals(10_000L, payload.totalWon)
        assertEquals(listOf(ShareLine("하준", 10_000)), payload.people)
    }
}
