package com.drivesplit.data

import android.content.Context
import androidx.room.ColumnInfo
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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.drivesplit.domain.Powertrain
import com.drivesplit.domain.repository.TripHistoryRepository
import com.drivesplit.domain.repository.TripSummary
import com.drivesplit.domain.trip.TripMode
import com.drivesplit.domain.trip.TripRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

@Entity(tableName = "trips")
internal data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** TripMode 이름 (SPLIT / SOLO). */
    val mode: String,
    val endedAtMs: Long,
    val distanceKm: Double,
    /** 쓴 연료(L) 또는 전기(kWh). 단위는 [powertrain]. */
    @ColumnInfo(name = "liters") val energy: Double,
    @ColumnInfo(name = "pricePerLiter") val pricePerUnit: Double,
    val totalWon: Long,
    /** Powertrain 이름 (FUEL / ELECTRIC). */
    @ColumnInfo(defaultValue = "FUEL") val powertrain: String = "FUEL",
    /** 결과 화면을 다시 그리기 위한 TripRecord JSON. */
    val detailJson: String? = null,
)

/** 여행별 1인 부담액. 카풀 누적 장부(P1)는 이름 기준으로 이 테이블을 합산한다. */
@Entity(
    tableName = "shares",
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")],
)
internal data class ShareEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val name: String,
    val amountWon: Long,
    val isDriver: Boolean,
)

@Dao
internal interface TripDao {
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

    @Query("SELECT * FROM trips WHERE id = :tripId")
    suspend fun trip(tripId: Long): TripEntity?

    /** 1인 부담액(shares)은 외래키 CASCADE로 함께 지워진다. */
    @Query("DELETE FROM trips WHERE id = :tripId")
    suspend fun deleteTrip(tripId: Long)

    @Query("DELETE FROM trips")
    suspend fun deleteAllTrips()
}

@Database(entities = [TripEntity::class, ShareEntity::class], version = 4, exportSchema = false)
internal abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao

    companion object {
        /** 전기차 지원: 기존 기록은 모두 휘발유차 기록이다. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN powertrain TEXT NOT NULL DEFAULT 'FUEL'")
            }
        }

        // 그 이전 스키마는 출시 전 기록이라 지우고 새로 만든다.
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "drivesplit.db")
                .addMigrations(MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build()
    }
}

/** 여행 기록을 Room에 저장한다. 결과 화면을 다시 그릴 원본(TripRecord)은 JSON으로 함께 둔다. */
class RoomTripHistoryRepository(context: Context) : TripHistoryRepository {
    private val dao = AppDatabase.create(context).tripDao()

    override fun recentTrips(limit: Int): Flow<List<TripSummary>> =
        dao.recentTrips(limit).map { trips -> trips.map { it.toSummary() } }

    override suspend fun save(record: TripRecord, endedAtMs: Long) {
        val figures = record.toFinished().figures
        val report = figures.report
        val settlement = figures.settlement
        dao.insertTripWithShares(
            TripEntity(
                mode = record.setup.mode.name,
                endedAtMs = endedAtMs,
                distanceKm = report.totalKm,
                energy = report.totalEnergy,
                pricePerUnit = record.setup.options.pricePerUnit,
                totalWon = if (record.setup.mode == TripMode.SOLO) report.costWon else settlement.totalWon,
                powertrain = record.setup.profile.powertrain.name,
                detailJson = json.encodeToString(TripRecord.serializer(), record),
            ),
            record.setup.passengers.map { p ->
                ShareEntity(tripId = 0, name = p.name, amountWon = settlement.shares[p.id] ?: 0, isDriver = p.isDriver)
            },
        )
    }

    override suspend fun load(tripId: Long): TripRecord? {
        val detail = dao.trip(tripId)?.detailJson ?: return null
        return runCatching { json.decodeFromString(TripRecord.serializer(), detail) }.getOrNull()
    }

    override suspend fun delete(tripId: Long) = dao.deleteTrip(tripId)

    override suspend fun deleteAll() = dao.deleteAllTrips()

    private fun TripEntity.toSummary() = TripSummary(
        id = id,
        mode = runCatching { TripMode.valueOf(mode) }.getOrDefault(TripMode.SPLIT),
        endedAtMs = endedAtMs,
        distanceKm = distanceKm,
        energy = energy,
        powertrain = runCatching { Powertrain.valueOf(powertrain) }.getOrDefault(Powertrain.FUEL),
        totalWon = totalWon,
        hasDetail = detailJson != null,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
