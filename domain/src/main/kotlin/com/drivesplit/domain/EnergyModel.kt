package com.drivesplit.domain

enum class EnergySource {
    /** 소모율 적분. 가장 정확하다. */
    RATE,

    /** 잔량 센서 감소분. 휘발유차는 해상도가 낮아 긴 구간에서만 쓴다. */
    LEVEL,

    /** 센서가 없을 때 공인 연비·전비로 추정. */
    ESTIMATED;

    val isMeasured: Boolean get() = this != ESTIMATED
}

/** 한 구간에서 쓴 연료(L) 또는 전기(kWh). */
data class SegmentEnergy(val amount: Double, val source: EnergySource)

object EnergyModel {
    /**
     * 연료량 센서는 해상도가 낮아(0.1L, 차에 따라 1%) 짧은 구간에선 값이 거의 안 변한다. 이 거리 미만이면 추정치를 쓴다.
     * 전기차 배터리 에너지는 Wh 단위로 촘촘해서 거리 제한 없이 쓴다.
     */
    const val MIN_FUEL_LEVEL_KM = 20.0

    fun energyFor(segment: Segment, profile: VehicleProfile): SegmentEnergy {
        segment.energyFromRate?.let { return SegmentEnergy(it, EnergySource.RATE) }

        val level = segment.energyFromLevel
        val minKm = if (profile.powertrain == Powertrain.ELECTRIC) 0.0 else MIN_FUEL_LEVEL_KM
        if (level != null && level > 0 && segment.distanceKm >= minKm) {
            return SegmentEnergy(level, EnergySource.LEVEL)
        }

        val driving = segment.distanceKm / profile.efficiencyKmPerUnit
        val idle = segment.idleSeconds / 3600.0 * profile.idlePerHour
        return SegmentEnergy(driving + idle, EnergySource.ESTIMATED)
    }
}
