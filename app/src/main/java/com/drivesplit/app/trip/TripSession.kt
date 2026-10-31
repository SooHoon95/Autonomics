package com.drivesplit.app.trip

import com.drivesplit.app.vehicle.VehicleDataSource
import com.drivesplit.core.AutoSplit
import com.drivesplit.core.DrivingReport
import com.drivesplit.core.Passenger
import com.drivesplit.core.Segment
import com.drivesplit.core.Settlement
import com.drivesplit.core.SettlementCalculator
import com.drivesplit.core.SettlementOptions
import com.drivesplit.core.TripRecorder
import com.drivesplit.core.VehicleProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
enum class TripMode {
    /** 동승자와 구간별로 연료비를 나눈다. */
    SPLIT,

    /** 혼자 운전: 실제 연비와 구간별 운전 효율을 본다. */
    SOLO,
}

@Serializable
data class TripSetup(
    val mode: TripMode,
    val passengers: List<Passenger>,
    val profile: VehicleProfile,
    val options: SettlementOptions,
    val tossId: String?,
)

/** 정산과 운전 리포트는 같은 구간 데이터로 만든다. 모드에 따라 화면에서 쓰는 쪽만 다르다. */
data class TripFigures(val settlement: Settlement, val report: DrivingReport) {
    companion object {
        fun of(setup: TripSetup, segments: List<Segment>, settledShares: Map<String, Long>) = TripFigures(
            settlement = SettlementCalculator.settle(
                setup.passengers, segments, setup.profile, setup.options, settledShares = settledShares,
            ),
            report = DrivingReport.of(segments, setup.profile, setup.options.pricePerLiter),
        )
    }
}

/** 지난 기록에서 결과 화면을 그대로 다시 만들 수 있게 저장하는 원본 데이터. */
@Serializable
data class TripRecord(
    val setup: TripSetup,
    val segments: List<Segment>,
    val settledShares: Map<String, Long> = emptyMap(),
) {
    fun toFinished() = TripState.Finished(setup, TripFigures.of(setup, segments, settledShares), settledShares)
}

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
) {
    private val _state = MutableStateFlow<TripState>(TripState.Idle)
    val state: StateFlow<TripState> = _state

    private var recorder: TripRecorder? = null
    private var collectJob: Job? = null
    private var settled: Map<String, Long> = emptyMap()

    fun start(setup: TripSetup) {
        val everyone = setup.passengers.map { it.id }.toSet()
        val autoSplit = if (setup.mode == TripMode.SOLO) AutoSplit() else null
        val newRecorder = TripRecorder(everyone, autoSplit)
        recorder = newRecorder
        settled = emptyMap()
        collectJob?.cancel()
        collectJob = mainScope.launch {
            vehicle.samples().collect { sample ->
                newRecorder.onSample(sample)
                publish(setup, newRecorder, sample.speedKmh)
            }
        }
        publish(setup, newRecorder, speedKmh = 0.0)
    }

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
    }

    fun finish(): TripState.Finished? {
        val active = _state.value as? TripState.Active ?: return null
        val current = recorder ?: return null
        collectJob?.cancel()
        collectJob = null
        val figures = figuresOf(active.setup, current.finish())
        recorder = null
        return TripState.Finished(active.setup, figures, settled).also { _state.value = it }
    }

    fun reset() {
        collectJob?.cancel()
        collectJob = null
        recorder = null
        settled = emptyMap()
        _state.value = TripState.Idle
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
}
