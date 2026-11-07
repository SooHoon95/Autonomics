package com.drivesplit.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drivesplit.app.BuildConfig
import com.drivesplit.app.AutonomicsApp
import com.drivesplit.domain.trip.TripMode
import com.drivesplit.domain.trip.TripRecord
import com.drivesplit.app.trip.TripService
import com.drivesplit.domain.trip.TripSetup
import com.drivesplit.domain.trip.TripState
import com.drivesplit.domain.GeoPoint
import com.drivesplit.domain.Passenger
import com.drivesplit.domain.PaymentLinks
import com.drivesplit.domain.Powertrain
import com.drivesplit.domain.Settlement
import com.drivesplit.domain.SettlementOptions
import com.drivesplit.domain.ShareLink
import com.drivesplit.domain.VehicleProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class SetupForm(
    val mode: TripMode = TripMode.SPLIT,
    val driverName: String = "운전자",
    val riders: List<String> = listOf("동승자 1", "동승자 2"),
    /** 차량에서 읽어 정한다. 읽기 전에는 휘발유차로 둔다. */
    val powertrain: Powertrain = Powertrain.FUEL,
    /** 연결된 차에서 동력 방식을 확인했는지. */
    val powertrainDetected: Boolean = false,
    /** 공인 연비(km/L) 또는 전비(km/kWh). */
    val efficiency: String = DEFAULT_FUEL_EFFICIENCY,
    /** 리터당 또는 kWh당 가격. */
    val pricePerUnit: String = DEFAULT_FUEL_PRICE,
    val priceIsLive: Boolean = false,
    val driverPays: Boolean = true,
    /** 운전자 토스 아이디 또는 toss.me 링크. */
    val tossId: String = "",
    /** 운전자 카카오페이 송금 링크(qr.kakaopay.com/…) 또는 코드. */
    val kakaoPay: String = "",
) {
    /** 입력했는데 알아볼 수 없는 송금 링크. 화면에 형식을 다시 확인하라고 알린다. */
    val tossInvalid: Boolean get() = tossId.isNotBlank() && PaymentLinks.tossIdOf(tossId) == null
    val kakaoPayInvalid: Boolean get() = kakaoPay.isNotBlank() && PaymentLinks.kakaoPayCodeOf(kakaoPay) == null

    val isValid: Boolean
        get() = driverName.isNotBlank() &&
            (mode == TripMode.SOLO || riders.all { it.isNotBlank() }) &&
            (efficiency.toDoubleOrNull() ?: 0.0) > 0 &&
            (pricePerUnit.toDoubleOrNull() ?: 0.0) > 0

    companion object {
        const val DEFAULT_FUEL_EFFICIENCY = "12.0"
        const val DEFAULT_FUEL_PRICE = "1700"

        /** 중형 전기 SUV(아이오닉 5 롱레인지 2WD) 복합 전비. */
        const val DEFAULT_ELECTRIC_EFFICIENCY = "5.0"

        /** 환경부 공용 급속충전(100kW 이상) 요금. */
        const val DEFAULT_ELECTRIC_PRICE = "347"
    }
}

class MainViewModel(private val app: AutonomicsApp) : ViewModel() {

    private val container = app.container
    private val session = container.tripSession

    val trip: StateFlow<TripState> = session.state
    val recentTrips = container.tripHistory.recentTrips(limit = 5)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _form = MutableStateFlow(SetupForm())
    val form: StateFlow<SetupForm> = _form

    private val _showSetup = MutableStateFlow(false)
    val showSetup: StateFlow<Boolean> = _showSetup

    init {
        viewModelScope.launch {
            when (container.vehicle.detectPowertrain()) {
                Powertrain.ELECTRIC -> _form.update {
                    it.copy(
                        powertrain = Powertrain.ELECTRIC,
                        powertrainDetected = true,
                        efficiency = SetupForm.DEFAULT_ELECTRIC_EFFICIENCY,
                        pricePerUnit = SetupForm.DEFAULT_ELECTRIC_PRICE,
                        priceIsLive = false,
                    )
                }
                Powertrain.FUEL -> {
                    _form.update { it.copy(powertrainDetected = true) }
                    loadFuelPrice()
                }
                null -> loadFuelPrice()
            }
        }
    }

    private suspend fun loadFuelPrice() {
        val price = container.fuelPrices.gasolineAverage()
        _form.update { it.copy(pricePerUnit = price.wonPerLiter.roundToInt().toString(), priceIsLive = price.isLive) }
    }

    fun openSetup(mode: TripMode) {
        _form.update { it.copy(mode = mode) }
        _showSetup.value = true
    }
    fun closeSetup() = _showSetup.update { false }
    fun updateForm(transform: (SetupForm) -> SetupForm) = _form.update(transform)

    fun startTrip() {
        val f = _form.value
        if (!f.isValid) return
        container.placeRecorder.clear()
        val driver = Passenger(DRIVER_ID, f.driverName.trim(), isDriver = true)
        val passengers = when (f.mode) {
            TripMode.SOLO -> listOf(driver)
            TripMode.SPLIT -> listOf(driver) + f.riders.mapIndexed { i, name -> Passenger("rider$i", name.trim()) }
        }
        session.start(
            TripSetup(
                mode = f.mode,
                passengers = passengers,
                profile = VehicleProfile.of(f.powertrain, f.efficiency.toDouble()),
                options = SettlementOptions(pricePerUnit = f.pricePerUnit.toDouble(), driverPays = f.driverPays),
                tossId = PaymentLinks.tossIdOf(f.tossId),
                kakaoPayCode = PaymentLinks.kakaoPayCodeOf(f.kakaoPay),
            ),
        )
        TripService.start(app)
        _showSetup.value = false
    }

    /** 방금 내린 사람. 값이 있으면 주행 화면에 그 사람의 정산 QR을 띄운다. */
    private val _dropOffNotice = MutableStateFlow<String?>(null)
    val dropOffNotice: StateFlow<String?> = _dropOffNotice

    fun setOccupants(ids: Set<String>) {
        val before = (trip.value as? TripState.Active)?.occupantIds ?: return
        session.setOccupants(ids)
        val after = (trip.value as? TripState.Active)?.occupantIds ?: return
        (before - after).firstOrNull()?.let { _dropOffNotice.value = it }
        // 잘못 눌러서 다시 태운 경우 띄워둔 정산서를 닫는다.
        if (_dropOffNotice.value in after) _dropOffNotice.value = null
    }

    /** 정차 중에 새로 탄 사람. 태웠으면 true. */
    fun addPassenger(name: String): Boolean = session.addPassenger(name) != null

    fun showDropOff(passengerId: String) = _dropOffNotice.update { passengerId }
    fun dismissDropOff() = _dropOffNotice.update { null }

    /** 한 사람 몫만 담은 정산 링크. 중간 하차 때와 지난 기록에서 개인별로 볼 때 쓴다. */
    fun personUrl(setup: TripSetup, settlement: Settlement, passengerId: String): String? {
        val passenger = setup.passengers.firstOrNull { it.id == passengerId } ?: return null
        return ShareLink.encode(
            BuildConfig.SHARE_BASE_URL,
            ShareLink.payloadForDropOff(
                settlement, passenger, setup.options.pricePerUnit, setup.tossId, setup.profile.powertrain, setup.kakaoPayCode,
            ),
        )
    }

    /** 최근 기록에서 연 지난 여행. 값이 있으면 홈 대신 그 결과 화면을 보여준다. */
    private val _history = MutableStateFlow<TripState.Finished?>(null)
    val history: StateFlow<TripState.Finished?> = _history

    fun openHistory(tripId: Long) {
        viewModelScope.launch {
            val record = container.tripHistory.load(tripId) ?: return@launch
            container.placeRecorder.restore(record.places)
            _history.value = record.toFinished()
        }
    }

    fun closeHistory() = _history.update { null }

    fun finishTrip() {
        _dropOffNotice.value = null
        val finished = session.finish() ?: return
        TripService.stop(app)
        val record = finished.toRecord().copy(places = container.placeRecorder.snapshot())
        viewModelScope.launch { container.tripHistory.save(record, endedAtMs = System.currentTimeMillis()) }
    }

    fun shareUrl(finished: TripState.Finished): String = ShareLink.encode(
        BuildConfig.SHARE_BASE_URL,
        ShareLink.payloadOf(
            finished.figures.settlement,
            finished.setup.passengers,
            finished.setup.options.pricePerUnit,
            finished.setup.tossId,
            finished.setup.profile.powertrain,
            finished.setup.kakaoPayCode,
        ),
    )

    suspend fun placeName(point: GeoPoint): String? = container.placeNames.nameOf(point)

    fun deleteTrip(tripId: Long) {
        viewModelScope.launch { container.tripHistory.delete(tripId) }
    }

    fun deleteAllTrips() {
        viewModelScope.launch { container.tripHistory.deleteAll() }
    }

    fun closeResult() = session.reset()

    private companion object {
        const val DRIVER_ID = "driver"
    }
}
