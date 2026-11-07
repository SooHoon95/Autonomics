package com.drivesplit.data

import android.content.Context
import com.drivesplit.domain.VehicleInfo
import com.drivesplit.domain.repository.VehicleInfoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** 차량 정보 하나를 SharedPreferences에 JSON으로 둔다. */
class PrefsVehicleInfoRepository(context: Context) : VehicleInfoRepository {
    private val prefs = context.getSharedPreferences("vehicle", Context.MODE_PRIVATE)

    override suspend fun load(): VehicleInfo? = withContext(Dispatchers.IO) {
        val saved = prefs.getString(KEY, null) ?: return@withContext null
        runCatching { json.decodeFromString(VehicleInfo.serializer(), saved) }.getOrNull()
    }

    override suspend fun save(info: VehicleInfo) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY, json.encodeToString(VehicleInfo.serializer(), info)).apply()
    }

    private companion object {
        const val KEY = "info"
        val json = Json { ignoreUnknownKeys = true }
    }
}
