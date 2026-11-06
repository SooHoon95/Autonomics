package com.drivesplit.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 무엇을 태워서 가는 차인지. 정산 단가와 표시 단위가 여기서 갈린다. */
@Serializable
enum class Powertrain(val unit: String) {
    /** 휘발유·경유. 리터 단위. */
    FUEL("L"),

    /** 전기차. 배터리 에너지 kWh 단위. */
    ELECTRIC("kWh"),
}

@Serializable
data class Passenger(
    val id: String,
    val name: String,
    val isDriver: Boolean = false,
)

@Serializable
data class GeoPoint(val latitude: Double, val longitude: Double) {
    /** 짧은 거리용 근사(등장방형 투영). 경로 점을 솎아낼 때만 쓰므로 이 정도 정확도면 충분하다. */
    fun distanceMetersTo(other: GeoPoint): Double {
        val midLat = Math.toRadians((latitude + other.latitude) / 2)
        val dx = Math.toRadians(other.longitude - longitude) * Math.cos(midLat)
        val dy = Math.toRadians(other.latitude - latitude)
        return Math.sqrt(dx * dx + dy * dy) * EARTH_RADIUS_M
    }

    private companion object {
        const val EARTH_RADIUS_M = 6_371_000.0
    }
}

/**
 * 차량에서 주기적으로 받는 원시 값. 차종이 지원하지 않는 값은 null.
 * 에너지 값의 단위는 차의 [Powertrain]을 따른다(휘발유차는 L, 전기차는 kWh).
 */
data class VehicleSample(
    val timestampMs: Long,
    val speedKmh: Double,
    val ignitionOn: Boolean = true,
    /** 남은 연료량 또는 배터리 에너지. */
    val energyLevel: Double? = null,
    /** 시간당 에너지 소모율. 잔량 센서보다 훨씬 촘촘해서 짧은 구간도 실측할 수 있다. */
    val energyRatePerHour: Double? = null,
    /** 차의 누적 주행거리. 있으면 속도를 적분하지 않고 이 증가분을 거리로 쓴다. */
    val odometerKm: Double? = null,
    /** 지금 있는 시·군·구. 바뀌면 솔로 모드에서 구간을 나눈다. */
    val region: String? = null,
    /** 차량 위치. 지도에 지나온 경로를 그릴 때 쓴다. */
    val location: GeoPoint? = null,
)

/** 지나온 길 위의 지명. 지난 기록에서도 구간 이름을 다시 보여주려고 기록과 함께 저장한다. */
@Serializable
data class NamedPlace(val point: GeoPoint, val name: String)

/** 탑승 인원이 바뀌지 않은 한 구간. 하차·승차(정산 모드)나 정차·거리(솔로 모드)로 나뉜다. */
@Serializable
data class Segment(
    val occupantIds: Set<String>,
    val distanceKm: Double,
    val drivingSeconds: Long,
    val idleSeconds: Long,
    /** 소모율을 적분한 실제 소모량. 구간 내내 소모율을 받았을 때만 값이 있다. */
    @SerialName("fuelFromRateLiters") val energyFromRate: Double?,
    /** 잔량 센서 감소분. 주유·충전으로 늘어난 양은 빼고 센다. 센서가 없으면 null. */
    @SerialName("fuelFromLevelLiters") val energyFromLevel: Double?,
    /** 이 구간에서 지나온 위치. 앞 구간의 마지막 점에서 시작해 선이 끊기지 않는다. */
    val path: List<GeoPoint> = emptyList(),
)

@Serializable
data class VehicleProfile(
    /** 공인 복합 연비(km/L) 또는 전비(km/kWh). 센서가 없을 때 추정과, 솔로 모드 효율 점수의 기준으로 쓴다. */
    @SerialName("efficiencyKmPerLiter") val efficiencyKmPerUnit: Double,
    /** 정차 중 시간당 소모량. 없으면 동력 방식별 기본값을 쓴다. */
    @SerialName("idleLitersPerHour") val idlePerHour: Double = DEFAULT_IDLE_LITERS_PER_HOUR,
    val powertrain: Powertrain = Powertrain.FUEL,
) {
    companion object {
        /** 가솔린 중형 세단 공회전 추정치. */
        const val DEFAULT_IDLE_LITERS_PER_HOUR = 0.8

        /** 전기차는 정차 중 공조·전장만 돈다. 겨울 난방 기준으로 넉넉히 잡은 추정치. */
        const val DEFAULT_IDLE_KW = 1.0

        fun of(powertrain: Powertrain, efficiencyKmPerUnit: Double) = VehicleProfile(
            efficiencyKmPerUnit = efficiencyKmPerUnit,
            idlePerHour = if (powertrain == Powertrain.ELECTRIC) DEFAULT_IDLE_KW else DEFAULT_IDLE_LITERS_PER_HOUR,
            powertrain = powertrain,
        )
    }
}
