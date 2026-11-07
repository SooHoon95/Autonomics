package com.drivesplit.data

import android.content.Context
import com.drivesplit.domain.FuelGrade
import com.drivesplit.domain.repository.ChargePrice
import com.drivesplit.domain.repository.ChargePriceSource
import com.drivesplit.domain.repository.FuelPrice
import com.drivesplit.domain.repository.FuelPriceSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET

/**
 * 오늘 단가(유가·충전 요금)를 정산 웹 사이트의 함수(/api/prices)에서 받는다.
 * 오피넷은 그 함수만 부르고 CDN이 1시간 캐시하므로 사용자가 늘어도 오피넷 무료 한도(하루 1,500건)에 걸리지 않고,
 * 오피넷 키도 앱에 들어가지 않는다. 받지 못하면 마지막으로 받은 값을 날짜와 함께, 그것도 없으면 기본값을 쓴다.
 */
class NetlifyPrices(context: Context, siteUrl: String) : FuelPriceSource, ChargePriceSource {

    private val prefs = context.getSharedPreferences("prices", Context.MODE_PRIVATE)
    private val lock = Mutex()

    /** 이번 실행에서 받은 값. 앱이 켜져 있는 동안 한 번만 묻는다. */
    private var fetched: Prices? = null

    private val api: PricesApi = Retrofit.Builder()
        .baseUrl(siteUrl)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(PricesApi::class.java)

    override suspend fun average(grade: FuelGrade): FuelPrice {
        val (prices, live) = load()
        val today = prices?.fuel?.let { if (grade == FuelGrade.DIESEL) it.diesel else it.gasoline }
        if (live && today != null) return FuelPrice(today.wonPerLiter, isLive = true, date = today.date)
        return cachedFuel(grade) ?: FuelPrice(DEFAULT_WON_PER_LITER, isLive = false)
    }

    override suspend fun publicFast(): ChargePrice {
        val (prices, live) = load()
        val tariff = prices?.publicFast ?: return BUILT_IN_FAST
        return ChargePrice(tariff.wonPerKwh, tariff.label, tariff.effectiveDate, isLive = live)
    }

    /** 받은 값(못 받으면 저장해 둔 값)과, 그게 이번에 새로 받은 것인지. */
    private suspend fun load(): Pair<Prices?, Boolean> = lock.withLock {
        fetched?.let { return it to true }
        val remote = runCatching { api.prices() }.getOrNull()
        if (remote != null) {
            fetched = remote
            save(remote)
            return remote to true
        }
        cached() to false
    }

    private fun cached(): Prices? =
        prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString(Prices.serializer(), it) }.getOrNull() }

    /** 함수가 오피넷에서 못 받은 날엔 연료 값이 비어 오므로, 마지막으로 받은 연료 값을 따로 둔다. */
    private fun cachedFuel(grade: FuelGrade): FuelPrice? {
        val saved = prefs.getString(FUEL_KEY, null) ?: return null
        val fuel = runCatching { json.decodeFromString(Fuel.serializer(), saved) }.getOrNull() ?: return null
        val day = (if (grade == FuelGrade.DIESEL) fuel.diesel else fuel.gasoline) ?: return null
        return FuelPrice(day.wonPerLiter, isLive = false, date = day.date)
    }

    private fun save(prices: Prices) {
        val edit = prefs.edit().putString(KEY, json.encodeToString(Prices.serializer(), prices))
        prices.fuel?.let { edit.putString(FUEL_KEY, json.encodeToString(Fuel.serializer(), it)) }
        edit.apply()
    }

    private companion object {
        const val KEY = "latest"
        const val FUEL_KEY = "latest_fuel"

        /** 한 번도 받지 못했을 때만 쓰는 값. 출발 화면에서 고칠 수 있다. */
        const val DEFAULT_WON_PER_LITER = 1_700.0

        /** 환경부 공용 급속(100kW 이상) 2022-09 고시 요금. 한 번도 받지 못했을 때만 쓴다. */
        val BUILT_IN_FAST = ChargePrice(347.2, "환경부 공용 급속(100kW 이상)", "2022-09-01", isLive = false)

        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class DayPrice(val wonPerLiter: Double, val date: String? = null)

@Serializable
private data class Fuel(val gasoline: DayPrice? = null, val diesel: DayPrice? = null)

@Serializable
private data class Tariff(val wonPerKwh: Double, val label: String, val effectiveDate: String)

@Serializable
private data class Prices(val fuel: Fuel? = null, val publicFast: Tariff? = null)

private interface PricesApi {
    @GET("api/prices")
    suspend fun prices(): Prices
}
