package com.drivesplit.app.vehicle

import com.drivesplit.core.VehicleSample
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 시연용 가상 주행. 실제 시간보다 [timeScale]배 빠르게 시내 → 고속도로 → 국도를 반복한다.
 * 속도·가속에 따라 연비가 달라지게 만들어 구간별 효율 차이가 보이게 한다.
 * [holdStop]을 켜면 감속해 정차하고, 끄면 다시 출발한다.
 * 위치는 강남 → 경부고속도로 → 대전 경로를 달린 거리만큼 따라가고, 끝에 닿으면 되돌아온다.
 */
class SimulatedVehicleSource(
    private val timeScale: Int = 30,
    private val ratedKmPerLiter: Double = 12.0,
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
        var traveledKm = 0.0
        val stepMs = TICK_MS * timeScale
        while (true) {
            val target = if (_holdStop.value) 0.0 else cruiseSpeed(simMs)
            val maxDelta = ACCEL_KMH_PER_SEC * stepMs / 1000.0
            val next = if (target > speed) min(target, speed + maxDelta) else max(target, speed - maxDelta)
            val accel = (next - speed) / (stepMs / 1000.0)
            speed = next

            val rate = fuelRate(speed, accel)
            fuel -= rate * stepMs / 3_600_000.0
            traveledKm += speed * stepMs / 3_600_000.0

            emit(
                VehicleSample(
                    timestampMs = simMs,
                    speedKmh = speed,
                    ignitionOn = true,
                    // 실차 센서처럼 연료량은 0.1L 단위로만 보고한다.
                    fuelLevelLiters = Math.floor(fuel * 10) / 10,
                    fuelRateLitersPerHour = rate,
                    location = DemoRoute.pointAt(traveledKm),
                ),
            )
            simMs += stepMs
            delay(TICK_MS)
        }
    }

    /** 16분 주기: 시내 4분 → 고속도로 8분 → 국도 4분. */
    private fun cruiseSpeed(simMs: Long): Double {
        val t = simMs % CYCLE_MS
        val wobble = sin(simMs / 20_000.0)
        return when {
            t < 4 * MIN_MS -> 45.0 + 15 * wobble
            t < 12 * MIN_MS -> 108.0 + 10 * wobble
            else -> 75.0 + 6 * wobble
        }
    }

    /** 75km/h 부근이 가장 효율적이고, 가속할수록 더 태운다. */
    private fun fuelRate(speedKmh: Double, accelKmhPerSec: Double): Double {
        if (speedKmh < 1.0) return IDLE_LITERS_PER_HOUR
        val efficiency = ratedKmPerLiter * (1.2 - 0.9 * ((speedKmh - 75) / 75).pow(2))
        return speedKmh / efficiency + max(0.0, accelKmhPerSec) * ACCEL_PENALTY
    }

    private companion object {
        const val TICK_MS = 100L
        const val ACCEL_KMH_PER_SEC = 3.0
        const val IDLE_LITERS_PER_HOUR = 0.8
        const val ACCEL_PENALTY = 1.5
        const val MIN_MS = 60_000L
        const val CYCLE_MS = 16 * MIN_MS
    }
}
