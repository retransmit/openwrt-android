package dev.openwrtmanager.app.ui.kit

import org.junit.Assert.assertEquals
import org.junit.Test

class KitMathTest {
    @Test
    fun usage_fraction_is_clamped() {
        assertEquals(0f, usageFraction(null))
        assertEquals(0.61f, usageFraction(61), 0.0001f)
        assertEquals(0f, usageFraction(-5))
        assertEquals(1f, usageFraction(140))
    }
}
