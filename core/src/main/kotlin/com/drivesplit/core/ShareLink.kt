package com.drivesplit.core

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
    @SerialName("l") val liters: Double,
    @SerialName("pr") val pricePerLiter: Int,
    @SerialName("t") val totalWon: Long,
    @SerialName("p") val people: List<ShareLine>,
    /** 운전자의 토스 아이디. 있으면 페이지에서 toss.me/{아이디}/{금액} 송금 버튼을 띄운다. */
    @SerialName("toss") val tossId: String? = null,
)

@Serializable
data class ShareLine(
    @SerialName("n") val name: String,
    @SerialName("a") val amountWon: Long,
    @SerialName("dr") val isDriver: Boolean = false,
)

object ShareLink {
    private val json = Json { encodeDefaults = false }

    fun payloadOf(settlement: Settlement, passengers: List<Passenger>, pricePerLiter: Double, tossId: String?) =
        SharePayload(
            distanceKm = Math.round(settlement.totalDistanceKm * 10) / 10.0,
            liters = Math.round(settlement.totalLiters * 100) / 100.0,
            pricePerLiter = Math.round(pricePerLiter).toInt(),
            totalWon = settlement.totalWon,
            people = passengers.map { ShareLine(it.name, settlement.shares[it.id] ?: 0, it.isDriver) },
            tossId = tossId?.takeIf { it.isNotBlank() },
        )

    /** 중간에 내린 한 사람에게 보여줄 정산서. 그 사람이 탄 구간의 거리와 연료만 담는다. */
    fun payloadForDropOff(
        settlement: Settlement,
        passenger: Passenger,
        pricePerLiter: Double,
        tossId: String?,
    ): SharePayload {
        val ridden = settlement.segmentsRiddenBy(passenger.id)
        val amount = settlement.shares[passenger.id] ?: 0
        return SharePayload(
            distanceKm = Math.round(ridden.sumOf { it.segment.distanceKm } * 10) / 10.0,
            liters = Math.round(ridden.sumOf { it.fuel.liters } * 100) / 100.0,
            pricePerLiter = Math.round(pricePerLiter).toInt(),
            totalWon = amount,
            people = listOf(ShareLine(passenger.name, amount, passenger.isDriver)),
            tossId = tossId?.takeIf { it.isNotBlank() },
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
