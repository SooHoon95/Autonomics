package com.drivesplit.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** TripMode 이름 (SPLIT / SOLO). */
    val mode: String,
    val endedAtMs: Long,
    val distanceKm: Double,
    val liters: Double,
    val pricePerLiter: Double,
    val totalWon: Long,
)

/** 여행별 1인 부담액. 카풀 누적 장부(P1)는 이름 기준으로 이 테이블을 합산한다. */
@Entity(
    tableName = "shares",
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")],
)
data class ShareEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val name: String,
    val amountWon: Long,
    val isDriver: Boolean,
)

@Dao
interface TripDao {
    @Insert
    suspend fun insertTrip(trip: TripEntity): Long

    @Insert
    suspend fun insertShares(shares: List<ShareEntity>)

    @Transaction
    suspend fun insertTripWithShares(trip: TripEntity, shares: List<ShareEntity>) {
        val tripId = insertTrip(trip)
        insertShares(shares.map { it.copy(tripId = tripId) })
    }

    @Query("SELECT * FROM trips ORDER BY endedAtMs DESC LIMIT :limit")
    fun recentTrips(limit: Int): Flow<List<TripEntity>>

    /** 1인 부담액(shares)은 외래키 CASCADE로 함께 지워진다. */
    @Query("DELETE FROM trips WHERE id = :tripId")
    suspend fun deleteTrip(tripId: Long)

    @Query("DELETE FROM trips")
    suspend fun deleteAllTrips()
}

@Database(entities = [TripEntity::class, ShareEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao

    companion object {
        // 출시 전이라 스키마가 바뀌면 기록을 지우고 새로 만든다.
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "drivesplit.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
