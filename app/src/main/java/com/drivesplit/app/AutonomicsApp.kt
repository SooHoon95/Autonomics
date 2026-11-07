package com.drivesplit.app

import android.app.Application
import com.drivesplit.app.trip.TripService
import com.drivesplit.data.FileActiveTripStore
import com.drivesplit.data.KakaoPlaceNames
import com.drivesplit.data.PlaceNames
import com.drivesplit.data.PrefsVehicleInfoRepository
import com.drivesplit.data.NetlifyPrices
import com.drivesplit.data.RoomTripHistoryRepository
import com.drivesplit.domain.repository.ChargePriceSource
import com.drivesplit.domain.repository.FuelPriceSource
import com.drivesplit.domain.repository.PlaceNameResolver
import com.drivesplit.domain.repository.PlaceRecorder
import com.drivesplit.domain.repository.TripHistoryRepository
import com.drivesplit.domain.repository.VehicleDataSource
import com.drivesplit.domain.repository.VehicleInfoRepository
import com.drivesplit.domain.trip.TripSession
import com.drivesplit.pleos.NaviPlaceNames
import com.drivesplit.pleos.PleosVehicleSource
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * 수동 DI 컨테이너. 의존성이 몇 개 안 돼서 Hilt 대신 여기서 직접 만든다.
 * 화면은 도메인 인터페이스만 보고, 구현(:data, :pleos)은 여기서만 고른다.
 */
class AppContainer(app: Application) {
    private val naviPlaces = NaviPlaceNames()

    val vehicle: VehicleDataSource = PleosVehicleSource(app, naviPlaces)
    val tripSession = TripSession(vehicle, MainScope(), FileActiveTripStore(app))
    val tripHistory: TripHistoryRepository = RoomTripHistoryRepository(app)
    private val prices = NetlifyPrices(app, BuildConfig.SITE_URL)
    val fuelPrices: FuelPriceSource = prices
    val chargePrices: ChargePriceSource = prices
    val vehicleInfo: VehicleInfoRepository = PrefsVehicleInfoRepository(app)
    val placeRecorder: PlaceRecorder = naviPlaces
    val placeNames: PlaceNameResolver = PlaceNames(
        listOfNotNull(naviPlaces, BuildConfig.KAKAO_REST_KEY.takeIf { it.isNotBlank() }?.let(::KakaoPlaceNames)),
    )
}

class AutonomicsApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 시동을 껐다 켜서 앱이 다시 떴다면(화면을 열었든, 부팅 알림이든) 진행 중이던 여행을 이어 간다.
        resumeTrip()
    }

    fun resumeTrip(onDone: () -> Unit = {}) {
        MainScope().launch {
            if (container.tripSession.resume()) TripService.start(this@AutonomicsApp)
            onDone()
        }
    }
}
