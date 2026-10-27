package uz.perfecttap.lab

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameRingTest {

    @Test
    fun matchesExactCenter() {
        val ring = FrameRing(8)
        ring.record(1_000L, 100f)
        ring.record(2_000L, 108f)
        ring.record(3_000L, 116f)

        val slot = ring.matchCenter(108.2f, tolerancePx = 4f, notOlderThanNs = 0L)
        assertEquals(2_000L, ring.vsyncAt(slot))
    }

    @Test
    fun returnsMinusOneOutsideTolerance() {
        val ring = FrameRing(4)
        ring.record(1_000L, 100f)
        assertEquals(-1, ring.matchCenter(120f, tolerancePx = 4f, notOlderThanNs = 0L))
    }

    @Test
    fun picksNearestOfTwoCandidates() {
        val ring = FrameRing(4)
        ring.record(1_000L, 100f)
        ring.record(2_000L, 106f)

        // 104.5 is 1.5 px from the second entry and 4.5 px from the first.
        val slot = ring.matchCenter(104.5f, tolerancePx = 6f, notOlderThanNs = 0L)
        assertEquals(2_000L, ring.vsyncAt(slot))
    }

    @Test
    fun wrapsAtCapacityKeepingNewest() {
        val ring = FrameRing(4)
        for (i in 0 until 10) ring.record(i * 1_000L, i * 8f)

        assertEquals(4, ring.count)
        // Only the last four draws (positions 48, 56, 64, 72) remain.
        assertEquals(-1, ring.matchCenter(0f, tolerancePx = 2f, notOlderThanNs = 0L))
        assertEquals(9_000L, ring.vsyncAt(ring.matchCenter(72f, tolerancePx = 2f, notOlderThanNs = 0L)))
    }

    @Test
    fun ignoresDrawsOlderThanTheWindow() {
        val ring = FrameRing(8)
        ring.record(1_000L, 100f)
        ring.record(9_000L, 100f) // the same position on the next sweep

        // The newest draw inside the window wins.
        assertEquals(9_000L, ring.vsyncAt(ring.matchCenter(100f, 4f, notOlderThanNs = 5_000L)))
        // Nothing is recent enough, so the frame is not matched at all.
        assertEquals(-1, ring.matchCenter(100f, 4f, notOlderThanNs = 9_500L))
    }

    @Test
    fun clearDropsEverything() {
        val ring = FrameRing(4)
        ring.record(1_000L, 100f)
        ring.clear()

        assertEquals(0, ring.count)
        assertEquals(-1, ring.matchCenter(100f, tolerancePx = 40f, notOlderThanNs = 0L))
    }
}
