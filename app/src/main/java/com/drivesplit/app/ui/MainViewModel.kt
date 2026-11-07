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
import com.drivesplit.domain.VehicleInfo
import com.drivesplit.domain.VehicleKind
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
    /** 저장된 차량 정보에서 채운다. */
    val kind: VehicleKind = VehicleKind.GASOLINE,
    /** 공인 연비(km/L) 또는 전비(km/kWh). */
    val efficiency: String = DEFAULT_FUEL_EFFICIENCY,
    /** 리터당 또는 kWh당 가격. */
    val pricePerUnit: String = DEFAULT_FUEL_PRICE,
    val priceIsLive: Boolean = false,
    /** 단가의 출처와 기준일. 출발 화면에 그대로 보여준다(예: "오피넷 전국 평균 · 11월 7일"). */
    val priceBasis: String = "",
    val driverPays: Boolean = true,
    /** 운전자 토스 아이디 또는 toss.me 링크. */
    val tossId: String = "",
    /** 운전자 카카오페이 송금 링크(qr.kakaopay.com/…) 또는 코드. */
    val kakaoPay: String = "",
) {
    /** 입력했는데 알아볼 수 없는 송금 링크. 화면에 형식을 다시 확인하라고 알린다. */
    val tossInvalid: Boolean get() = tossId.isNotBlank() && PaymentLinks.tossIdOf(tossId) == null
    val kakaoPayInvalid: Boolean get() = kakaoPay.isNotBlank() && PaymentLinks.kakaoPayCodeOf(kakaoPay) == null

    val powertrain: Powertrain get() = kind.powertrain

    val isValid: Boolean
        get() = driverName.isNotBlank() &&
            (mode == TripMode.SOLO || riders.all { it.isNotBlank() }) &&
            (efficiency.toDoubleOrNull() ?: 0.0) > 0 &&
            (pricePerUnit.toDoubleOrNull() ?: 0.0) > 0

    companion object {
        const val DEFAULT_FUEL_EFFICIENCY = "12.0"
        const val DEFAULT_FUEL_PRICE = "1700"
    }
}

/** 차량 정보 화면의 입력값. 처음 실행이면 저장하기 전까지 닫을 수 없다. */
data class VehicleForm(
    val kind: VehicleKind? = null,
    val efficiency: String = "",
    /** 차에서 짐작한 차종. 화면에 "차에서 확인했어요"로 알려준다. */
    val detected: VehicleKind? = null,
    val firstRun: Boolean = false,
) {
    val isValid: Boolean get() = kind != null && (efficiency.toDoubleOrNull() ?: 0.0) > 0

    companion object {
        /** 공인 효율을 모를 때 미리 채워 두는 대표값(중형차 기준). */
        fun defaultEfficiency(kind: VehicleKind): String = when (kind) {
            VehicleKind.GASOLINE -> "12.0"
            VehicleKind.DIESEL -> "14.5"
            VehicleKind.HYBRID -> "18.0"
            VehicleKind.ELECTRIC -> "5.0"
        }
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

    /** 저장된 차량 정보. 아직 입력하지 않았으면 null. */
    private val _vehicle = MutableStateFlow<VehicleInfo?>(null)
    val vehicle: StateFlow<VehicleInfo?> = _vehicle

    /** 값이 있으면 차량 정보 화면을 띄운다. */
    private val _vehicleForm = MutableStateFlow<VehicleForm?>(null)
    val vehicleForm: StateFlow<VehicleForm?> = _vehicleForm

    init {
        viewModelScope.launch {
            val saved = container.vehicleInfo.load()
            if (saved != null) {
                applyVehicle(saved)
            } else {
                // 처음 실행: 차가 알려주는 값으로 차종을 짐작해 미리 골라 둔다.
                val detected = container.vehicle.detectKind()
                _vehicleForm.value = VehicleForm(
                    kind = detected,
                    efficiency = detected?.let(VehicleForm::defaultEfficiency).orEmpty(),
                    detected = detected,
                    firstRun = true,
                )
            }
        }
    }

    fun openVehicleSetup() {
        val saved = _vehicle.value
        viewModelScope.launch {
            val detected = container.vehicle.detectKind()
            _vehicleForm.value = VehicleForm(
                kind = saved?.kind ?: detected,
                efficiency = saved?.efficiencyKmPerUnit?.toString() ?: detected?.let(VehicleForm::defaultEfficiency).orEmpty(),
                detected = detected,
                firstRun = saved == null,
            )
        }
    }

    fun updateVehicleForm(transform: (VehicleForm) -> VehicleForm) = _vehicleForm.update { it?.let(transform) }

    /** 차종을 바꾸면 그 차종의 대표 효율로 다시 채운다. 사용자가 직접 고친 값은 차종이 같을 때만 남긴다. */
    fun selectVehicleKind(kind: VehicleKind) = updateVehicleForm {
        if (it.kind == kind) it else it.copy(kind = kind, efficiency = VehicleForm.defaultEfficiency(kind))
    }

    fun closeVehicleSetup() = _vehicleForm.update { if (it?.firstRun == true) it else null }

    fun saveVehicle() {
        val f = _vehicleForm.value ?: return
        val kind = f.kind ?: return
        val efficiency = f.efficiency.toDoubleOrNull()?.takeIf { it > 0 } ?: return
        val info = VehicleInfo(kind, efficiency)
        _vehicleForm.value = null
        viewModelScope.launch {
            container.vehicleInfo.save(info)
            applyVehicle(info)
        }
    }

    /** 저장된 차량으로 출발 화면 기본값(효율, 단가)을 채운다. */
    private suspend fun applyVehicle(info: VehicleInfo) {
        _vehicle.value = info
        _form.update { it.copy(kind = info.kind, efficiency = info.efficiencyKmPerUnit.toString()) }
        refreshPrice()
    }

    /** 앱을 켤 때와 출발 화면을 열 때마다 오늘 단가를 다시 가져온다(하루에 한 번만 실제로 묻는다). */
    private suspend fun refreshPrice() {
        val kind = _vehicle.value?.kind ?: return
        val grade = kind.fuelGrade
        if (grade == null) {
            val charge = container.chargePrices.publicFast()
            _form.update {
                it.copy(
                    pricePerUnit = formatPrice(charge.wonPerKwh),
                    priceIsLive = charge.isLive,
                    priceBasis = "${charge.label} · ${charge.effectiveDate} 시행",
                )
            }
        } else {
            val price = container.fuelPrices.average(grade)
            _form.update {
                it.copy(
                    pricePerUnit = price.wonPerLiter.roundToInt().toString(),
                    priceIsLive = price.isLive,
                    priceBasis = when (val date = price.date) {
                        null -> "기본값 · 오피넷에서 받지 못했어요"
                        else -> "오피넷 전국 평균 · ${prettyDate(date)} 기준" + if (price.isLive) "" else "(오늘 값을 못 받았어요)"
                    },
                )
            }
        }
    }

    fun openSetup(mode: TripMode) {
        _form.update { it.copy(mode = mode) }
        _showSetup.value = true
        viewModelScope.launch { refreshPrice() }
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
                profile = VehicleInfo(f.kind, f.efficiency.toDouble()).toProfile(),
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

        /** "20261107" → "11월 7일" */
        fun prettyDate(yyyymmdd: String): String =
            if (yyyymmdd.length == 8) "${yyyymmdd.substring(4, 6).toInt()}월 ${yyyymmdd.substring(6, 8).toInt()}일" else yyyymmdd

        /** 347.2 → "347.2", 1800.0 → "1800" */
        fun formatPrice(won: Double): String = if (won % 1.0 == 0.0) won.toLong().toString() else won.toString()
    }
}
