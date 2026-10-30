package com.drivesplit.app.vehicle

import com.drivesplit.core.VehicleSample
import kotlinx.coroutines.flow.Flow

/**
 * 차량 데이터 공급원. 지금은 시연용 시뮬레이터만 있고,
 * Pleos Vehicle SDK 접근 승인이 나면 PleosVehicleSource를 추가해 교체한다.
 */
interface VehicleDataSource {
    /** 수집을 시작하면 샘플을 계속 내보낸다. 수집을 취소하면 멈춘다. */
    fun samples(): Flow<VehicleSample>
}
