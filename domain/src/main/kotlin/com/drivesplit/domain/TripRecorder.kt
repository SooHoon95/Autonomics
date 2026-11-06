package com.drivesplit.domain

import kotlinx.serialization.Serializable

/**
 * 솔로 모드 자동 구간 나누기. 운전 상황이 바뀐 곳에서 끊어야 구간끼리 효율을 비교할 의미가 있다.
 * - 오래 섰다가 출발(휴게소, 들른 곳). 신호 대기 정도는 넘긴다.
 * - 도로 성격이 바뀜(시내 ↔ 고속). 잠깐 속도가 바뀐 건 무시하고 일정 시간 이어질 때만.
 * - 행정구역(시·군·구)이 바뀜.
 */
data class AutoSplit(
    val minStopSeconds: Long = 120,
    val roadTypeHoldSeconds: Long = 120,
    val splitOnRegionChange: Boolean = true,
)

@Serializable
enum class RoadType {
    CITY,
    HIGHWAY;

    companion object {
        /** 50~80km/h는 어느 쪽으로도 보지 않고 직전 판단을 유지한다. */
        fun of(speedKmh: Double): RoadType? = when {
            speedKmh >= 80 -> HIGHWAY
            speedKmh >= TripRecorder.STOP_KMH && speedKmh < 50 -> CITY
            else -> null
        }
    }
}

/**
 * 시동을 껐다 켜도 여행을 이어 가려고 저장하는 기록기 상태.
 * 마지막 샘플 시각처럼 재부팅하면 의미가 없어지는 값은 담지 않는다.
 */
@Serializable
data class RecorderState(
    val closed: List<Segment>,
    val occupants: Set<String>,
    val current: Segment,
    val rateSeen: Boolean,
    val rateMissing: Boolean,
    val levelSeen: Boolean,
    val segmentRoad: RoadType?,
    val lastRegion: String?,
    val odometerMoving: Boolean,
    val levelLow: Double?,
    /** 저장할 때의 계기판 주행거리. 꺼져 있던 동안 차가 움직였으면 그만큼 이어서 더한다. */
    val lastOdometerKm: Double?,
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
    private var rateEnergy = 0.0
    private var rateSeen = false
    private var rateMissing = false
    private var levelConsumed = 0.0
    private var levelSeen = false
    private var path = mutableListOf<GeoPoint>()

    // 자동 구간 나누기 상태
    private var segmentRoad: RoadType? = null
    private var candidateRoad: RoadType? = null
    private var candidateMs = 0L
    private var lastRegion: String? = null

    // 구간을 넘어 이어지는 상태
    private var odometerMoving = false
    private var levelLow: Double? = null
    private var stoppedSinceMs: Long? = null
    private var last: VehicleSample? = null
    private var resumeOdometerKm: Double? = null

    val currentOccupants: Set<String> get() = occupants
    val isStopped: Boolean get() = (last?.speedKmh ?: 0.0) < STOP_KMH

    fun onSample(sample: VehicleSample) {
        splitOnDepartureIfNeeded(sample)
        splitOnRegionChangeIfNeeded(sample)

        val prev = last
        if (prev == null) addDistanceWhileAway(sample)
        if (prev != null) {
            val dtMs = sample.timestampMs - prev.timestampMs
            // 샘플이 오래 끊겼다면(터널, 앱 재시작) 그 사이 거리·연료를 지어내지 않는다.
            if (dtMs in 1..MAX_GAP_MS) {
                distanceKm += distanceBetween(prev, sample, dtMs)
                val stopped = prev.speedKmh < STOP_KMH && sample.speedKmh < STOP_KMH
                when {
                    !stopped -> drivingMs += dtMs
                    sample.ignitionOn -> idleMs += dtMs
                }
                splitOnRoadTypeChangeIfNeeded(sample, dtMs)
                val prevRate = prev.energyRatePerHour
                val rate = sample.energyRatePerHour
                if (prevRate != null && rate != null) {
                    rateEnergy += (prevRate + rate) / 2 * dtMs / 3_600_000.0
                    rateSeen = true
                } else {
                    rateMissing = true
                }
            }
        }
        sample.energyLevel?.let(::trackEnergyLevel)
        sample.location?.let(::trackLocation)
        last = sample
    }

    fun saveState(): RecorderState = RecorderState(
        closed = closed.toList(),
        occupants = occupants,
        current = currentSegment(),
        rateSeen = rateSeen,
        rateMissing = rateMissing,
        levelSeen = levelSeen,
        segmentRoad = segmentRoad,
        lastRegion = lastRegion,
        odometerMoving = odometerMoving,
        levelLow = levelLow,
        lastOdometerKm = last?.odometerKm ?: resumeOdometerKm,
    )

    /** 다시 켜진 뒤 첫 샘플: 꺼져 있던 동안 계기판이 늘었으면 그 거리를 진행 중인 구간에 더한다. */
    private fun addDistanceWhileAway(sample: VehicleSample) {
        val before = resumeOdometerKm ?: return
        val now = sample.odometerKm ?: return
        resumeOdometerKm = null
        val moved = now - before
        if (moved > 0 && moved < MAX_AWAY_KM) {
            distanceKm += moved
            odometerMoving = true
        }
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

    /** 시내 ↔ 고속이 [AutoSplit.roadTypeHoldSeconds] 넘게 이어지면 그때 새 구간을 시작한다. */
    private fun splitOnRoadTypeChangeIfNeeded(sample: VehicleSample, dtMs: Long) {
        val config = autoSplit ?: return
        val road = RoadType.of(sample.speedKmh) ?: return
        val current = segmentRoad
        when {
            current == null -> segmentRoad = road
            road == current -> {
                candidateRoad = null
                candidateMs = 0
            }
            else -> {
                if (candidateRoad != road) {
                    candidateRoad = road
                    candidateMs = 0
                }
                candidateMs += dtMs
                if (candidateMs >= config.roadTypeHoldSeconds * 1000) {
                    closeSegment()
                    segmentRoad = road
                }
            }
        }
    }

    private fun splitOnRegionChangeIfNeeded(sample: VehicleSample) {
        val config = autoSplit ?: return
        val region = sample.region ?: return
        val previous = lastRegion
        lastRegion = region
        if (config.splitOnRegionChange && previous != null && region != previous && distanceKm > 0) closeSegment()
    }

    /**
     * 계기판 주행거리가 실제로 늘어나는 차면 그 증가분이 가장 정확하다.
     * 값은 오는데 멈춰 있으면(에뮬레이터처럼 따로 움직이는 경우) 속도를 적분한다.
     */
    private fun distanceBetween(prev: VehicleSample, sample: VehicleSample, dtMs: Long): Double {
        val from = prev.odometerKm
        val to = sample.odometerKm
        val odometerDelta = if (from != null && to != null) (to - from).coerceAtLeast(0.0) else 0.0
        if (odometerDelta > 0) odometerMoving = true
        if (odometerMoving) return odometerDelta
        return (prev.speedKmh + sample.speedKmh) / 2 * dtMs / 3_600_000.0
    }

    /**
     * 가장 낮았던 잔량 아래로 내려간 만큼만 소모로 센다.
     * 연료 출렁임, 전기차 회생제동으로 잠깐 오른 양, 주유·충전은 소모로 치지 않는다.
     */
    private fun trackEnergyLevel(level: Double) {
        val low = levelLow
        when {
            low == null -> levelLow = level
            level > low + REFILL_JUMP -> levelLow = level
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
        energyFromRate = if (rateSeen && !rateMissing) rateEnergy else null,
        energyFromLevel = if (levelSeen) levelConsumed else null,
        path = path.toList(),
    )

    private fun closeSegment() {
        val segment = currentSegment()
        if (segment.distanceKm > 0 || segment.idleSeconds > 0) closed += segment
        distanceKm = 0.0
        drivingMs = 0
        idleMs = 0
        rateEnergy = 0.0
        rateSeen = false
        rateMissing = false
        levelConsumed = 0.0
        levelSeen = false
        path = path.lastOrNull()?.let { mutableListOf(it) } ?: mutableListOf()
        segmentRoad = null
        candidateRoad = null
        candidateMs = 0
    }

    companion object {
        const val STOP_KMH = 1.0
        const val MAX_GAP_MS = 10_000L
        /** 이만큼(L 또는 kWh) 넘게 늘면 주유·충전으로 본다. */
        const val REFILL_JUMP = 1.0
        const val MIN_POINT_GAP_M = 30.0

        /** 꺼져 있던 사이 이보다 많이 늘었으면 계기판 값이 바뀐 것(교체·초기화)으로 보고 더하지 않는다. */
        const val MAX_AWAY_KM = 1_000.0

        fun restore(state: RecorderState, autoSplit: AutoSplit? = null): TripRecorder =
            TripRecorder(state.occupants, autoSplit).apply {
                closed += state.closed
                distanceKm = state.current.distanceKm
                drivingMs = state.current.drivingSeconds * 1000
                idleMs = state.current.idleSeconds * 1000
                rateEnergy = state.current.energyFromRate ?: 0.0
                rateSeen = state.rateSeen
                rateMissing = state.rateMissing
                levelConsumed = state.current.energyFromLevel ?: 0.0
                levelSeen = state.levelSeen
                path = state.current.path.toMutableList()
                segmentRoad = state.segmentRoad
                lastRegion = state.lastRegion
                odometerMoving = state.odometerMoving
                levelLow = state.levelLow
                resumeOdometerKm = state.lastOdometerKm
            }
    }
}
