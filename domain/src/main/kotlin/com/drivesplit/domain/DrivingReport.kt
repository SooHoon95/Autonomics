package com.drivesplit.domain

import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class SegmentEfficiency(
    val distanceKm: Double,
    /** 쓴 연료(L) 또는 전기(kWh). */
    val energy: Double,
    val source: EnergySource,
    /** 에너지를 거의 안 쓴 구간(정차만 한 구간 등)은 null. */
    val kmPerUnit: Double?,
    val costWon: Long,
    /** 정차 시간을 뺀 평균 주행 속도. */
    val avgMovingSpeedKmh: Double,
    val idleRatio: Double,
    /** 공인 연비·전비 대비 실제 효율(%). 100이면 공인 수치만큼 달린 것. */
    val scorePercent: Int?,
)

/** 솔로 모드 리포트: 실제 연비·전비, 쓴 에너지 값, 구간별 운전 효율. */
data class DrivingReport(
    val segments: List<SegmentEfficiency>,
    val totalKm: Double,
    val totalEnergy: Double,
    val kmPerUnit: Double?,
    val costWon: Long,
    val idleSeconds: Long,
    val scorePercent: Int?,
    val isMeasured: Boolean,
) {
    companion object {
        /** 이보다 적게 쓴 구간(L 또는 kWh)은 효율을 계산하면 값이 튀어서 표시하지 않는다. */
        private const val MIN_ENERGY_FOR_EFFICIENCY = 0.05

        fun of(segments: List<Segment>, profile: VehicleProfile, pricePerUnit: Double): DrivingReport {
            val rated = profile.efficiencyKmPerUnit
            val rows = segments.map { segment ->
                val energy = EnergyModel.energyFor(segment, profile)
                val kmPerUnit = efficiency(segment.distanceKm, energy.amount)
                val totalSeconds = segment.drivingSeconds + segment.idleSeconds
                SegmentEfficiency(
                    distanceKm = segment.distanceKm,
                    energy = energy.amount,
                    source = energy.source,
                    kmPerUnit = kmPerUnit,
                    costWon = (energy.amount * pricePerUnit).roundToLong(),
                    avgMovingSpeedKmh = if (segment.drivingSeconds > 0) segment.distanceKm / (segment.drivingSeconds / 3600.0) else 0.0,
                    idleRatio = if (totalSeconds > 0) segment.idleSeconds.toDouble() / totalSeconds else 0.0,
                    scorePercent = kmPerUnit?.let { (it / rated * 100).roundToInt() },
                )
            }
            val totalKm = rows.sumOf { it.distanceKm }
            val totalEnergy = rows.sumOf { it.energy }
            val kmPerUnit = efficiency(totalKm, totalEnergy)
            return DrivingReport(
                segments = rows,
                totalKm = totalKm,
                totalEnergy = totalEnergy,
                kmPerUnit = kmPerUnit,
                costWon = (totalEnergy * pricePerUnit).roundToLong(),
                idleSeconds = segments.sumOf { it.idleSeconds },
                scorePercent = kmPerUnit?.let { (it / rated * 100).roundToInt() },
                isMeasured = rows.isNotEmpty() && rows.all { it.source.isMeasured },
            )
        }

        private fun efficiency(km: Double, energy: Double): Double? =
            if (energy >= MIN_ENERGY_FOR_EFFICIENCY) km / energy else null
    }
}
