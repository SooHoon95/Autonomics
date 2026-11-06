package com.drivesplit.domain.repository

import com.drivesplit.domain.Powertrain
import com.drivesplit.domain.VehicleSample
import kotlinx.coroutines.flow.Flow

/** 차량 데이터 공급원. 실차와 Pleos Connect 에뮬레이터 모두 Pleos SDK 구현을 쓴다. */
interface VehicleDataSource {
    /** 연결된 차가 휘발유차인지 전기차인지. 차량 서비스가 답하지 않으면 null. */
    suspend fun detectPowertrain(): Powertrain?

    /** 수집을 시작하면 샘플을 계속 내보낸다. 수집을 취소하면 멈춘다. */
    fun samples(): Flow<VehicleSample>
}
