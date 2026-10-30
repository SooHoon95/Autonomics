package com.drivesplit.core

data class ExtraCost(
    val label: String,
    val amountWon: Long,
    /** null이면 여행 참가자 전원이 나눈다. */
    val sharedBy: Set<String>? = null,
)

data class SettlementOptions(
    val pricePerLiter: Double,
    /** false면 운전자는 분담에서 빠지고, 운전자 혼자 탄 구간만 운전자가 부담한다. */
    val driverPays: Boolean = true,
    val roundingUnitWon: Long = 10,
)

data class SegmentCost(
    val segment: Segment,
    val fuel: SegmentFuel,
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
    val totalLiters: Double get() = segmentCosts.sumOf { it.fuel.liters }
}

object SettlementCalculator {

    fun settle(
        passengers: List<Passenger>,
        segments: List<Segment>,
        profile: VehicleProfile,
        options: SettlementOptions,
        extras: List<ExtraCost> = emptyList(),
    ): Settlement {
        val driverIds = passengers.filter { it.isDriver }.map { it.id }.toSet()
        val exact = passengers.associate { it.id to 0.0 }.toMutableMap()

        val segmentCosts = segments.map { segment ->
            val fuel = FuelModel.litersFor(segment, profile)
            val cost = fuel.liters * options.pricePerLiter
            val payers = payersOf(segment.occupantIds, driverIds, options.driverPays)
            payers.forEach { id -> exact[id] = exact.getValue(id) + cost / payers.size }
            SegmentCost(segment, fuel, cost, payers)
        }

        val allIds = passengers.map { it.id }.toSet()
        extras.forEach { extra ->
            val payers = payersOf(extra.sharedBy ?: allIds, driverIds, options.driverPays)
            payers.forEach { id -> exact[id] = exact.getValue(id) + extra.amountWon.toDouble() / payers.size }
        }

        val shares = roundPreservingTotal(exact, options.roundingUnitWon)
        return Settlement(segmentCosts, extras, shares.values.sum(), shares)
    }

    private fun payersOf(occupants: Set<String>, driverIds: Set<String>, driverPays: Boolean): Set<String> {
        if (driverPays) return occupants
        val riders = occupants - driverIds
        return riders.ifEmpty { occupants }
    }

    /** 각자 단위 금액으로 내림한 뒤, 남은 단위를 소수점 이하가 큰 순서로 1단위씩 나눠 합계를 맞춘다. */
    internal fun roundPreservingTotal(exact: Map<String, Double>, unit: Long): Map<String, Long> {
        val totalUnits = Math.round(exact.values.sum() / unit)
        val floors = exact.mapValues { (_, v) -> Math.floor(v / unit).toLong() }
        var remaining = totalUnits - floors.values.sum()
        val result = floors.toMutableMap()
        exact.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value / unit - floors.getValue(it.key) }.thenBy { it.key })
            .forEach { entry ->
                if (remaining > 0) {
                    result[entry.key] = result.getValue(entry.key) + 1
                    remaining--
                }
            }
        return result.mapValues { (_, units) -> units * unit }
    }
}
