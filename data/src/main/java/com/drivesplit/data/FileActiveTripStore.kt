package com.drivesplit.data

import android.content.Context
import android.util.AtomicFile
import com.drivesplit.domain.repository.ActiveTripStore
import com.drivesplit.domain.trip.ActiveTrip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 진행 중인 여행 하나를 앱 전용 저장소의 파일 한 개에 덮어쓴다. 여행이 끝나면 지운다.
 * 쓰는 도중 시동이 꺼져도 깨진 파일이 남지 않게 AtomicFile로 통째로 바꿔 쓴다.
 */
class FileActiveTripStore(context: Context) : ActiveTripStore {
    private val file = AtomicFile(File(context.filesDir, "active_trip.json"))

    override suspend fun save(trip: ActiveTrip) = withContext(Dispatchers.IO) {
        val bytes = json.encodeToString(ActiveTrip.serializer(), trip).toByteArray()
        val out = file.startWrite()
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    override suspend fun load(): ActiveTrip? = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists()) return@withContext null
        runCatching { json.decodeFromString(ActiveTrip.serializer(), file.readFully().decodeToString()) }.getOrNull()
    }

    override suspend fun clear() = withContext(Dispatchers.IO) { file.delete() }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
