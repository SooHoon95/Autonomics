package com.drivesplit.app.trip

import com.drivesplit.app.vehicle.VehicleDataSource
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

data class TripSetup(
    val passengers: List<Passenger>,
    val profile: VehicleProfile,
    val options: SettlementOptions,
    val tossId: String?,
)

sealed interface TripState {
    data object Idle : TripState

    data class Active(
        val setup: TripSetup,
        val occupantIds: Set<String>,
        val speedKmh: Double,
        val isStopped: Boolean,
        /** 진행 중인 구간까지 반영한 실시간 정산. */
        val live: Settlement,
    ) : TripState

    data class Finished(val setup: TripSetup, val settlement: Settlement) : TripState
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

    fun start(setup: TripSetup) {
        val everyone = setup.passengers.map { it.id }.toSet()
        val newRecorder = TripRecorder(everyone)
        recorder = newRecorder
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
        current.changeOccupants(ids)
        publish(active.setup, current, active.speedKmh)
    }

    fun finish(): TripState.Finished? {
        val active = _state.value as? TripState.Active ?: return null
        val current = recorder ?: return null
        collectJob?.cancel()
        collectJob = null
        val settlement = settle(active.setup, current.finish())
        recorder = null
        return TripState.Finished(active.setup, settlement).also { _state.value = it }
    }

    fun reset() {
        collectJob?.cancel()
        collectJob = null
        recorder = null
        _state.value = TripState.Idle
    }

    private fun publish(setup: TripSetup, recorder: TripRecorder, speedKmh: Double) {
        _state.value = TripState.Active(
            setup = setup,
            occupantIds = recorder.currentOccupants,
            speedKmh = speedKmh,
            isStopped = recorder.isStopped,
            live = settle(setup, recorder.snapshot()),
        )
    }

    private fun settle(setup: TripSetup, segments: List<Segment>) =
        SettlementCalculator.settle(setup.passengers, segments, setup.profile, setup.options)
}
