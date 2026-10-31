package com.drivesplit.core

import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class SegmentEfficiency(
    val distanceKm: Double,
    val liters: Double,
    val source: FuelSource,
    /** 연료를 거의 안 쓴 구간(정차만 한 구간 등)은 null. */
    val kmPerLiter: Double?,
    val costWon: Long,
    /** 정차 시간을 뺀 평균 주행 속도. */
    val avgMovingSpeedKmh: Double,
    val idleRatio: Double,
    /** 공인 연비 대비 실제 연비(%). 100이면 공인 연비만큼 달린 것. */
    val scorePercent: Int?,
)

/** 솔로 모드 리포트: 실제 연비, 태운 기름값, 구간별 운전 효율. */
data class DrivingReport(
    val segments: List<SegmentEfficiency>,
    val totalKm: Double,
    val totalLiters: Double,
    val kmPerLiter: Double?,
    val costWon: Long,
    val idleSeconds: Long,
    val scorePercent: Int?,
    val isMeasured: Boolean,
) {
    companion object {
        /** 이보다 적게 쓴 구간은 연비를 계산하면 값이 튀어서 표시하지 않는다. */
        private const val MIN_LITERS_FOR_EFFICIENCY = 0.05

        fun of(segments: List<Segment>, profile: VehicleProfile, pricePerLiter: Double): DrivingReport {
            val rated = profile.efficiencyKmPerLiter
            val rows = segments.map { segment ->
                val fuel = FuelModel.litersFor(segment, profile)
                val kmPerLiter = efficiency(segment.distanceKm, fuel.liters)
                val totalSeconds = segment.drivingSeconds + segment.idleSeconds
                SegmentEfficiency(
                    distanceKm = segment.distanceKm,
                    liters = fuel.liters,
                    source = fuel.source,
                    kmPerLiter = kmPerLiter,
                    costWon = (fuel.liters * pricePerLiter).roundToLong(),
                    avgMovingSpeedKmh = if (segment.drivingSeconds > 0) segment.distanceKm / (segment.drivingSeconds / 3600.0) else 0.0,
                    idleRatio = if (totalSeconds > 0) segment.idleSeconds.toDouble() / totalSeconds else 0.0,
                    scorePercent = kmPerLiter?.let { (it / rated * 100).roundToInt() },
                )
            }
            val totalKm = rows.sumOf { it.distanceKm }
            val totalLiters = rows.sumOf { it.liters }
            val kmPerLiter = efficiency(totalKm, totalLiters)
            return DrivingReport(
                segments = rows,
                totalKm = totalKm,
                totalLiters = totalLiters,
                kmPerLiter = kmPerLiter,
                costWon = (totalLiters * pricePerLiter).roundToLong(),
                idleSeconds = segments.sumOf { it.idleSeconds },
                scorePercent = kmPerLiter?.let { (it / rated * 100).roundToInt() },
                isMeasured = rows.isNotEmpty() && rows.all { it.source.isMeasured },
            )
        }

        private fun efficiency(km: Double, liters: Double): Double? =
            if (liters >= MIN_LITERS_FOR_EFFICIENCY) km / liters else null
    }
}
