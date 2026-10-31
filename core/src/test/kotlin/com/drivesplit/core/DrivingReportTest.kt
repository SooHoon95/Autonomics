package com.drivesplit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingReportTest {

    private val profile = VehicleProfile(efficiencyKmPerLiter = 12.0)

    @Test
    fun reportsActualEfficiencyAndScorePerSegment() {
        val segments = listOf(
            segment(km = 30.0, liters = 2.0, drivingSec = 1800, idleSec = 0),   // 15km/L, 125%
            segment(km = 10.0, liters = 1.25, drivingSec = 1200, idleSec = 600), // 8km/L, 67%
        )

        val report = DrivingReport.of(segments, profile, pricePerLiter = 1_600.0)

        assertEquals(15.0, report.segments[0].kmPerLiter!!, 1e-9)
        assertEquals(125, report.segments[0].scorePercent)
        assertEquals(8.0, report.segments[1].kmPerLiter!!, 1e-9)
        assertEquals(67, report.segments[1].scorePercent)
        assertEquals(30.0, report.segments[1].avgMovingSpeedKmh, 1e-9)
        assertEquals(1.0 / 3, report.segments[1].idleRatio, 1e-9)

        assertEquals(40.0 / 3.25, report.kmPerLiter!!, 1e-9)
        assertEquals(5_200L, report.costWon)
        assertTrue(report.isMeasured)
    }

    @Test
    fun skipsEfficiencyForSegmentsThatBurnedAlmostNothing() {
        val idleOnly = segment(km = 0.0, liters = 0.01, drivingSec = 0, idleSec = 60)

        val report = DrivingReport.of(listOf(idleOnly), profile, pricePerLiter = 1_600.0)

        assertNull(report.segments.single().kmPerLiter)
        assertNull(report.segments.single().scorePercent)
    }

    private fun segment(km: Double, liters: Double, drivingSec: Long, idleSec: Long) =
        Segment(setOf("me"), km, drivingSec, idleSec, fuelFromRateLiters = liters, fuelFromLevelLiters = null)
}
