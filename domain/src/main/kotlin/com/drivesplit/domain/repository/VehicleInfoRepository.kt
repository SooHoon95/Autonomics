package com.drivesplit.domain.repository

import com.drivesplit.domain.VehicleInfo

/** 사용자가 입력한 차량 정보. 아직 입력하지 않았으면 null. */
interface VehicleInfoRepository {
    suspend fun load(): VehicleInfo?

    suspend fun save(info: VehicleInfo)
}
