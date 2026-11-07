package com.drivesplit.domain

import kotlinx.serialization.Serializable

/** 연료 종류. 유가를 따로 조회한다. */
enum class FuelGrade { GASOLINE, DIESEL }

/**
 * 사용자가 고르는 차종. 하이브리드는 밖에서 충전하지 않아 배터리는 연료로 채운 완충일 뿐이므로
 * 비용은 연료만 센다(휘발유차와 같은 계산). 플러그인 하이브리드는 아직 지원하지 않는다.
 */
@Serializable
enum class VehicleKind(val powertrain: Powertrain, val fuelGrade: FuelGrade?) {
    GASOLINE(Powertrain.FUEL, FuelGrade.GASOLINE),
    DIESEL(Powertrain.FUEL, FuelGrade.DIESEL),
    HYBRID(Powertrain.FUEL, FuelGrade.GASOLINE),
    ELECTRIC(Powertrain.ELECTRIC, null);

    companion object {
        /**
         * 차가 알려주는 값으로 차종을 짐작한다. 배터리와 연료가 둘 다 있으면 하이브리드.
         * 차는 휘발유와 경유를 구분해 주지 않으므로 연료만 있으면 휘발유로 두고 사용자가 고친다.
         */
        fun guess(hasBattery: Boolean, hasFuel: Boolean): VehicleKind? = when {
            hasBattery && hasFuel -> HYBRID
            hasBattery -> ELECTRIC
            hasFuel -> GASOLINE
            else -> null
        }
    }
}

/** 처음 실행할 때 입력받아 저장하는 차량 정보. */
@Serializable
data class VehicleInfo(
    val kind: VehicleKind,
    /** 공인 복합 연비(km/L) 또는 전비(km/kWh). */
    val efficiencyKmPerUnit: Double,
) {
    val powertrain: Powertrain get() = kind.powertrain

    fun toProfile(): VehicleProfile = VehicleProfile.of(powertrain, efficiencyKmPerUnit)
}
