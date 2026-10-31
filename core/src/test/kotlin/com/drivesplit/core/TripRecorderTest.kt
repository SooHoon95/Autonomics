package com.drivesplit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripRecorderTest {

    @Test
    fun integratesSpeedIntoDistanceAndCountsStopsAsIdle() {
        val recorder = TripRecorder(setOf("a", "b"))
        feed(recorder, from = 0, to = 100, kmh = 72.0)   // 100초 × 72km/h = 2km
        feed(recorder, from = 101, to = 160, kmh = 0.0)  // 첫 1초는 감속(0.01km), 이후 59초 공회전
        recorder.changeOccupants(setOf("a"))
        feed(recorder, from = 161, to = 260, kmh = 72.0)

        val segments = recorder.finish()

        assertEquals(2, segments.size)
        assertEquals(setOf("a", "b"), segments[0].occupantIds)
        assertEquals(2.01, segments[0].distanceKm, 1e-9)
        assertEquals(59L, segments[0].idleSeconds)
        assertEquals(setOf("a"), segments[1].occupantIds)
        assertEquals(1.99, segments[1].distanceKm, 1e-9) // 출발 1초 가속(0.01km) + 99초 × 72km/h
    }

    @Test
    fun ignoresDistanceAcrossLongSampleGaps() {
        val recorder = TripRecorder(setOf("a"))
        recorder.onSample(VehicleSample(0, 100.0))
        recorder.onSample(VehicleSample(60_000, 100.0))

        assertEquals(0.0, recorder.snapshot().single().distanceKm, 1e-9)
    }

    @Test
    fun integratesFuelRatePerSegment() {
        val recorder = TripRecorder(setOf("a"))
        // 7.2L/h로 100초 = 0.2L
        for (sec in 0..100) recorder.onSample(VehicleSample(sec * 1_000L, 60.0, fuelRateLitersPerHour = 7.2))
        recorder.changeOccupants(setOf("a", "b"))
        for (sec in 101..150) recorder.onSample(VehicleSample(sec * 1_000L, 60.0, fuelRateLitersPerHour = 7.2))

        val segments = recorder.finish()

        assertEquals(0.2, segments[0].fuelFromRateLiters!!, 1e-9)
        assertEquals(0.1, segments[1].fuelFromRateLiters!!, 1e-9)
    }

    @Test
    fun fuelRateIsNullWhenVehicleDoesNotReportIt() {
        val recorder = TripRecorder(setOf("a"))
        feed(recorder, from = 0, to = 10, kmh = 60.0)

        assertNull(recorder.finish().single().fuelFromRateLiters)
    }

    @Test
    fun measuresFuelLevelDropPerSegment() {
        val recorder = TripRecorder(setOf("a"))
        recorder.onSample(VehicleSample(0, 60.0, fuelLevelLiters = 50.0))
        recorder.onSample(VehicleSample(1_000, 60.0, fuelLevelLiters = 48.0))
        recorder.changeOccupants(setOf("a", "b"))
        recorder.onSample(VehicleSample(2_000, 60.0, fuelLevelLiters = 47.5))

        val segments = recorder.finish()

        assertEquals(2.0, segments[0].fuelFromLevelLiters!!, 1e-9)
        assertEquals(0.5, segments[1].fuelFromLevelLiters!!, 1e-9)
    }

    @Test
    fun refuelingMidSegmentKeepsCountingConsumption() {
        val recorder = TripRecorder(setOf("a"))
        recorder.onSample(VehicleSample(0, 60.0, fuelLevelLiters = 10.0))
        recorder.onSample(VehicleSample(1_000, 60.0, fuelLevelLiters = 9.0))  // 1L 소모
        recorder.onSample(VehicleSample(2_000, 0.0, fuelLevelLiters = 50.0))  // 주유
        recorder.onSample(VehicleSample(3_000, 60.0, fuelLevelLiters = 49.5)) // 0.5L 소모

        assertEquals(1.5, recorder.finish().single().fuelFromLevelLiters!!, 1e-9)
    }

    @Test
    fun smallFuelLevelWobbleIsNotCountedTwice() {
        val recorder = TripRecorder(setOf("a"))
        listOf(40.0, 39.8, 40.0, 39.8, 39.6).forEachIndexed { i, level ->
            recorder.onSample(VehicleSample(i * 1_000L, 60.0, fuelLevelLiters = level))
        }

        assertEquals(0.4, recorder.finish().single().fuelFromLevelLiters!!, 1e-9)
    }

    @Test
    fun autoSplitStartsNewSegmentAfterLongStop() {
        val recorder = TripRecorder(setOf("a"), AutoSplit(maxSegmentKm = 100.0, minStopSeconds = 30))
        feed(recorder, from = 0, to = 60, kmh = 60.0)
        feed(recorder, from = 61, to = 100, kmh = 0.0)   // 40초 정차
        feed(recorder, from = 101, to = 160, kmh = 60.0)

        val segments = recorder.finish()

        assertEquals(2, segments.size)
        assertEquals(39L, segments[0].idleSeconds)
    }

    @Test
    fun autoSplitIgnoresShortStops() {
        val recorder = TripRecorder(setOf("a"), AutoSplit(maxSegmentKm = 100.0, minStopSeconds = 30))
        feed(recorder, from = 0, to = 60, kmh = 60.0)
        feed(recorder, from = 61, to = 70, kmh = 0.0)    // 10초 신호 대기
        feed(recorder, from = 71, to = 130, kmh = 60.0)

        assertEquals(1, recorder.finish().size)
    }

    @Test
    fun autoSplitCapsSegmentDistance() {
        val recorder = TripRecorder(setOf("a"), AutoSplit(maxSegmentKm = 1.0, minStopSeconds = 30))
        feed(recorder, from = 0, to = 180, kmh = 72.0)  // 3.6km

        val segments = recorder.finish()

        assertEquals(4, segments.size)
        assertEquals(1.0, segments[0].distanceKm, 1e-9)
    }

    private fun feed(recorder: TripRecorder, from: Int, to: Int, kmh: Double) {
        for (sec in from..to) recorder.onSample(VehicleSample(sec * 1_000L, kmh))
    }
}
