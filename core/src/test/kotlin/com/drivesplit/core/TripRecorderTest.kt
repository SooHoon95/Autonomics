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
    fun measuresFuelDropPerSegment() {
        val recorder = TripRecorder(setOf("a"))
        recorder.onSample(VehicleSample(0, 60.0, fuelLevelLiters = 50.0))
        recorder.onSample(VehicleSample(1_000, 60.0, fuelLevelLiters = 48.0))
        recorder.changeOccupants(setOf("a", "b"))
        recorder.onSample(VehicleSample(2_000, 60.0, fuelLevelLiters = 47.5))

        val segments = recorder.finish()

        assertEquals(2.0, segments[0].measuredFuelLiters!!, 1e-9)
        assertEquals(0.5, segments[1].measuredFuelLiters!!, 1e-9)
    }

    @Test
    fun discardsMeasuredFuelWhenRefueledMidSegment() {
        val recorder = TripRecorder(setOf("a"))
        recorder.onSample(VehicleSample(0, 60.0, fuelLevelLiters = 10.0))
        recorder.onSample(VehicleSample(1_000, 0.0, fuelLevelLiters = 50.0))

        assertNull(recorder.finish().single().measuredFuelLiters)
    }

    private fun feed(recorder: TripRecorder, from: Int, to: Int, kmh: Double) {
        for (sec in from..to) recorder.onSample(VehicleSample(sec * 1_000L, kmh))
    }
}
