package com.drivesplit.core

enum class FuelSource {
    /** 순간 연료 소모율 적분. 가장 정확하다. */
    FUEL_RATE,

    /** 연료량 센서 감소분. 0.1L 단위라 긴 구간에서만 쓴다. */
    FUEL_LEVEL,

    /** 센서가 없을 때 공인 연비로 추정. */
    ESTIMATED;

    val isMeasured: Boolean get() = this != ESTIMATED
}

data class SegmentFuel(val liters: Double, val source: FuelSource)

object FuelModel {
    /** 연료량 센서는 해상도가 낮아 짧은 구간에선 값이 거의 안 변한다. 이 거리 미만이면 추정치를 쓴다. */
    const val MIN_LEVEL_KM = 20.0

    fun litersFor(segment: Segment, profile: VehicleProfile): SegmentFuel {
        segment.fuelFromRateLiters?.let { return SegmentFuel(it, FuelSource.FUEL_RATE) }

        val level = segment.fuelFromLevelLiters
        if (level != null && level > 0 && segment.distanceKm >= MIN_LEVEL_KM) {
            return SegmentFuel(level, FuelSource.FUEL_LEVEL)
        }

        val driving = segment.distanceKm / profile.efficiencyKmPerLiter
        val idle = segment.idleSeconds / 3600.0 * profile.idleLitersPerHour
        return SegmentFuel(driving + idle, FuelSource.ESTIMATED)
    }
}
