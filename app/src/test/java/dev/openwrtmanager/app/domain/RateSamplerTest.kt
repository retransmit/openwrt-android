package dev.openwrtmanager.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RateSamplerTest {
    private val s = RateSampler()

    @Test
    fun first_sample_has_no_rate() {
        assertNull(s.sample(Counters(1_000, 500), 0))
    }

    @Test
    fun rate_is_bits_per_second_between_samples() {
        s.sample(Counters(1_000, 500), 0)
        assertEquals(Rate(8_000, 4_000), s.sample(Counters(3_000, 1_500), 2_000))
    }

    @Test
    fun counter_going_backwards_gives_zero_then_recovers() {
        s.sample(Counters(1_000, 500), 0)
        s.sample(Counters(3_000, 1_500), 2_000)
        assertEquals(Rate(0, 0), s.sample(Counters(100, 1_600), 4_000))
        assertEquals(Rate(400, 0), s.sample(Counters(200, 1_600), 6_000))
    }

    @Test
    fun zero_elapsed_gives_zero() {
        s.sample(Counters(200, 1_600), 6_000)
        assertEquals(Rate(0, 0), s.sample(Counters(999, 9_999), 6_000))
    }

    @Test
    fun reset_forgets_previous_sample() {
        s.sample(Counters(1_000, 500), 0)
        s.reset()
        assertNull(s.sample(Counters(5, 5), 7_000))
    }
}
