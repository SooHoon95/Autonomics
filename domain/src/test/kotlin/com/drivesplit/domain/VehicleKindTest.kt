package com.drivesplit.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleKindTest {

    @Test
    fun carReportingBothBatteryAndFuelIsHybrid() {
        assertEquals(VehicleKind.HYBRID, VehicleKind.guess(hasBattery = true, hasFuel = true))
    }

    @Test
    fun batteryOnlyIsElectricAndFuelOnlyIsGasoline() {
        assertEquals(VehicleKind.ELECTRIC, VehicleKind.guess(hasBattery = true, hasFuel = false))
        assertEquals(VehicleKind.GASOLINE, VehicleKind.guess(hasBattery = false, hasFuel = true))
        assertNull(VehicleKind.guess(hasBattery = false, hasFuel = false))
    }

    @Test
    fun hybridIsPricedByFuelNotBattery() {
        val hybrid = VehicleInfo(VehicleKind.HYBRID, efficiencyKmPerUnit = 18.0)

        assertEquals(Powertrain.FUEL, hybrid.toProfile().powertrain)
        assertEquals(FuelGrade.GASOLINE, VehicleKind.HYBRID.fuelGrade)
        assertEquals(FuelGrade.DIESEL, VehicleKind.DIESEL.fuelGrade)
    }
}
