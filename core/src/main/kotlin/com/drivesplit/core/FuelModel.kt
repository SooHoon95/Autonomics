package com.drivesplit.core

enum class FuelSource { MEASURED, ESTIMATED }

data class SegmentFuel(val liters: Double, val source: FuelSource)

object FuelModel {
    /** 연료량 센서는 해상도가 낮아 짧은 구간에선 값이 거의 안 변한다. 이 거리 미만이면 추정치를 쓴다. */
    const val MIN_MEASURED_KM = 20.0

    fun litersFor(segment: Segment, profile: VehicleProfile): SegmentFuel {
        val measured = segment.measuredFuelLiters
        if (measured != null && measured > 0 && segment.distanceKm >= MIN_MEASURED_KM) {
            return SegmentFuel(measured, FuelSource.MEASURED)
        }
        val driving = segment.distanceKm / profile.efficiencyKmPerLiter
        val idle = segment.idleSeconds / 3600.0 * profile.idleLitersPerHour
        return SegmentFuel(driving + idle, FuelSource.ESTIMATED)
    }
}
