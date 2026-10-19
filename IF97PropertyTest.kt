package com.example.boilerefficiencypro

import com.hummeling.if97.IF97
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression checks against published IAPWS-IF97 reference examples.
 * Engineering units: bar absolute, degrees Celsius, kJ/kg.
 */
class IF97PropertyTest {
    private val if97 = IF97(IF97.UnitSystem.ENGINEERING)

    @Test
    fun region1EnthalpyMatchesPublishedReferencePoint() {
        // IAPWS-IF97 Region 1 example: T=300 K, p=3 MPa, h=115.331273 kJ/kg.
        // Engineering units convert to T=26.85 °C and p=30 bar.
        val h = if97.specificEnthalpyPT(30.0, 26.85)
        assertEquals(115.331273, h, 0.02)
    }

    @Test
    fun saturationTemperatureNearAtmosphericPressureIsPhysical() {
        val tsat = if97.saturationTemperatureP(1.01325)
        assertEquals(100.0, tsat, 0.03)
    }

    @Test
    fun superheatedSteamReferenceStateReturnsPositiveEnthalpy() {
        // 30 bar abs and 400 °C is a valid superheated-steam state.
        val h = if97.specificEnthalpyPT(30.0, 400.0)
        assertTrue("Enthalpy must be a finite positive value", h.isFinite() && h > 2500.0)
    }
}
