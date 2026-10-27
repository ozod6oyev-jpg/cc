package uz.perfecttap.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyStatsTest {

    private val ms = 1_000_000L

    @Test
    fun emptyStatsAreZero() {
        val stats = LatencyStats(10)
        assertEquals(0, stats.samples)
        assertEquals(0.0, stats.meanNs(), 0.0)
        assertEquals(0.0, stats.p95Ns(), 0.0)
        assertEquals(0.0, stats.jitterNs(), 0.0)
        assertFalse(stats.meetsJitterCriterion())
    }

    @Test
    fun computesMeanAndP95() {
        val stats = LatencyStats(10)
        for (i in 1..10) stats.add(i * 2 * ms)

        assertEquals(11.0, stats.meanNs() / 1e6, 0.001)
        // Nearest-rank p95 of 10 samples is the 9th value.
        assertEquals(18.0, stats.p95Ns() / 1e6, 0.001)
    }

    @Test
    fun constantSamplesHaveZeroJitter() {
        val stats = LatencyStats(50)
        repeat(30) { stats.add(32 * ms) }

        assertEquals(0.0, stats.jitterNs(), 0.001)
        assertTrue(stats.meetsJitterCriterion())
    }

    @Test
    fun criterionIsOneDisplayFrame() {
        // A 5 ms spread is better than one 60 Hz frame, so it passes...
        val tight = LatencyStats(50)
        repeat(10) { tight.add(10 * ms) }
        repeat(10) { tight.add(20 * ms) }
        assertEquals(5.0, tight.jitterNs() / 1e6, 0.01)
        assertTrue(tight.meetsJitterCriterion(16.7))

        // ...while a spread larger than a frame does not.
        val wide = LatencyStats(50)
        repeat(10) { wide.add(20 * ms) }
        repeat(10) { wide.add(60 * ms) }
        assertEquals(20.0, wide.jitterNs() / 1e6, 0.01)
        assertFalse(wide.meetsJitterCriterion(16.7))
    }

    @Test
    fun ignoresNonPositiveSamples() {
        val stats = LatencyStats(10)
        stats.add(0L)
        stats.add(-5L)
        assertEquals(0, stats.samples)
    }

    @Test
    fun windowKeepsNewest() {
        val stats = LatencyStats(4)
        repeat(4) { stats.add(10 * ms) }
        repeat(4) { stats.add(50 * ms) }

        assertEquals(4, stats.samples)
        assertEquals(50.0, stats.meanNs() / 1e6, 0.001)
    }

    @Test
    fun resetClearsSamples() {
        val stats = LatencyStats(4)
        repeat(3) { stats.add(12 * ms) }
        stats.reset()
        assertEquals(0, stats.samples)
        assertEquals(0.0, stats.p95Ns(), 0.0)
    }

    @Test
    fun p95DeviationIsRobustSpread() {
        val stats = LatencyStats(20)
        repeat(18) { stats.add(40 * ms) }
        repeat(2) { stats.add(44 * ms) }

        // Values are 18×40 ms and 2×44 ms: mean 40.4 ms, so the 19th deviation is 3.6 ms.
        assertEquals(3.6, stats.p95DeviationNs() / 1e6, 0.001)
    }
}
