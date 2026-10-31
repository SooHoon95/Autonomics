package com.drivesplit.core

import kotlinx.serialization.Serializable

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

/** 차량에서 주기적으로 받는 원시 값. 차종이 지원하지 않는 값은 null. */
data class VehicleSample(
    val timestampMs: Long,
    val speedKmh: Double,
    val ignitionOn: Boolean = true,
    val fuelLevelLiters: Double? = null,
    /** 순간 연료 소모율. 연료량 센서보다 훨씬 촘촘해서 짧은 구간도 실측할 수 있다. */
    val fuelRateLitersPerHour: Double? = null,
    /** 차량 위치. 지도에 지나온 경로를 그릴 때 쓴다. */
    val location: GeoPoint? = null,
)

/** 탑승 인원이 바뀌지 않은 한 구간. 하차·승차(정산 모드)나 정차·거리(솔로 모드)로 나뉜다. */
@Serializable
data class Segment(
    val occupantIds: Set<String>,
    val distanceKm: Double,
    val drivingSeconds: Long,
    val idleSeconds: Long,
    /** 순간 연료 소모율을 적분한 실제 소모량. 구간 내내 소모율을 받았을 때만 값이 있다. */
    val fuelFromRateLiters: Double?,
    /** 연료량 센서 감소분. 주유로 늘어난 양은 빼고 센다. 센서가 없으면 null. */
    val fuelFromLevelLiters: Double?,
    /** 이 구간에서 지나온 위치. 앞 구간의 마지막 점에서 시작해 선이 끊기지 않는다. */
    val path: List<GeoPoint> = emptyList(),
)

@Serializable
data class VehicleProfile(
    /** 공인 복합 연비. 센서가 없을 때 추정과, 솔로 모드 효율 점수의 기준으로 쓴다. */
    val efficiencyKmPerLiter: Double,
    /** 공회전 시간당 소모량. 가솔린 중형 세단 기준 추정치. */
    val idleLitersPerHour: Double = 0.8,
)
