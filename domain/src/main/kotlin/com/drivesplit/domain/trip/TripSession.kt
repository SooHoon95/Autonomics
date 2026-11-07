package com.drivesplit.domain.trip

import com.drivesplit.domain.AutoSplit
import com.drivesplit.domain.DrivingReport
import com.drivesplit.domain.NamedPlace
import com.drivesplit.domain.Passenger
import com.drivesplit.domain.Segment
import com.drivesplit.domain.Settlement
import com.drivesplit.domain.SettlementCalculator
import com.drivesplit.domain.RecorderState
import com.drivesplit.domain.SettlementOptions
import com.drivesplit.domain.TripRecorder
import com.drivesplit.domain.VehicleProfile
import com.drivesplit.domain.repository.ActiveTripStore
import com.drivesplit.domain.repository.VehicleDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
enum class TripMode {
    /** 동승자와 구간별로 연료비·충전비를 나눈다. */
    SPLIT,

    /** 혼자 운전: 실제 연비·전비와 구간별 운전 효율을 본다. */
    SOLO,
}

@Serializable
data class TripSetup(
    val mode: TripMode,
    val passengers: List<Passenger>,
    val profile: VehicleProfile,
    val options: SettlementOptions,
    val tossId: String?,
    /** 운전자의 카카오페이 송금 코드. */
    val kakaoPayCode: String? = null,
)

/** 정산과 운전 리포트는 같은 구간 데이터로 만든다. 모드에 따라 화면에서 쓰는 쪽만 다르다. */
data class TripFigures(val settlement: Settlement, val report: DrivingReport) {
    companion object {
        fun of(setup: TripSetup, segments: List<Segment>, settledShares: Map<String, Long>) = TripFigures(
            settlement = SettlementCalculator.settle(
                setup.passengers, segments, setup.profile, setup.options, settledShares = settledShares,
            ),
            report = DrivingReport.of(segments, setup.profile, setup.options.pricePerUnit),
        )
    }
}

/** 지난 기록에서 결과 화면을 그대로 다시 만들 수 있게 저장하는 원본 데이터. */
@Serializable
data class TripRecord(
    val setup: TripSetup,
    val segments: List<Segment>,
    val settledShares: Map<String, Long> = emptyMap(),
    /** 지나온 길의 지명. 앱을 다시 켜도 지난 기록의 구간 이름이 남도록 같이 저장한다. */
    val places: List<NamedPlace> = emptyList(),
) {
    fun toFinished() = TripState.Finished(setup, TripFigures.of(setup, segments, settledShares), settledShares)
}

/** 진행 중인 여행을 다시 이어 가는 데 필요한 전부. [ActiveTripStore]에 저장한다. */
@Serializable
data class ActiveTrip(
    val setup: TripSetup,
    val recorder: RecorderState,
    val settledShares: Map<String, Long> = emptyMap(),
)

sealed interface TripState {
    data object Idle : TripState

    data class Active(
        val setup: TripSetup,
        val occupantIds: Set<String>,
        val speedKmh: Double,
        val isStopped: Boolean,
        /** 진행 중인 구간까지 반영한 실시간 값. */
        val live: TripFigures,
        /** 중간에 내려서 정산을 끝낸 사람과 확정 금액. */
        val settledShares: Map<String, Long> = emptyMap(),
    ) : TripState

    data class Finished(
        val setup: TripSetup,
        val figures: TripFigures,
        val settledShares: Map<String, Long> = emptyMap(),
    ) : TripState {
        fun toRecord() = TripRecord(setup, figures.settlement.segmentCosts.map { it.segment }, settledShares)
    }
}

/**
 * 진행 중인 여행 한 건. 앱 프로세스 단위 싱글턴이라 화면이 사라져도 기록이 유지된다.
 * 모든 호출과 샘플 수집은 메인 스레드에서만 일어나므로 TripRecorder를 잠금 없이 쓴다.
 */
class TripSession(
    private val vehicle: VehicleDataSource,
    private val mainScope: CoroutineScope,
    private val store: ActiveTripStore,
) {
    private val _state = MutableStateFlow<TripState>(TripState.Idle)
    val state: StateFlow<TripState> = _state

    private var recorder: TripRecorder? = null
    /** 중간에 동승자를 태우면 늘어나므로 시작할 때의 설정과 따로 들고 있는다. */
    private var currentSetup: TripSetup? = null
    private var collectJob: Job? = null
    private var settled: Map<String, Long> = emptyMap()
    private val resumeLock = Mutex()

    fun start(setup: TripSetup) {
        val everyone = setup.passengers.map { it.id }.toSet()
        settled = emptyMap()
        record(setup, TripRecorder(everyone, autoSplitFor(setup)))
    }

    /**
     * 시동을 껐다 켜서 앱이 다시 떴을 때, 저장해 둔 여행이 있으면 이어서 기록한다.
     * 이미 여행 중이면 아무것도 하지 않는다. 이어 갔으면 true.
     */
    suspend fun resume(): Boolean = resumeLock.withLock {
        // 앱 실행과 부팅 알림이 거의 동시에 부른다. 두 번 이어 붙이면 차량 구독이 겹쳐서 하나만 받는다.
        if (_state.value !is TripState.Idle) return false
        val saved = runCatching { store.load() }.getOrNull() ?: return false
        settled = saved.settledShares
        record(saved.setup, TripRecorder.restore(saved.recorder, autoSplitFor(saved.setup)))
        true
    }

    private fun record(setup: TripSetup, newRecorder: TripRecorder) {
        recorder = newRecorder
        currentSetup = setup
        collectJob?.cancel()
        collectJob = mainScope.launch {
            var samples = 0
            vehicle.samples(setup.profile.powertrain).collect { sample ->
                val now = currentSetup ?: return@collect
                newRecorder.onSample(sample)
                publish(now, newRecorder, sample.speedKmh)
                // 시동이 갑자기 꺼져도 잃는 건 마지막 몇 초뿐이다.
                if (++samples % SAVE_EVERY_SAMPLES == 0) save(now, newRecorder)
            }
        }
        publish(setup, newRecorder, speedKmh = 0.0)
        mainScope.launch { save(setup, newRecorder) }
    }

    private suspend fun save(setup: TripSetup, recorder: TripRecorder) {
        runCatching { store.save(ActiveTrip(setup, recorder.saveState(), settled)) }
    }

    private fun autoSplitFor(setup: TripSetup) = if (setup.mode == TripMode.SOLO) AutoSplit() else null

    fun setOccupants(ids: Set<String>) {
        val active = _state.value as? TripState.Active ?: return
        val current = recorder ?: return
        // 주행 중 조작 제한: 정차 상태에서만 탑승 인원을 바꿀 수 있다.
        if (!current.isStopped || ids.isEmpty()) return
        val leaving = active.occupantIds - ids
        current.changeOccupants(ids)
        // 내린 사람은 탄 구간이 모두 끝났으므로 지금 금액을 확정한다. 다시 타면 확정을 푼다.
        val atDropOff = figuresOf(active.setup, current.snapshot()).settlement.shares
        settled = (settled - ids) + leaving.associateWith { atDropOff[it] ?: 0L }
        publish(active.setup, current, active.speedKmh)
        // 하차 정산은 확정 금액이라 바로 저장한다.
        mainScope.launch { save(active.setup, current) }
    }

    /**
     * 정차 중에 새 동승자를 태운다. 지금부터 새 구간이 시작되고, 이 사람은 탄 뒤의 구간만 나눠 낸다.
     * 태웠으면 새 동승자의 id, 태울 수 없으면(주행 중, 빈 이름, 자리 없음) null.
     */
    fun addPassenger(name: String): String? {
        val active = _state.value as? TripState.Active ?: return null
        val current = recorder ?: return null
        val setup = currentSetup ?: return null
        if (!current.isStopped || name.isBlank() || current.currentOccupants.size >= MAX_OCCUPANTS) return null
        val id = generateSequence(setup.passengers.size) { it + 1 }
            .map { "rider$it" }
            .first { candidate -> setup.passengers.none { it.id == candidate } }
        val updated = setup.copy(passengers = setup.passengers + Passenger(id, name.trim()))
        currentSetup = updated
        current.changeOccupants(current.currentOccupants + id)
        publish(updated, current, active.speedKmh)
        mainScope.launch { save(updated, current) }
        return id
    }

    fun finish(): TripState.Finished? {
        val active = _state.value as? TripState.Active ?: return null
        val current = recorder ?: return null
        collectJob?.cancel()
        collectJob = null
        val figures = figuresOf(active.setup, current.finish())
        recorder = null
        currentSetup = null
        mainScope.launch { runCatching { store.clear() } }
        return TripState.Finished(active.setup, figures, settled).also { _state.value = it }
    }

    fun reset() {
        val wasActive = _state.value is TripState.Active
        collectJob?.cancel()
        collectJob = null
        recorder = null
        currentSetup = null
        settled = emptyMap()
        _state.value = TripState.Idle
        if (wasActive) mainScope.launch { runCatching { store.clear() } }
    }

    private fun publish(setup: TripSetup, recorder: TripRecorder, speedKmh: Double) {
        _state.value = TripState.Active(
            setup = setup,
            occupantIds = recorder.currentOccupants,
            speedKmh = speedKmh,
            isStopped = recorder.isStopped,
            live = figuresOf(setup, recorder.snapshot()),
            settledShares = settled,
        )
    }

    private fun figuresOf(setup: TripSetup, segments: List<Segment>) = TripFigures.of(setup, segments, settled)

    private companion object {
        /** 샘플이 1초마다 오므로 5초마다 저장한다. */
        const val SAVE_EVERY_SAMPLES = 5

        /** 운전자 포함 5인승 기준. */
        const val MAX_OCCUPANTS = 5
    }
}
