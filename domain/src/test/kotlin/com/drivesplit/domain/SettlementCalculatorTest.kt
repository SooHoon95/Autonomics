package com.drivesplit.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SettlementCalculatorTest {

    private val passengers = listOf(
        Passenger("a", "운전자", isDriver = true),
        Passenger("b", "민수"),
        Passenger("c", "지연"),
        Passenger("d", "하준"),
    )
    private val profile = VehicleProfile(efficiencyKmPerUnit = 10.0)
    private val options = SettlementOptions(pricePerUnit = 1000.0)

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

        // 각자 1원 단위로 반올림(18,666.7→18,667, 6,666.7→6,667). 운전자는 분담에서 빠져 0원.
        assertEquals(mapOf("a" to 0L, "b" to 18_667L, "c" to 18_667L, "d" to 6_667L), result.shares)
        assertEquals(44_001L, result.totalWon)
    }

    @Test
    fun ridersOnSameSegmentsAlwaysPayTheSame() {
        // 실제 쓴 기름값 75원을 3명이 탔으면 운전자 포함 각자 25원, 합계도 75원.
        val three = listOf(Passenger("a", "운전자", isDriver = true), Passenger("b", "민수"), Passenger("c", "지연"))
        val ride = listOf(segment(setOf("a", "b", "c"), 0.75, rate = 0.075))

        val result = SettlementCalculator.settle(three, ride, profile, options)

        assertEquals(mapOf("a" to 25L, "b" to 25L, "c" to 25L), result.shares)
        assertEquals(75L, result.totalWon)
    }

    @Test
    fun ridersRiseTogetherAsTheTripGoesOn() {
        // 주행 중 금액이 늘어날 때 같이 내는 사람(운전자 포함) 금액이 한 명씩 번갈아 오르지 않는다.
        for (won in 40..200 step 7) {
            val ride = listOf(segment(setOf("a", "b", "c", "d"), 1.0, rate = won / 1000.0))
            val shares = SettlementCalculator.settle(passengers, ride, profile, options).shares

            assertEquals("at ${won}원", 1, shares.values.toSet().size)
        }
    }

    @Test
    fun settledShareStaysFixedWhileOthersKeepRounding() {
        // 중간에 내린 c는 3,000원으로 확정됐다. 남은 두 사람은 각자 3,333.3→3,333원.
        val three = listOf(Passenger("a", "운전자", isDriver = true), Passenger("b", "민수"), Passenger("c", "지연"))
        val ride = listOf(segment(setOf("a", "b", "c"), 100.0))
        val result = SettlementCalculator.settle(three, ride, profile, options, settledShares = mapOf("c" to 3_000L))

        assertEquals(3_000L, result.shares["c"])
        assertEquals(mapOf("a" to 3_333L, "b" to 3_333L, "c" to 3_000L), result.shares)
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
        assertEquals(8.0, result.kmPerUnit!!, 1e-9)
    }

    @Test
    fun fuelRateIsPreferredOverFuelLevel() {
        val both = segment(setOf("a"), 5.0, rate = 0.4, level = 0.5)

        assertEquals(SegmentEnergy(0.4, EnergySource.RATE), EnergyModel.energyFor(both, profile))
    }

    @Test
    fun fuelLevelIsUsedOnlyForLongSegments() {
        val long = segment(setOf("a"), 50.0, level = 4.0)
        val short = segment(setOf("a"), 10.0, level = 0.1)

        assertEquals(SegmentEnergy(4.0, EnergySource.LEVEL), EnergyModel.energyFor(long, profile))
        assertEquals(SegmentEnergy(1.0, EnergySource.ESTIMATED), EnergyModel.energyFor(short, profile))
    }

    @Test
    fun batteryLevelIsUsedEvenForShortElectricSegments() {
        val ev = VehicleProfile.of(Powertrain.ELECTRIC, efficiencyKmPerUnit = 5.0)
        val short = segment(setOf("a"), 3.0, level = 0.6)

        assertEquals(SegmentEnergy(0.6, EnergySource.LEVEL), EnergyModel.energyFor(short, ev))
    }

    @Test
    fun electricTripIsPricedPerKwh() {
        val ev = VehicleProfile.of(Powertrain.ELECTRIC, efficiencyKmPerUnit = 5.0)
        val segments = listOf(segment(setOf("a", "b"), 50.0, level = 10.0))

        val result = SettlementCalculator.settle(
            listOf(Passenger("a", "A", isDriver = true), Passenger("b", "B")),
            segments, ev, SettlementOptions(pricePerUnit = 350.0),
        )

        assertEquals(3_500L, result.totalWon)
        assertEquals(mapOf("a" to 1_750L, "b" to 1_750L), result.shares)
    }

    @Test
    fun recordsSavedBeforeElectricSupportStillDecode() {
        val old = """{"efficiencyKmPerLiter":12.0,"idleLitersPerHour":0.8}"""

        val profile = kotlinx.serialization.json.Json.decodeFromString(VehicleProfile.serializer(), old)

        assertEquals(VehicleProfile(efficiencyKmPerUnit = 12.0), profile)
    }

    @Test
    fun idleTimeIsAddedToEstimatedFuel() {
        val idle = Segment(setOf("a"), 0.0, drivingSeconds = 0, idleSeconds = 3600, energyFromRate = null, energyFromLevel = null)

        assertEquals(0.8, EnergyModel.energyFor(idle, profile).amount, 1e-9)
    }

    private fun segment(occupants: Set<String>, km: Double, rate: Double? = null, level: Double? = null) =
        Segment(occupants, km, drivingSeconds = 0, idleSeconds = 0, energyFromRate = rate, energyFromLevel = level)
}
