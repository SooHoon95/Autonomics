package com.drivesplit.domain.repository

import com.drivesplit.domain.FuelGrade

/**
 * 리터당 가격.
 * [date]는 가격의 기준일(yyyyMMdd). [isLive]는 오늘 새로 받은 값인지(아니면 저장해 둔 지난 값이나 기본값).
 */
data class FuelPrice(val wonPerLiter: Double, val isLive: Boolean, val date: String? = null)

fun interface FuelPriceSource {
    /** 오늘 기준 전국 평균. 오늘 이미 받았으면 다시 묻지 않는다. */
    suspend fun average(grade: FuelGrade): FuelPrice
}

/** kWh당 충전 요금. [label]은 요금 이름(예: 환경부 공용 급속), [effectiveDate]는 고시 시행일. */
data class ChargePrice(val wonPerKwh: Double, val label: String, val effectiveDate: String, val isLive: Boolean)

fun interface ChargePriceSource {
    suspend fun publicFast(): ChargePrice
}
