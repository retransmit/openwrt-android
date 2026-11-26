package dev.openwrtmanager.app.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class GraphMathTest {
    @Test
    fun scale_top_rounds_up_to_a_tidy_number() {
        // Light traffic gets a small scale instead of a flat line along the bottom.
        assertEquals(100_000L, niceScaleTop(0))
        assertEquals(200_000L, niceScaleTop(109_000))
        assertEquals(500_000L, niceScaleTop(400_000))
        assertEquals(1_000_000L, niceScaleTop(800_000))
        assertEquals(2_000_000L, niceScaleTop(1_300_000))
        assertEquals(5_000_000L, niceScaleTop(4_200_000))
        assertEquals(10_000_000L, niceScaleTop(5_100_000))
        assertEquals(20_000_000L, niceScaleTop(19_000_000))
        assertEquals(500_000_000L, niceScaleTop(230_000_000))
        assertEquals(1_000_000_000L, niceScaleTop(1_000_000_000))
    }

    @Test
    fun scale_labels_are_short() {
        assertEquals("0", axisLabel(0))
        assertEquals("500k", axisLabel(500_000))
        assertEquals("1M", axisLabel(1_000_000))
        assertEquals("2.5M", axisLabel(2_500_000))
        assertEquals("50M", axisLabel(50_000_000))
        assertEquals("1G", axisLabel(1_000_000_000))
    }

    @Test
    fun a_touch_picks_the_nearest_sample_and_stays_in_range() {
        // 60 slots across 590 px: one slot every 10 px, newest sample at the right edge.
        assertEquals(9, sampleAt(x = 590f, width = 590f, slots = 60, count = 10))
        assertEquals(8, sampleAt(x = 582f, width = 590f, slots = 60, count = 10))
        assertEquals(0, sampleAt(x = 0f, width = 590f, slots = 60, count = 10))
        assertEquals(0, sampleAt(x = 400f, width = 590f, slots = 60, count = 10))
        assertEquals(59, sampleAt(x = 600f, width = 590f, slots = 60, count = 60))
        assertEquals(-1, sampleAt(x = 300f, width = 590f, slots = 60, count = 0))
    }

    @Test
    fun age_reads_as_time_ago() {
        assertEquals("now", ageLabel(samplesBack = 0, sampleMs = 2_000))
        assertEquals("6 s ago", ageLabel(samplesBack = 3, sampleMs = 2_000))
        assertEquals("1 min 10 s ago", ageLabel(samplesBack = 35, sampleMs = 2_000))
        assertEquals("2 min ago", ageLabel(samplesBack = 60, sampleMs = 2_000))
    }
}
