package com.drivesplit.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * 동승자 폰에서 여는 정산 페이지 링크. 서버 없이 정산 내용을 URL 해시에 담는다.
 * 키 이름을 짧게 써서 QR 밀도를 낮춘다.
 */
@Serializable
data class SharePayload(
    @SerialName("d") val distanceKm: Double,
    /** 쓴 연료(L) 또는 전기(kWh). 단위는 [unit]. */
    @SerialName("l") val energy: Double,
    @SerialName("pr") val pricePerUnit: Int,
    @SerialName("t") val totalWon: Long,
    @SerialName("p") val people: List<ShareLine>,
    /** 에너지 단위. 기본값(L)이면 링크에서 빠진다. */
    @SerialName("u") val unit: String = Powertrain.FUEL.unit,
)

@Serializable
data class ShareLine(
    @SerialName("n") val name: String,
    @SerialName("a") val amountWon: Long,
    @SerialName("dr") val isDriver: Boolean = false,
)

object ShareLink {
    private val json = Json { encodeDefaults = false }

    fun payloadOf(
        settlement: Settlement,
        passengers: List<Passenger>,
        pricePerUnit: Double,
        powertrain: Powertrain = Powertrain.FUEL,
    ) = SharePayload(
        distanceKm = Math.round(settlement.totalDistanceKm * 10) / 10.0,
        energy = Math.round(settlement.totalEnergy * 100) / 100.0,
        pricePerUnit = Math.round(pricePerUnit).toInt(),
        totalWon = settlement.totalWon,
        people = passengers.map { ShareLine(it.name, settlement.shares[it.id] ?: 0, it.isDriver) },
        unit = powertrain.unit,
    )

    /** 중간에 내린 한 사람에게 보여줄 정산서. 그 사람이 탄 구간의 거리와 에너지만 담는다. */
    fun payloadForDropOff(
        settlement: Settlement,
        passenger: Passenger,
        pricePerUnit: Double,
        powertrain: Powertrain = Powertrain.FUEL,
    ): SharePayload {
        val ridden = settlement.segmentsRiddenBy(passenger.id)
        val amount = settlement.shares[passenger.id] ?: 0
        return SharePayload(
            distanceKm = Math.round(ridden.sumOf { it.segment.distanceKm } * 10) / 10.0,
            energy = Math.round(ridden.sumOf { it.energy.amount } * 100) / 100.0,
            pricePerUnit = Math.round(pricePerUnit).toInt(),
            totalWon = amount,
            people = listOf(ShareLine(passenger.name, amount, passenger.isDriver)),
                unit = powertrain.unit,
            )
    }

    fun encode(baseUrl: String, payload: SharePayload): String {
        val bytes = json.encodeToString(SharePayload.serializer(), payload).toByteArray(Charsets.UTF_8)
        return "$baseUrl#" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun decode(url: String): SharePayload {
        val fragment = url.substringAfter('#')
        val text = Base64.getUrlDecoder().decode(fragment).toString(Charsets.UTF_8)
        return json.decodeFromString(SharePayload.serializer(), text)
    }
}
