package dev.openwrtmanager.app.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraColorsTest {
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun light_mode_status_colours_are_readable_as_small_text() {
        // The lightest surfaces small status text sits on in light mode.
        val surface = Color(0xFFF8F9FF)
        val extra = extraColorsFor(lightColorScheme(), dark = false)
        assertTrue("online green ${contrast(extra.online, surface)}", contrast(extra.online, surface) >= 4.5)
        assertTrue("warning amber ${contrast(extra.warning, surface)}", contrast(extra.warning, surface) >= 4.5)
    }
}
