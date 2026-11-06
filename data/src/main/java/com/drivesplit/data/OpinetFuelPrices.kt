package com.drivesplit.data

import com.drivesplit.domain.repository.FuelPrice
import com.drivesplit.domain.repository.FuelPriceSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

enum class FuelType(val opinetCode: String, val label: String) {
    GASOLINE("B027", "휘발유"),
    DIESEL("D047", "경유"),
}

/**
 * 오피넷 전국 평균 유가. 키가 없거나 호출이 실패하면 기본값을 돌려주고 isLive=false로 표시한다.
 */
class OpinetFuelPrices(private val apiKey: String) : FuelPriceSource {

    private val api: OpinetApi = Retrofit.Builder()
        .baseUrl("https://www.opinet.co.kr/api/")
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(OpinetApi::class.java)

    override suspend fun gasolineAverage(): FuelPrice = nationalAverage(FuelType.GASOLINE)

    suspend fun nationalAverage(type: FuelType): FuelPrice {
        if (apiKey.isBlank()) return FuelPrice(DEFAULT_WON_PER_LITER, isLive = false)
        return runCatching {
            api.averageAll(apiKey).result.oil
                .first { it.productCode == type.opinetCode }
                .price.toDouble()
        }.fold(
            onSuccess = { FuelPrice(it, isLive = true) },
            onFailure = { FuelPrice(DEFAULT_WON_PER_LITER, isLive = false) },
        )
    }

    companion object {
        /** 오피넷 연결 전 임시 기본값. 설정 화면에서 수정할 수 있다. */
        const val DEFAULT_WON_PER_LITER = 1_700.0

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

private interface OpinetApi {
    @GET("avgAllPrice.do?out=json")
    suspend fun averageAll(@Query("code") key: String): OpinetResponse
}

@Serializable
private data class OpinetResponse(@SerialName("RESULT") val result: OpinetResult)

@Serializable
private data class OpinetResult(@SerialName("OIL") val oil: List<OpinetOil>)

@Serializable
private data class OpinetOil(
    @SerialName("PRODCD") val productCode: String,
    @SerialName("PRICE") val price: String,
)
