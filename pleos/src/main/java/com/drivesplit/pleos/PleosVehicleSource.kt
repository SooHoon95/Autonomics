package com.drivesplit.pleos

import android.Manifest
import android.annotation.SuppressLint
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.EvBatteryLevelListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import com.drivesplit.domain.GeoPoint
import com.drivesplit.domain.Powertrain
import com.drivesplit.domain.VehicleKind
import com.drivesplit.domain.VehicleSample
import com.drivesplit.domain.repository.VehicleDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Pleos Vehicle SDK로 실제 차량 값을 받아 1초마다 한 샘플로 묶어 내보낸다.
 *
 * - 누적 주행거리, 기어: Pleos Odometer / Gear
 * - 남은 에너지: 전기차는 Pleos EvBattery(Wh), 휘발유차는 Pleos Fuel(%) × 탱크 용량
 * - 차속, 탱크 용량: Pleos SDK에 없어서 AAOS 표준 CarPropertyManager
 * - 위치: 안드로이드 LocationManager(GPS). GPS가 없으면 Pleos NaviHelper 위치
 * - 지명: Pleos NaviHelper가 알려주는 현재 위치 주소
 */
class PleosVehicleSource(
    private val context: Context,
    private val places: NaviPlaceNames,
) : VehicleDataSource {

    /** 전기차 배터리 값이 오면 전기차로 본다. 차량 서비스가 답하지 않으면 null. */
    override suspend fun detectKind(): VehicleKind? = withContext(Dispatchers.IO) {
        val vehicle = Vehicle(context).apply { initialize() }
        try {
            withTimeoutOrNull(DETECT_TIMEOUT_MS) {
                val batteryWh = suspendCancellableCoroutine { cont ->
                    vehicle.getEvBattery().getEvBatteryLevel({ cont.resume(it) }, { cont.resume(null) })
                }
                val fuelPercent = suspendCancellableCoroutine { cont ->
                    vehicle.getFuel().getFuelPercent({ cont.resume(it) }, { cont.resume(null) })
                }
                VehicleKind.guess(hasBattery = batteryWh != null && batteryWh > 0f, hasFuel = fuelPercent != null)
            }
        } finally {
            vehicle.release()
        }
    }

    @SuppressLint("MissingPermission")
    override fun samples(powertrain: Powertrain): Flow<VehicleSample> = callbackFlow {
        // 각 값은 서로 다른 스레드에서 따로 들어온다. 최신 값만 들고 있다가 정해진 주기로 묶는다.
        val latest = Latest()

        val vehicle = Vehicle(context).apply { initialize() }
        val odometer = object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(km: Float?) { km?.let { latest.odometerKm = it.toDouble() } }
            override fun onFailed(e: Exception) = logFailure(e)
        }
        val battery = object : EvBatteryLevelListener {
            override fun onEvBatteryLevelUpdated(wh: Float?) { wh?.let { latest.batteryKwh = it / 1000.0 } }
            override fun onFailed(e: Exception) = logFailure(e)
        }
        val fuel = object : FuelPercentListener {
            override fun onFuelPercentUpdated(percent: Int?) { percent?.let { latest.fuelPercent = it } }
            override fun onFailed(e: Exception) = logFailure(e)
        }
        val gear = object : GearSelectionListener {
            override fun onSelected(g: VehicleGear?) { latest.parked = g == VehicleGear.GEAR_PARK }
            override fun onFailed(e: Exception) = logFailure(e)
        }
        vehicle.getOdometer().registerDistanceDriven(odometer)
        vehicle.getEvBattery().registerEvBatteryLevel(battery)
        vehicle.getFuel().registerFuelPercent(fuel)
        vehicle.getGear().registerGearSelection(gear)
        // 등록 알림은 값이 바뀔 때만 오므로 시작 값은 한 번 직접 읽어 둔다.
        vehicle.getOdometer().getCurrentDistanceDriven({ km -> km?.let { latest.odometerKm = it.toDouble() } }, ::logFailure)
        vehicle.getEvBattery().getEvBatteryLevel({ wh -> wh?.let { latest.batteryKwh = it / 1000.0 } }, ::logFailure)
        vehicle.getFuel().getFuelPercent({ percent -> percent?.let { latest.fuelPercent = it } }, ::logFailure)
        vehicle.getGear().getCurrentGearSelectionState({ latest.parked = it == VehicleGear.GEAR_PARK }, ::logFailure)

        val car = Car.createCar(context)
        val properties = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                (value.value as? Float)?.let { latest.speedKmh = it * MPS_TO_KMH }
            }

            override fun onErrorEvent(propertyId: Int, areaId: Int) {}
        }
        if (properties != null && granted(Car.PERMISSION_SPEED)) {
            properties.registerCallback(speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
        }
        val tankLiters = runCatching {
            properties?.getFloatProperty(VehiclePropertyIds.INFO_FUEL_CAPACITY, 0)?.div(1000.0)
        }.getOrNull()?.takeIf { it > 0 }

        val navi = NaviHelper(context)
        navi.addListener(object : NaviHelperEventListener {
            override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
                val point = GeoPoint(info.latitude, info.longitude)
                latest.naviLocation = point
                info.address?.let {
                    places.record(point, it)
                    latest.region = regionOf(it)
                }
            }

            override fun onError(error: NaviErrorCode) {
                Log.w(TAG, "NaviHelper error: $error")
            }
        })
        runCatching { navi.initialize() }.onFailure { Log.w(TAG, "NaviHelper unavailable", it) }

        val locations = context.getSystemService(LocationManager::class.java)
        val locationListener = LocationListener { location: Location ->
            latest.location = GeoPoint(location.latitude, location.longitude)
        }
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            val provider = if (locations.hasProvider(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER
            locations.requestLocationUpdates(provider, SAMPLE_MS, 0f, locationListener, Looper.getMainLooper())
        }

        launch {
            while (true) {
                runCatching { navi.getCurrentLocationInfo() }
                // 벽시계는 시간 동기화로 튈 수 있어서, 샘플 간격은 부팅 후 경과 시간으로 잰다.
                send(latest.toSample(SystemClock.elapsedRealtime(), tankLiters, powertrain))
                delay(SAMPLE_MS)
            }
        }

        awaitClose {
            locations.removeUpdates(locationListener)
            runCatching { navi.release() }
            properties?.unregisterCallback(speedCallback)
            car?.disconnect()
            vehicle.getOdometer().unregisterDistanceDriven(odometer)
            vehicle.getEvBattery().unregisterEvBatteryLevel(battery)
            vehicle.getFuel().unregisterFuelPercent(fuel)
            vehicle.getGear().unregisterGearSelection(gear)
            vehicle.release()
        }
    }.flowOn(Dispatchers.IO) // 차량 서비스 연결과 동기 조회가 메인 스레드를 막지 않게 한다.

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun logFailure(e: Exception) {
        Log.w(TAG, "Vehicle value unavailable", e)
    }

    private class Latest {
        @Volatile var odometerKm: Double? = null
        @Volatile var batteryKwh: Double? = null
        @Volatile var fuelPercent: Int? = null
        @Volatile var parked: Boolean = true
        @Volatile var speedKmh: Double? = null
        @Volatile var location: GeoPoint? = null
        @Volatile var naviLocation: GeoPoint? = null
        @Volatile var region: String? = null

        fun toSample(nowMs: Long, tankLiters: Double?, powertrain: Powertrain): VehicleSample {
            val fuelLiters = fuelPercent?.let { p -> tankLiters?.let { it * p / 100.0 } }
            return VehicleSample(
                timestampMs = nowMs,
                // 차속 센서가 없으면 기어로 판단한다. P가 아니면 달리는 중으로 본다.
                speedKmh = if (parked) 0.0 else speedKmh ?: UNKNOWN_MOVING_KMH,
                // 하이브리드의 배터리는 연료로 채운 완충이라 비용이 아니다. 차종에 맞는 잔량만 보낸다.
                energyLevel = if (powertrain == Powertrain.ELECTRIC) batteryKwh else fuelLiters,
                odometerKm = odometerKm,
                region = region,
                // 지나온 길은 GPS를 우선한다. 에뮬레이터의 내비는 GPS를 따라가지 않고 제자리에 있다.
                location = location ?: naviLocation,
            )
        }
    }

    private companion object {
        const val TAG = "PleosVehicleSource"
        const val SAMPLE_MS = 1_000L
        const val DETECT_TIMEOUT_MS = 3_000L
        const val MPS_TO_KMH = 3.6
        /** 차속을 모를 때 "달리는 중"으로만 쓰는 값. 거리는 주행거리계로 따로 잰다. */
        const val UNKNOWN_MOVING_KMH = 10.0
    }
}
