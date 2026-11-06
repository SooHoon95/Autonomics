package com.drivesplit.domain

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
        for (sec in 0..100) recorder.onSample(VehicleSample(sec * 1_000L, 60.0, energyRatePerHour = 7.2))
        recorder.changeOccupants(setOf("a", "b"))
        for (sec in 101..150) recorder.onSample(VehicleSample(sec * 1_000L, 60.0, energyRatePerHour = 7.2))

        val segments = recorder.finish()

        assertEquals(0.2, segments[0].energyFromRate!!, 1e-9)
        assertEquals(0.1, segments[1].energyFromRate!!, 1e-9)
    }

    @Test
    fun fuelRateIsNullWhenVehicleDoesNotReportIt() {
        val recorder = TripRecorder(setOf("a"))
        feed(recorder, from = 0, to = 10, kmh = 60.0)

        assertNull(recorder.finish().single().energyFromRate)
    }

    @Test
    fun measuresFuelLevelDropPerSegment() {
        val recorder = TripRecorder(setOf("a"))
        recorder.onSample(VehicleSample(0, 60.0, energyLevel = 50.0))
        recorder.onSample(VehicleSample(1_000, 60.0, energyLevel = 48.0))
        recorder.changeOccupants(setOf("a", "b"))
        recorder.onSample(VehicleSample(2_000, 60.0, energyLevel = 47.5))

        val segments = recorder.finish()

        assertEquals(2.0, segments[0].energyFromLevel!!, 1e-9)
        assertEquals(0.5, segments[1].energyFromLevel!!, 1e-9)
    }

    @Test
    fun refuelingMidSegmentKeepsCountingConsumption() {
        val recorder = TripRecorder(setOf("a"))
        recorder.onSample(VehicleSample(0, 60.0, energyLevel = 10.0))
        recorder.onSample(VehicleSample(1_000, 60.0, energyLevel = 9.0))  // 1L 소모
        recorder.onSample(VehicleSample(2_000, 0.0, energyLevel = 50.0))  // 주유
        recorder.onSample(VehicleSample(3_000, 60.0, energyLevel = 49.5)) // 0.5L 소모

        assertEquals(1.5, recorder.finish().single().energyFromLevel!!, 1e-9)
    }

    @Test
    fun smallFuelLevelWobbleIsNotCountedTwice() {
        val recorder = TripRecorder(setOf("a"))
        listOf(40.0, 39.8, 40.0, 39.8, 39.6).forEachIndexed { i, level ->
            recorder.onSample(VehicleSample(i * 1_000L, 60.0, energyLevel = level))
        }

        assertEquals(0.4, recorder.finish().single().energyFromLevel!!, 1e-9)
    }

    @Test
    fun odometerIsPreferredOverSpeedForDistance() {
        val recorder = TripRecorder(setOf("a"))
        // 속도로 적분하면 1초에 0.0167km지만, 계기판은 1.5km 늘었다.
        recorder.onSample(VehicleSample(0, 60.0, odometerKm = 1_000.0))
        recorder.onSample(VehicleSample(1_000, 60.0, odometerKm = 1_001.5))

        assertEquals(1.5, recorder.finish().single().distanceKm, 1e-9)
    }

    @Test
    fun speedIsUsedWhileOdometerStaysStill() {
        val recorder = TripRecorder(setOf("a"))
        // 주행거리계 값은 오지만 늘지 않는다(에뮬레이터). 60km/h로 60초 = 1km.
        for (sec in 0..60) recorder.onSample(VehicleSample(sec * 1_000L, 60.0, odometerKm = 500.0))

        assertEquals(1.0, recorder.finish().single().distanceKm, 1e-9)
    }

    @Test
    fun regenerativeBrakingIsNotCountedAsConsumption() {
        val recorder = TripRecorder(setOf("a"))
        // 전기차 배터리(kWh): 내리막에서 회생제동으로 잠깐 올랐다가 다시 쓴다.
        listOf(60.0, 59.5, 59.8, 59.3).forEachIndexed { i, kwh ->
            recorder.onSample(VehicleSample(i * 1_000L, 60.0, energyLevel = kwh))
        }

        assertEquals(0.7, recorder.finish().single().energyFromLevel!!, 1e-9)
    }

    @Test
    fun autoSplitStartsNewSegmentAfterLongStop() {
        val recorder = TripRecorder(setOf("a"), AutoSplit(minStopSeconds = 30))
        feed(recorder, from = 0, to = 60, kmh = 60.0)
        feed(recorder, from = 61, to = 100, kmh = 0.0)   // 40초 정차
        feed(recorder, from = 101, to = 160, kmh = 60.0)

        val segments = recorder.finish()

        assertEquals(2, segments.size)
        assertEquals(39L, segments[0].idleSeconds)
    }

    @Test
    fun autoSplitIgnoresShortStops() {
        val recorder = TripRecorder(setOf("a"), AutoSplit(minStopSeconds = 30))
        feed(recorder, from = 0, to = 60, kmh = 60.0)
        feed(recorder, from = 61, to = 70, kmh = 0.0)    // 10초 신호 대기
        feed(recorder, from = 71, to = 130, kmh = 60.0)

        assertEquals(1, recorder.finish().size)
    }

    @Test
    fun autoSplitStartsNewSegmentWhenRoadTypeChangesForLong() {
        val recorder = TripRecorder(setOf("a"), AutoSplit(roadTypeHoldSeconds = 120))
        feed(recorder, from = 0, to = 300, kmh = 40.0)    // 시내
        feed(recorder, from = 301, to = 700, kmh = 100.0) // 고속도로

        assertEquals(2, recorder.finish().size)
    }

    @Test
    fun briefSpeedChangeDoesNotSplit() {
        val recorder = TripRecorder(setOf("a"), AutoSplit(roadTypeHoldSeconds = 120))
        feed(recorder, from = 0, to = 300, kmh = 40.0)
        feed(recorder, from = 301, to = 360, kmh = 90.0)  // 1분 잠깐 빠르게
        feed(recorder, from = 361, to = 600, kmh = 40.0)

        assertEquals(1, recorder.finish().size)
    }

    @Test
    fun autoSplitStartsNewSegmentWhenRegionChanges() {
        val recorder = TripRecorder(setOf("a"), AutoSplit())
        for (sec in 0..60) recorder.onSample(VehicleSample(sec * 1_000L, 60.0, region = "강남구"))
        for (sec in 61..120) recorder.onSample(VehicleSample(sec * 1_000L, 60.0, region = "서초구"))

        assertEquals(2, recorder.finish().size)
    }

    @Test
    fun splitModeIgnoresRegionChanges() {
        val recorder = TripRecorder(setOf("a"))
        for (sec in 0..60) recorder.onSample(VehicleSample(sec * 1_000L, 60.0, region = "강남구"))
        for (sec in 61..120) recorder.onSample(VehicleSample(sec * 1_000L, 60.0, region = "서초구"))

        assertEquals(1, recorder.finish().size)
    }

    @Test
    fun restoredRecorderContinuesWhereItStopped() {
        val recorder = TripRecorder(setOf("a", "b"))
        feed(recorder, from = 0, to = 60, kmh = 60.0)          // 1km
        recorder.changeOccupants(setOf("a"))
        feed(recorder, from = 61, to = 121, kmh = 60.0)        // 1km

        val restored = TripRecorder.restore(recorder.saveState())
        feed(restored, from = 200, to = 260, kmh = 60.0)       // 재시작 후 1km 더

        val segments = restored.finish()
        assertEquals(2, segments.size)
        assertEquals(setOf("a"), segments[1].occupantIds)
        // 하차 직후 1초(60→61초)도 두 번째 구간에 들어가서 1km를 조금 넘는다.
        assertEquals(2.0, segments[1].distanceKm, 0.02)
    }

    @Test
    fun distanceDrivenWhileAppWasOffIsAddedOnResume() {
        val recorder = TripRecorder(setOf("a"))
        recorder.onSample(VehicleSample(0, 0.0, odometerKm = 1_000.0))
        recorder.onSample(VehicleSample(1_000, 0.0, odometerKm = 1_000.0))

        // 시동을 끈 사이 다른 앱 없이 3km를 더 갔다. 재부팅이라 시각은 처음부터 다시 센다.
        val restored = TripRecorder.restore(recorder.saveState())
        restored.onSample(VehicleSample(0, 0.0, odometerKm = 1_003.0))

        assertEquals(3.0, restored.finish().single().distanceKm, 1e-9)
    }

    private fun feed(recorder: TripRecorder, from: Int, to: Int, kmh: Double) {
        for (sec in from..to) recorder.onSample(VehicleSample(sec * 1_000L, kmh))
    }

    @Test
    fun pathKeepsOnlyPointsThatMovedFarEnough() {
        val recorder = TripRecorder(setOf("a"))
        // 위도 0.0001도 ≈ 11m. 30m 이상 움직였을 때만 남는다.
        (0..10).forEach { i ->
            recorder.onSample(VehicleSample(i * 1000L, 40.0, location = GeoPoint(37.0 + i * 0.0001, 127.0)))
        }

        val path = recorder.finish().single().path

        assertEquals(listOf(37.0, 37.0003, 37.0006, 37.0009), path.map { Math.round(it.latitude * 10_000) / 10_000.0 })
    }

    @Test
    fun nextSegmentPathStartsWhereThePreviousEnded() {
        val recorder = TripRecorder(setOf("a", "b"))
        recorder.onSample(VehicleSample(0, 40.0, location = GeoPoint(37.0, 127.0)))
        recorder.onSample(VehicleSample(1000, 40.0, location = GeoPoint(37.001, 127.0)))
        recorder.changeOccupants(setOf("a"))
        recorder.onSample(VehicleSample(2000, 40.0, location = GeoPoint(37.002, 127.0)))

        val (first, second) = recorder.finish()

        assertEquals(first.path.last(), second.path.first())
        assertEquals(GeoPoint(37.002, 127.0), second.path.last())
    }
}
