package com.drivesplit.core

/**
 * 차량 샘플을 받아 구간(Segment)을 쌓는다. 스레드 안전하지 않으므로 한 코루틴에서만 호출한다.
 */
class TripRecorder(initialOccupants: Set<String>) {

    private val closed = mutableListOf<Segment>()
    private var occupants = initialOccupants

    private var distanceKm = 0.0
    private var drivingMs = 0L
    private var idleMs = 0L
    private var fuelAtStart: Double? = null
    private var fuelLatest: Double? = null
    private var fuelValid = true

    private var last: VehicleSample? = null

    val currentOccupants: Set<String> get() = occupants
    val isStopped: Boolean get() = (last?.speedKmh ?: 0.0) < STOP_KMH

    fun onSample(sample: VehicleSample) {
        val prev = last
        if (prev != null) {
            val dtMs = sample.timestampMs - prev.timestampMs
            // 샘플이 오래 끊겼다면(터널, 앱 재시작) 그 사이 거리를 지어내지 않는다.
            if (dtMs in 1..MAX_GAP_MS) {
                val avgKmh = (prev.speedKmh + sample.speedKmh) / 2
                distanceKm += avgKmh * dtMs / 3_600_000.0
                val stopped = prev.speedKmh < STOP_KMH && sample.speedKmh < STOP_KMH
                when {
                    !stopped -> drivingMs += dtMs
                    sample.ignitionOn -> idleMs += dtMs
                }
            }
        }
        sample.fuelLevelLiters?.let { level ->
            val latest = fuelLatest
            if (latest != null && level - latest > REFUEL_JUMP_LITERS) fuelValid = false
            if (fuelAtStart == null) fuelAtStart = level
            fuelLatest = level
        }
        last = sample
    }

    fun changeOccupants(newOccupants: Set<String>) {
        if (newOccupants == occupants) return
        closeSegment()
        occupants = newOccupants
    }

    /** 진행 중인 구간까지 포함한 현재 상태. 주행 중 게이지 표시용. */
    fun snapshot(): List<Segment> = closed + currentSegment()

    fun finish(): List<Segment> {
        closeSegment()
        return closed.toList()
    }

    private fun currentSegment(): Segment {
        val start = fuelAtStart
        val end = fuelLatest
        val measured = if (fuelValid && start != null && end != null) start - end else null
        return Segment(
            occupantIds = occupants,
            distanceKm = distanceKm,
            drivingSeconds = drivingMs / 1000,
            idleSeconds = idleMs / 1000,
            measuredFuelLiters = measured,
        )
    }

    private fun closeSegment() {
        val segment = currentSegment()
        if (segment.distanceKm > 0 || segment.idleSeconds > 0) closed += segment
        distanceKm = 0.0
        drivingMs = 0
        idleMs = 0
        fuelAtStart = fuelLatest
        fuelValid = true
    }

    companion object {
        const val STOP_KMH = 1.0
        const val MAX_GAP_MS = 10_000L
        const val REFUEL_JUMP_LITERS = 1.0
    }
}
