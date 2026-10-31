package com.drivesplit.app.data

import com.drivesplit.app.vehicle.DemoRoute
import com.drivesplit.core.GeoPoint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/** 좌표를 사람이 읽는 지명으로 바꾼다. 모르면 null. */
fun interface PlaceNameResolver {
    suspend fun nameOf(point: GeoPoint): String?
}

/**
 * 여러 조회 방법을 순서대로 시도하고 결과를 기억한다.
 * 약 100m 안의 좌표는 같은 곳으로 보고 다시 묻지 않는다.
 * TODO: Pleos NaviHelper 연동 후 getCurrentLocationInfo()의 주소를 구간이 끝나는 순간 받아 쓰기
 */
class PlaceNames(private val resolvers: List<PlaceNameResolver>) {
    private val cache = mutableMapOf<Pair<Long, Long>, String?>()

    suspend fun nameOf(point: GeoPoint): String? {
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

/** 시연용: 가상 주행 경로의 이름 붙은 지점 중 가장 가까운 곳. 경로에서 멀면 모른다고 한다. */
object DemoPlaceNames : PlaceNameResolver {
    private const val EXACT_M = 2_000.0
    private const val NEARBY_M = 15_000.0

    override suspend fun nameOf(point: GeoPoint): String? {
        val nearest = DemoRoute.stops
            .filter { it.name != null }
            .minByOrNull { it.point.distanceMetersTo(point) } ?: return null
        val distance = nearest.point.distanceMetersTo(point)
        return when {
            distance <= EXACT_M -> nearest.name
            distance <= NEARBY_M -> "${nearest.name} 부근"
            else -> null
        }
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
