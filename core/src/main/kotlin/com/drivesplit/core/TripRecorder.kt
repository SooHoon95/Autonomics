package com.drivesplit.core

/** 솔로 모드 자동 구간 나누기: 일정 시간 이상 정차 후 출발하거나, 일정 거리를 달리면 새 구간. */
data class AutoSplit(
    val maxSegmentKm: Double = 10.0,
    val minStopSeconds: Long = 30,
)

/**
 * 차량 샘플을 받아 구간(Segment)을 쌓는다. 스레드 안전하지 않으므로 한 코루틴에서만 호출한다.
 */
class TripRecorder(
    initialOccupants: Set<String>,
    private val autoSplit: AutoSplit? = null,
) {

    private val closed = mutableListOf<Segment>()
    private var occupants = initialOccupants

    // 진행 중인 구간의 누적값
    private var distanceKm = 0.0
    private var drivingMs = 0L
    private var idleMs = 0L
    private var rateLiters = 0.0
    private var rateSeen = false
    private var rateMissing = false
    private var levelConsumed = 0.0
    private var levelSeen = false
    private var path = mutableListOf<GeoPoint>()

    // 구간을 넘어 이어지는 상태
    private var levelLow: Double? = null
    private var stoppedSinceMs: Long? = null
    private var last: VehicleSample? = null

    val currentOccupants: Set<String> get() = occupants
    val isStopped: Boolean get() = (last?.speedKmh ?: 0.0) < STOP_KMH

    fun onSample(sample: VehicleSample) {
        splitOnDepartureIfNeeded(sample)

        val prev = last
        if (prev != null) {
            val dtMs = sample.timestampMs - prev.timestampMs
            // 샘플이 오래 끊겼다면(터널, 앱 재시작) 그 사이 거리·연료를 지어내지 않는다.
            if (dtMs in 1..MAX_GAP_MS) {
                val avgKmh = (prev.speedKmh + sample.speedKmh) / 2
                distanceKm += avgKmh * dtMs / 3_600_000.0
                val stopped = prev.speedKmh < STOP_KMH && sample.speedKmh < STOP_KMH
                when {
                    !stopped -> drivingMs += dtMs
                    sample.ignitionOn -> idleMs += dtMs
                }
                val prevRate = prev.fuelRateLitersPerHour
                val rate = sample.fuelRateLitersPerHour
                if (prevRate != null && rate != null) {
                    rateLiters += (prevRate + rate) / 2 * dtMs / 3_600_000.0
                    rateSeen = true
                } else {
                    rateMissing = true
                }
            }
        }
        sample.fuelLevelLiters?.let(::trackFuelLevel)
        sample.location?.let(::trackLocation)
        last = sample

        autoSplit?.let { if (distanceKm >= it.maxSegmentKm) closeSegment() }
    }

    fun changeOccupants(newOccupants: Set<String>) {
        if (newOccupants == occupants) return
        closeSegment()
        occupants = newOccupants
    }

    /** 진행 중인 구간까지 포함한 현재 상태. 주행 중 화면 표시용. */
    fun snapshot(): List<Segment> = closed + currentSegment()

    fun finish(): List<Segment> {
        closeSegment()
        return closed.toList()
    }

    private fun splitOnDepartureIfNeeded(sample: VehicleSample) {
        val config = autoSplit ?: return
        if (sample.speedKmh < STOP_KMH) {
            if (stoppedSinceMs == null) stoppedSinceMs = sample.timestampMs
            return
        }
        val since = stoppedSinceMs
        stoppedSinceMs = null
        // 출발 직전까지의 공회전은 이전 구간에 남긴다. 아직 움직인 적 없는 구간은 나누지 않는다.
        if (since != null && sample.timestampMs - since >= config.minStopSeconds * 1000 && distanceKm > 0) {
            closeSegment()
        }
    }

    /** 가장 낮았던 연료량 아래로 내려간 만큼만 소모로 센다. 출렁임과 주유는 소모로 치지 않는다. */
    private fun trackFuelLevel(level: Double) {
        val low = levelLow
        when {
            low == null -> levelLow = level
            level > low + REFUEL_JUMP_LITERS -> levelLow = level
            level < low -> {
                levelConsumed += low - level
                levelLow = level
            }
        }
        levelSeen = true
    }

    /** 점이 너무 촘촘하면 메모리만 먹으므로 일정 거리 이상 움직였을 때만 남긴다. */
    private fun trackLocation(point: GeoPoint) {
        val prev = path.lastOrNull()
        if (prev == null || prev.distanceMetersTo(point) >= MIN_POINT_GAP_M) path += point
    }

    private fun currentSegment() = Segment(
        occupantIds = occupants,
        distanceKm = distanceKm,
        drivingSeconds = drivingMs / 1000,
        idleSeconds = idleMs / 1000,
        fuelFromRateLiters = if (rateSeen && !rateMissing) rateLiters else null,
        fuelFromLevelLiters = if (levelSeen) levelConsumed else null,
        path = path.toList(),
    )

    private fun closeSegment() {
        val segment = currentSegment()
        if (segment.distanceKm > 0 || segment.idleSeconds > 0) closed += segment
        distanceKm = 0.0
        drivingMs = 0
        idleMs = 0
        rateLiters = 0.0
        rateSeen = false
        rateMissing = false
        levelConsumed = 0.0
        levelSeen = false
        path = path.lastOrNull()?.let { mutableListOf(it) } ?: mutableListOf()
    }

    companion object {
        const val STOP_KMH = 1.0
        const val MAX_GAP_MS = 10_000L
        const val REFUEL_JUMP_LITERS = 1.0
        const val MIN_POINT_GAP_M = 30.0
    }
}
