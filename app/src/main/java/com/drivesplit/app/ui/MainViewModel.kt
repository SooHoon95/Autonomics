package com.drivesplit.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drivesplit.app.BuildConfig
import com.drivesplit.app.DriveSplitApp
import com.drivesplit.app.data.FuelType
import com.drivesplit.app.data.ShareEntity
import com.drivesplit.app.data.TripEntity
import com.drivesplit.app.trip.TripMode
import com.drivesplit.app.trip.TripRecord
import com.drivesplit.app.trip.TripService
import com.drivesplit.app.trip.TripSetup
import com.drivesplit.app.trip.TripState
import com.drivesplit.core.GeoPoint
import com.drivesplit.core.Passenger
import com.drivesplit.core.Settlement
import com.drivesplit.core.SettlementOptions
import com.drivesplit.core.ShareLink
import com.drivesplit.core.VehicleProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

data class SetupForm(
    val mode: TripMode = TripMode.SPLIT,
    val driverName: String = "운전자",
    val riders: List<String> = listOf("동승자 1", "동승자 2"),
    val efficiencyKmPerLiter: String = "12.0",
    val pricePerLiter: String = "1700",
    val priceIsLive: Boolean = false,
    val driverPays: Boolean = true,
    val tossId: String = "",
) {
    val isValid: Boolean
        get() = driverName.isNotBlank() &&
            (mode == TripMode.SOLO || riders.all { it.isNotBlank() }) &&
            (efficiencyKmPerLiter.toDoubleOrNull() ?: 0.0) > 0 &&
            (pricePerLiter.toDoubleOrNull() ?: 0.0) > 0
}

class MainViewModel(private val app: DriveSplitApp) : ViewModel() {

    private val container = app.container
    private val session = container.tripSession

    val trip: StateFlow<TripState> = session.state
    val holdStop: StateFlow<Boolean> = container.vehicle.holdStop
    val recentTrips = container.database.tripDao().recentTrips(limit = 5)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _form = MutableStateFlow(SetupForm())
    val form: StateFlow<SetupForm> = _form

    private val _showSetup = MutableStateFlow(false)
    val showSetup: StateFlow<Boolean> = _showSetup

    init {
        viewModelScope.launch {
            val price = container.fuelPrices.nationalAverage(FuelType.GASOLINE)
            _form.update { it.copy(pricePerLiter = price.wonPerLiter.roundToInt().toString(), priceIsLive = price.isLive) }
        }
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
        val driver = Passenger(DRIVER_ID, f.driverName.trim(), isDriver = true)
        val passengers = when (f.mode) {
            TripMode.SOLO -> listOf(driver)
            TripMode.SPLIT -> listOf(driver) + f.riders.mapIndexed { i, name -> Passenger("rider$i", name.trim()) }
        }
        session.start(
            TripSetup(
                mode = f.mode,
                passengers = passengers,
                profile = VehicleProfile(efficiencyKmPerLiter = f.efficiencyKmPerLiter.toDouble()),
                options = SettlementOptions(pricePerLiter = f.pricePerLiter.toDouble(), driverPays = f.driverPays),
                tossId = f.tossId.trim().ifBlank { null },
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

    fun showDropOff(passengerId: String) = _dropOffNotice.update { passengerId }
    fun dismissDropOff() = _dropOffNotice.update { null }

    /** 한 사람 몫만 담은 정산 링크. 중간 하차 때와 지난 기록에서 개인별로 볼 때 쓴다. */
    fun personUrl(setup: TripSetup, settlement: Settlement, passengerId: String): String? {
        val passenger = setup.passengers.firstOrNull { it.id == passengerId } ?: return null
        return ShareLink.encode(
            BuildConfig.SHARE_BASE_URL,
            ShareLink.payloadForDropOff(settlement, passenger, setup.options.pricePerLiter, setup.tossId),
        )
    }

    /** 최근 기록에서 연 지난 여행. 값이 있으면 홈 대신 그 결과 화면을 보여준다. */
    private val _history = MutableStateFlow<TripState.Finished?>(null)
    val history: StateFlow<TripState.Finished?> = _history

    fun openHistory(tripId: Long) {
        viewModelScope.launch {
            val detail = container.database.tripDao().trip(tripId)?.detailJson ?: return@launch
            _history.value = runCatching { json.decodeFromString(TripRecord.serializer(), detail).toFinished() }.getOrNull()
        }
    }

    fun closeHistory() = _history.update { null }

    fun setHoldStop(hold: Boolean) = container.vehicle.setHoldStop(hold)

    fun finishTrip() {
        _dropOffNotice.value = null
        val finished = session.finish() ?: return
        TripService.stop(app)
        val s = finished.figures.settlement
        val r = finished.figures.report
        viewModelScope.launch {
            container.database.tripDao().insertTripWithShares(
                TripEntity(
                    mode = finished.setup.mode.name,
                    endedAtMs = System.currentTimeMillis(),
                    distanceKm = r.totalKm,
                    liters = r.totalLiters,
                    pricePerLiter = finished.setup.options.pricePerLiter,
                    totalWon = if (finished.setup.mode == TripMode.SOLO) r.costWon else s.totalWon,
                    detailJson = json.encodeToString(TripRecord.serializer(), finished.toRecord()),
                ),
                finished.setup.passengers.map { p ->
                    ShareEntity(tripId = 0, name = p.name, amountWon = s.shares[p.id] ?: 0, isDriver = p.isDriver)
                },
            )
        }
    }

    fun shareUrl(finished: TripState.Finished): String = ShareLink.encode(
        BuildConfig.SHARE_BASE_URL,
        ShareLink.payloadOf(
            finished.figures.settlement,
            finished.setup.passengers,
            finished.setup.options.pricePerLiter,
            finished.setup.tossId,
        ),
    )

    suspend fun placeName(point: GeoPoint): String? = container.placeNames.nameOf(point)

    fun deleteTrip(tripId: Long) {
        viewModelScope.launch { container.database.tripDao().deleteTrip(tripId) }
    }

    fun deleteAllTrips() {
        viewModelScope.launch { container.database.tripDao().deleteAllTrips() }
    }

    fun closeResult() {
        container.vehicle.setHoldStop(false)
        session.reset()
    }

    private companion object {
        const val DRIVER_ID = "driver"
        val json = Json { ignoreUnknownKeys = true }
    }
}
