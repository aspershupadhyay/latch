package io.github.aspershupadhyay.latch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictedSettingsTest {
    @Test
    fun recognisesBrandsTheOwnerTestedOn() {
        assertEquals(RestrictedSettings.Brand.XIAOMI, RestrictedSettings.brand("Xiaomi"))
        assertEquals(RestrictedSettings.Brand.XIAOMI, RestrictedSettings.brand(" Redmi "))
        assertEquals(RestrictedSettings.Brand.OPPO_FAMILY, RestrictedSettings.brand("realme"))
        assertEquals(RestrictedSettings.Brand.SAMSUNG, RestrictedSettings.brand("samsung"))
        assertEquals(RestrictedSettings.Brand.OTHER, RestrictedSettings.brand("Google"))
    }

    @Test
    fun stepsStartWithTheAttemptThatUnlocksTheMenu() {
        for (maker in listOf("Xiaomi", "realme", "samsung", "Google")) {
            val steps = RestrictedSettings.steps(maker)
            assertEquals(4, steps.size)
            assertTrue(steps.first().contains("try to switch Latch on once"))
            assertTrue(steps[2].contains("Allow restricted settings"))
        }
        assertTrue(RestrictedSettings.steps("Xiaomi")[2].contains("Manage apps"))
    }
}
