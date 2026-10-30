package com.drivesplit.app.vehicle

import com.drivesplit.core.VehicleSample
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 시연용 가상 주행. 실제 시간보다 [timeScale]배 빠르게 고속도로 주행을 흉내 낸다.
 * [holdStop]을 켜면 감속해 정차하고, 끄면 다시 출발한다. 하차 처리 시연에 쓴다.
 */
class SimulatedVehicleSource(
    private val timeScale: Int = 30,
    private val efficiencyKmPerLiter: Double = 12.0,
    private val startFuelLiters: Double = 45.0,
) : VehicleDataSource {

    private val _holdStop = MutableStateFlow(false)
    val holdStop: StateFlow<Boolean> = _holdStop

    fun setHoldStop(hold: Boolean) {
        _holdStop.value = hold
    }

    override fun samples(): Flow<VehicleSample> = flow {
        var simMs = 0L
        var speed = 0.0
        var fuel = startFuelLiters
        val stepMs = TICK_MS * timeScale
        while (true) {
            val target = if (_holdStop.value) 0.0 else cruiseSpeed(simMs)
            val maxDelta = ACCEL_KMH_PER_SEC * stepMs / 1000.0
            speed = if (target > speed) min(target, speed + maxDelta) else max(target, speed - maxDelta)

            val km = speed * stepMs / 3_600_000.0
            val idleLiters = if (speed < 1.0) IDLE_LITERS_PER_HOUR * stepMs / 3_600_000.0 else 0.0
            fuel -= km / efficiencyKmPerLiter + idleLiters

            emit(
                VehicleSample(
                    timestampMs = simMs,
                    speedKmh = speed,
                    ignitionOn = true,
                    // 실차 센서처럼 0.1L 단위로만 보고한다.
                    fuelLevelLiters = Math.floor(fuel * 10) / 10,
                ),
            )
            simMs += stepMs
            delay(TICK_MS)
        }
    }

    private fun cruiseSpeed(simMs: Long): Double = 100.0 + 8 * sin(simMs / 40_000.0)

    private companion object {
        const val TICK_MS = 100L
        const val ACCEL_KMH_PER_SEC = 3.0
        const val IDLE_LITERS_PER_HOUR = 0.8
    }
}
