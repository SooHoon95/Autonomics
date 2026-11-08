package com.drivesplit.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class ExtraCost(
    val label: String,
    val amountWon: Long,
    /** null이면 여행 참가자 전원이 나눈다. */
    val sharedBy: Set<String>? = null,
)

@Serializable
data class SettlementOptions(
    /** 연료 리터당 또는 전기 kWh당 가격. */
    @SerialName("pricePerLiter") val pricePerUnit: Double,
    /** false면 운전자는 분담에서 빠지고, 운전자 혼자 탄 구간만 운전자가 부담한다. */
    val driverPays: Boolean = true,
    /** 금액 반올림 단위. 실제 쓴 연료값을 나눈 그대로 보여주려고 1원 단위로 둔다(10원 단위는 실제와 어긋나 보였다). */
    val roundingUnitWon: Long = 1,
)

data class SegmentCost(
    val segment: Segment,
    val energy: SegmentEnergy,
    val costWon: Double,
    val payerIds: Set<String>,
)

data class Settlement(
    val segmentCosts: List<SegmentCost>,
    val extras: List<ExtraCost>,
    val totalWon: Long,
    /** 사람별 부담액. 운전자 몫도 포함되며, 합계는 totalWon과 같다. */
    val shares: Map<String, Long>,
) {
    val totalDistanceKm: Double get() = segmentCosts.sumOf { it.segment.distanceKm }
    val totalEnergy: Double get() = segmentCosts.sumOf { it.energy.amount }
    val isMeasured: Boolean get() = segmentCosts.isNotEmpty() && segmentCosts.all { it.energy.source.isMeasured }
    val kmPerUnit: Double? get() = if (totalEnergy > 0) totalDistanceKm / totalEnergy else null

    /** 이 사람이 함께 탄 구간만 모은 것. 중간 하차 정산서에 쓴다. */
    fun segmentsRiddenBy(id: String): List<SegmentCost> = segmentCosts.filter { id in it.segment.occupantIds }
}

object SettlementCalculator {

    fun settle(
        passengers: List<Passenger>,
        segments: List<Segment>,
        profile: VehicleProfile,
        options: SettlementOptions,
        extras: List<ExtraCost> = emptyList(),
        /** 중간 하차로 이미 정산을 끝낸 사람의 확정 금액. 반올림을 다시 해도 바뀌지 않는다. */
        settledShares: Map<String, Long> = emptyMap(),
    ): Settlement {
        val driverIds = passengers.filter { it.isDriver }.map { it.id }.toSet()
        val exact = passengers.associate { it.id to 0.0 }.toMutableMap()

        val segmentCosts = segments.map { segment ->
            val energy = EnergyModel.energyFor(segment, profile)
            val cost = energy.amount * options.pricePerUnit
            val payers = payersOf(segment.occupantIds, driverIds, options.driverPays)
            payers.forEach { id -> exact[id] = exact.getValue(id) + cost / payers.size }
            SegmentCost(segment, energy, cost, payers)
        }

        val allIds = passengers.map { it.id }.toSet()
        extras.forEach { extra ->
            val payers = payersOf(extra.sharedBy ?: allIds, driverIds, options.driverPays)
            payers.forEach { id -> exact[id] = exact.getValue(id) + extra.amountWon.toDouble() / payers.size }
        }

        val shares = passengers.associate { it.id to 0L } +
            roundEach(exact.filterKeys { it !in settledShares }, options.roundingUnitWon) +
            settledShares.filterKeys { it in exact }
        return Settlement(segmentCosts, extras, shares.values.sum(), shares)
    }

    /**
     * 같은 몫이면 누구든(운전자 포함) 같은 금액이 되게 각자 반올림한다. 1원 단위라 총액은 실제 쓴 연료값과 몇 원 안쪽으로 같다.
     * (합계를 맞추려고 남는 단위를 한 사람씩 몰아주면 같은 몫인데도 10원씩 차이 나고, 주행 중 금액이 한 명씩 번갈아 오른다.)
     */
    private fun roundEach(exact: Map<String, Double>, unit: Long): Map<String, Long> =
        exact.mapValues { (_, v) -> Math.round(v / unit) * unit }

    private fun payersOf(occupants: Set<String>, driverIds: Set<String>, driverPays: Boolean): Set<String> {
        if (driverPays) return occupants
        val riders = occupants - driverIds
        return riders.ifEmpty { occupants }
    }
}
