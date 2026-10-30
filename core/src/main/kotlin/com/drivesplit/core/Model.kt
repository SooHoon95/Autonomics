package com.drivesplit.core

data class Passenger(
    val id: String,
    val name: String,
    val isDriver: Boolean = false,
)

/** 차량에서 주기적으로 받는 원시 값. 차종이 지원하지 않는 값은 null. */
data class VehicleSample(
    val timestampMs: Long,
    val speedKmh: Double,
    val ignitionOn: Boolean = true,
    val fuelLevelLiters: Double? = null,
)

/** 탑승 인원이 바뀌지 않은 한 구간. 하차·승차가 일어나면 새 구간이 시작된다. */
data class Segment(
    val occupantIds: Set<String>,
    val distanceKm: Double,
    val drivingSeconds: Long,
    val idleSeconds: Long,
    /** 연료량 센서로 측정한 소모량. 센서가 없거나 구간 중 주유했으면 null. */
    val measuredFuelLiters: Double?,
)

data class VehicleProfile(
    val efficiencyKmPerLiter: Double,
    /** 공회전 시간당 소모량. 가솔린 중형 세단 기준 추정치. */
    val idleLitersPerHour: Double = 0.8,
)
