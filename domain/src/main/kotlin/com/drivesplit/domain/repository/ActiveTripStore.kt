package com.drivesplit.domain.repository

import com.drivesplit.domain.trip.ActiveTrip

/** 진행 중인 여행 하나를 보관한다. 시동이 꺼져 앱이 죽어도 다시 켜면 이어서 기록한다. */
interface ActiveTripStore {
    suspend fun save(trip: ActiveTrip)

    suspend fun load(): ActiveTrip?

    suspend fun clear()
}
