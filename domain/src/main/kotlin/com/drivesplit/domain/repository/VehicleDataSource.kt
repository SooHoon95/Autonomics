package com.drivesplit.domain.repository

import com.drivesplit.domain.Powertrain
import com.drivesplit.domain.VehicleKind
import com.drivesplit.domain.VehicleSample
import kotlinx.coroutines.flow.Flow

/** 차량 데이터 공급원. 실차와 Pleos Connect 에뮬레이터 모두 Pleos SDK 구현을 쓴다. */
interface VehicleDataSource {
    /** 차가 알려주는 값으로 짐작한 차종. 차량 서비스가 답하지 않으면 null. */
    suspend fun detectKind(): VehicleKind?

    /**
     * 수집을 시작하면 샘플을 계속 내보낸다. 수집을 취소하면 멈춘다.
     * 잔량([VehicleSample.energyLevel])은 [powertrain]에 맞는 것만 보낸다(하이브리드는 배터리가 아니라 연료).
     */
    fun samples(powertrain: Powertrain): Flow<VehicleSample>
}
