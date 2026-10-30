package com.drivesplit.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SettlementCalculatorTest {

    private val passengers = listOf(
        Passenger("a", "운전자", isDriver = true),
        Passenger("b", "민수"),
        Passenger("c", "지연"),
        Passenger("d", "하준"),
    )
    private val profile = VehicleProfile(efficiencyKmPerLiter = 10.0)
    private val options = SettlementOptions(pricePerLiter = 1000.0)

    // 서울→대전 200km 4명 = 20,000원, 대전→부산 240km 3명(하준 하차) = 24,000원
    private val segments = listOf(
        segment(setOf("a", "b", "c", "d"), 200.0),
        segment(setOf("a", "b", "c"), 240.0),
    )

    @Test
    fun passengerWhoLeftEarlyPaysOnlyRiddenSegments() {
        val result = SettlementCalculator.settle(passengers, segments, profile, options)

        assertEquals(44_000L, result.totalWon)
        assertEquals(mapOf("a" to 13_000L, "b" to 13_000L, "c" to 13_000L, "d" to 5_000L), result.shares)
    }

    @Test
    fun driverExcludedSplitsAmongRidersAndKeepsTotal() {
        val result = SettlementCalculator.settle(passengers, segments, profile, options.copy(driverPays = false))

        assertEquals(44_000L, result.totalWon)
        assertEquals(mapOf("a" to 0L, "b" to 18_670L, "c" to 18_670L, "d" to 6_660L), result.shares)
    }

    @Test
    fun driverPaysForSegmentDrivenAlone() {
        val solo = listOf(segment(setOf("a"), 100.0))
        val result = SettlementCalculator.settle(passengers, solo, profile, options.copy(driverPays = false))

        assertEquals(10_000L, result.shares["a"])
    }

    @Test
    fun extraCostIsSplitOnlyAmongSpecifiedPeople() {
        val extras = listOf(ExtraCost("통행료", 9_000, sharedBy = setOf("a", "b", "c")))
        val result = SettlementCalculator.settle(passengers, segments, profile, options, extras)

        assertEquals(53_000L, result.totalWon)
        assertEquals(16_000L, result.shares["b"])
        assertEquals(5_000L, result.shares["d"])
    }

    @Test
    fun measuredFuelIsUsedOnlyForLongSegments() {
        val long = segment(setOf("a"), 50.0, measured = 4.0)
        val short = segment(setOf("a"), 10.0, measured = 0.1)

        assertEquals(SegmentFuel(4.0, FuelSource.MEASURED), FuelModel.litersFor(long, profile))
        assertEquals(SegmentFuel(1.0, FuelSource.ESTIMATED), FuelModel.litersFor(short, profile))
    }

    @Test
    fun idleTimeIsAddedToEstimatedFuel() {
        val idle = Segment(setOf("a"), distanceKm = 0.0, drivingSeconds = 0, idleSeconds = 3600, measuredFuelLiters = null)

        assertEquals(0.8, FuelModel.litersFor(idle, profile).liters, 1e-9)
    }

    private fun segment(occupants: Set<String>, km: Double, measured: Double? = null) =
        Segment(occupants, km, drivingSeconds = 0, idleSeconds = 0, measuredFuelLiters = measured)
}
