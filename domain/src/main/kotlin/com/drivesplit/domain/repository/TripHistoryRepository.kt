package com.drivesplit.domain.repository

import com.drivesplit.domain.Powertrain
import com.drivesplit.domain.trip.TripMode
import com.drivesplit.domain.trip.TripRecord
import kotlinx.coroutines.flow.Flow

/** 최근 기록 목록 한 줄. */
data class TripSummary(
    val id: Long,
    val mode: TripMode,
    val endedAtMs: Long,
    val distanceKm: Double,
    /** 쓴 연료(L) 또는 전기(kWh). */
    val energy: Double,
    val powertrain: Powertrain,
    val totalWon: Long,
    /** 결과 화면을 다시 열 수 있는 기록인지. */
    val hasDetail: Boolean,
)

interface TripHistoryRepository {
    fun recentTrips(limit: Int): Flow<List<TripSummary>>

    suspend fun save(record: TripRecord, endedAtMs: Long)

    suspend fun load(tripId: Long): TripRecord?

    suspend fun delete(tripId: Long)

    suspend fun deleteAll()
}
