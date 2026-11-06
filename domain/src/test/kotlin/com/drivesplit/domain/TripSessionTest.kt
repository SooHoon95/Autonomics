package com.drivesplit.domain

import com.drivesplit.domain.repository.ActiveTripStore
import com.drivesplit.domain.repository.VehicleDataSource
import com.drivesplit.domain.trip.ActiveTrip
import com.drivesplit.domain.trip.TripMode
import com.drivesplit.domain.trip.TripSession
import com.drivesplit.domain.trip.TripSetup
import com.drivesplit.domain.trip.TripState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TripSessionTest {

    private val setup = TripSetup(
        mode = TripMode.SPLIT,
        passengers = listOf(Passenger("a", "운전자", isDriver = true), Passenger("b", "민수")),
        profile = VehicleProfile(efficiencyKmPerUnit = 10.0),
        options = SettlementOptions(pricePerUnit = 1_000.0),
        tossId = null,
    )

    @Test
    fun tripSurvivesAppRestart() = runTest {
        val store = MemoryStore()
        val before = TestScope(testScheduler)
        TripSession(TenSecondsAt60(), before, store).start(setup)
        advanceUntilIdle()
        before.cancel() // 시동 OFF: 프로세스가 죽는다

        val after = TestScope(testScheduler)
        val session = TripSession(TenSecondsAt60(), after, store)
        assertTrue(session.resume())
        advanceUntilIdle()

        val active = session.state.value as TripState.Active
        // 끄기 전 10초 + 켠 뒤 10초를 60km/h로 달렸다.
        assertEquals(20.0 / 60, active.live.settlement.totalDistanceKm, 0.02)
        after.cancel()
    }

    @Test
    fun resumingTwiceAtOnceContinuesOnlyOnce() = runTest {
        val store = MemoryStore()
        val before = TestScope(testScheduler)
        TripSession(TenSecondsAt60(), before, store).start(setup)
        advanceUntilIdle()
        before.cancel()

        val after = TestScope(testScheduler)
        val vehicle = CountingVehicle()
        val session = TripSession(vehicle, after, store)
        // 앱 실행과 부팅 알림이 동시에 이어 붙이려 한다.
        val first = async { session.resume() }
        val second = async { session.resume() }

        assertEquals(1, listOf(first.await(), second.await()).count { it })
        advanceUntilIdle()
        assertEquals(1, vehicle.subscriptions)
        after.cancel()
    }

    @Test
    fun passengerAddedMidTripPaysOnlyFromBoarding() = runTest {
        val store = MemoryStore()
        val scope = TestScope(testScheduler)
        val session = TripSession(ParkedTenSeconds(), scope, store)
        session.start(setup)
        advanceUntilIdle()

        val id = session.addPassenger("철규")
        advanceUntilIdle()

        val active = session.state.value as TripState.Active
        assertEquals("철규", active.setup.passengers.single { it.id == id }.name)
        assertTrue(id in active.occupantIds)
        // 태우기 전 구간(정차 10초 공회전)은 원래 두 사람이 나눈다.
        assertEquals(0L, active.live.settlement.shares[id])
        assertTrue(store.saved!!.setup.passengers.any { it.id == id })
        scope.cancel()
    }

    @Test
    fun finishedTripIsNotResumed() = runTest {
        val store = MemoryStore()
        val scope = TestScope(testScheduler)
        val session = TripSession(TenSecondsAt60(), scope, store)
        session.start(setup)
        advanceUntilIdle()
        session.finish()
        advanceUntilIdle()

        assertNull(store.saved)
        assertFalse(TripSession(TenSecondsAt60(), scope, store).resume())
        scope.cancel()
    }

    private class MemoryStore : ActiveTripStore {
        var saved: ActiveTrip? = null
        override suspend fun save(trip: ActiveTrip) { saved = trip }
        override suspend fun load(): ActiveTrip? = saved
        override suspend fun clear() { saved = null }
    }

    /** 정차한 채 10초. 하차·승차 조작은 정차 중에만 된다. */
    private class ParkedTenSeconds : VehicleDataSource {
        override suspend fun detectPowertrain(): Powertrain = Powertrain.FUEL
        override fun samples(): Flow<VehicleSample> = flow {
            for (sec in 0..10) emit(VehicleSample(sec * 1_000L, 0.0))
        }
    }

    private class CountingVehicle : VehicleDataSource {
        var subscriptions = 0
        override suspend fun detectPowertrain(): Powertrain = Powertrain.FUEL
        override fun samples(): Flow<VehicleSample> = flow {
            subscriptions++
            emit(VehicleSample(0, 0.0))
        }
    }

    /** 1초 간격 샘플 11개(10초). 재부팅을 흉내 내려고 시각은 매번 0부터 센다. */
    private class TenSecondsAt60 : VehicleDataSource {
        override suspend fun detectPowertrain(): Powertrain = Powertrain.FUEL
        override fun samples(): Flow<VehicleSample> = flow {
            for (sec in 0..10) emit(VehicleSample(sec * 1_000L, 60.0))
        }
    }
}
