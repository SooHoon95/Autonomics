package com.drivesplit.data

import com.drivesplit.domain.GeoPoint
import com.drivesplit.domain.repository.PlaceNameResolver
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/**
 * 여러 조회 방법을 순서대로 시도하고 결과를 기억한다.
 * 약 100m 안의 좌표는 같은 곳으로 보고 다시 묻지 않는다.
 */
class PlaceNames(private val resolvers: List<PlaceNameResolver>) : PlaceNameResolver {
    private val cache = mutableMapOf<Pair<Long, Long>, String?>()

    override suspend fun nameOf(point: GeoPoint): String? {
        val key = Math.round(point.latitude * 1000) to Math.round(point.longitude * 1000)
        if (key in cache) return cache[key]
        val name = resolvers.firstNotNullOfOrNull { runCatching { it.nameOf(point) }.getOrNull() }
        cache[key] = name
        return name
    }
}

/** 카카오 로컬 API 좌표→행정구역. 예: "분당구 삼평동". */
class KakaoPlaceNames(private val apiKey: String) : PlaceNameResolver {
    private val api: KakaoLocalApi = Retrofit.Builder()
        .baseUrl("https://dapi.kakao.com/")
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(KakaoLocalApi::class.java)

    override suspend fun nameOf(point: GeoPoint): String? {
        val regions = api.regionOf("KakaoAK $apiKey", x = point.longitude, y = point.latitude).documents
        // 행정동(H)이 동네 이름으로 더 익숙하다.
        val region = regions.firstOrNull { it.regionType == "H" } ?: regions.firstOrNull() ?: return null
        return listOf(region.district, region.town).filter { it.isNotBlank() }.joinToString(" ").ifBlank { null }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

private interface KakaoLocalApi {
    @GET("v2/local/geo/coord2regioncode.json")
    suspend fun regionOf(
        @Header("Authorization") auth: String,
        @Query("x") x: Double,
        @Query("y") y: Double,
    ): KakaoRegionResponse
}

@Serializable
private data class KakaoRegionResponse(val documents: List<KakaoRegion>)

@Serializable
private data class KakaoRegion(
    @SerialName("region_type") val regionType: String,
    @SerialName("region_2depth_name") val district: String,
    @SerialName("region_3depth_name") val town: String,
)
