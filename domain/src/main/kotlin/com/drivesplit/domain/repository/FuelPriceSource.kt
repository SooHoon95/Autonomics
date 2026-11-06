package com.drivesplit.domain.repository

/** 리터당 휘발유 가격. 실시간 값을 못 받으면 기본값과 함께 [isLive]=false. */
data class FuelPrice(val wonPerLiter: Double, val isLive: Boolean)

fun interface FuelPriceSource {
    suspend fun gasolineAverage(): FuelPrice
}
