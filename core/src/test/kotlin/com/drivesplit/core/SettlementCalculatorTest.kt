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
    fun settledShareStaysFixedWhileOthersKeepRounding() {
        // 3명이 10원 단위로 나누면 3,340/3,330/3,330. 중간에 내린 c는 3,330원으로 확정됐다.
        val three = listOf(Passenger("a", "운전자", isDriver = true), Passenger("b", "민수"), Passenger("c", "지연"))
        val ride = listOf(segment(setOf("a", "b", "c"), 100.0))
        val result = SettlementCalculator.settle(three, ride, profile, options, settledShares = mapOf("c" to 3_330L))

        assertEquals(3_330L, result.shares["c"])
        assertEquals(10_000L, result.totalWon)
        assertEquals(6_670L, result.shares.getValue("a") + result.shares.getValue("b"))
    }

    @Test
    fun droppedOffPassengerDoesNotPayLaterSegments() {
        val dropped = SettlementCalculator.settle(passengers, segments.take(1), profile, options)
        val settled = mapOf("d" to dropped.shares.getValue("d"))
        val result = SettlementCalculator.settle(passengers, segments, profile, options, settledShares = settled)

        assertEquals(5_000L, result.shares["d"])
        assertEquals(44_000L, result.totalWon)
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
    fun settlementChargesActualFuelNotRatedEfficiency() {
        // 공인 연비로는 200km = 20L지만 실제로는 25L를 태웠다 → 실제 25L로 정산
        val actual = listOf(segment(setOf("a", "b"), 200.0, rate = 25.0))
        val result = SettlementCalculator.settle(passengers.take(2), actual, profile, options)

        assertEquals(25_000L, result.totalWon)
        assertEquals(true, result.isMeasured)
        assertEquals(8.0, result.kmPerLiter!!, 1e-9)
    }

    @Test
    fun fuelRateIsPreferredOverFuelLevel() {
        val both = segment(setOf("a"), 5.0, rate = 0.4, level = 0.5)

        assertEquals(SegmentFuel(0.4, FuelSource.FUEL_RATE), FuelModel.litersFor(both, profile))
    }

    @Test
    fun fuelLevelIsUsedOnlyForLongSegments() {
        val long = segment(setOf("a"), 50.0, level = 4.0)
        val short = segment(setOf("a"), 10.0, level = 0.1)

        assertEquals(SegmentFuel(4.0, FuelSource.FUEL_LEVEL), FuelModel.litersFor(long, profile))
        assertEquals(SegmentFuel(1.0, FuelSource.ESTIMATED), FuelModel.litersFor(short, profile))
    }

    @Test
    fun idleTimeIsAddedToEstimatedFuel() {
        val idle = Segment(setOf("a"), 0.0, drivingSeconds = 0, idleSeconds = 3600, fuelFromRateLiters = null, fuelFromLevelLiters = null)

        assertEquals(0.8, FuelModel.litersFor(idle, profile).liters, 1e-9)
    }

    private fun segment(occupants: Set<String>, km: Double, rate: Double? = null, level: Double? = null) =
        Segment(occupants, km, drivingSeconds = 0, idleSeconds = 0, fuelFromRateLiters = rate, fuelFromLevelLiters = level)
}
