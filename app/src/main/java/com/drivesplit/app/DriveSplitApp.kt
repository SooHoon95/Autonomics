package com.drivesplit.app

import android.app.Application
import com.drivesplit.app.data.AppDatabase
import com.drivesplit.app.data.DemoPlaceNames
import com.drivesplit.app.data.FuelPriceRepository
import com.drivesplit.app.data.KakaoPlaceNames
import com.drivesplit.app.data.PlaceNames
import com.drivesplit.app.trip.TripSession
import com.drivesplit.app.vehicle.SimulatedVehicleSource
import kotlinx.coroutines.MainScope

/** 수동 DI 컨테이너. 의존성이 몇 개 안 돼서 Hilt 대신 여기서 직접 만든다. */
class AppContainer(app: Application) {
    // TODO: Pleos Vehicle SDK 승인 후 실차 데이터 소스로 교체
    val vehicle = SimulatedVehicleSource()
    val tripSession = TripSession(vehicle, MainScope())
    val database = AppDatabase.create(app)
    val fuelPrices = FuelPriceRepository(BuildConfig.OPINET_KEY)
    val placeNames = PlaceNames(
        listOfNotNull(
            BuildConfig.KAKAO_REST_KEY.takeIf { it.isNotBlank() }?.let(::KakaoPlaceNames),
            DemoPlaceNames,
        ),
    )
}

class DriveSplitApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
