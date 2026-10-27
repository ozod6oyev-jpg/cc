package uz.perfecttap.lab

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameCorrelatorTest {

    private val ms = 1_000_000L

    @Test
    fun streamDeltaIsImageMinusVsync() {
        val ring = FrameRing(8)
        ring.record(10 * ms, 200f)
        ring.record(26 * ms, 208f)
        val correlator = FrameCorrelator(ring, tolerancePx = 4f)

        // The captured frame shows the square drawn at 26 ms, captured at 36 ms.
        assertEquals(10 * ms, correlator.streamDeltaNs(208.1f, 36 * ms))
        assertEquals(26 * ms, correlator.lastVsyncNs)
    }

    @Test
    fun rejectsUnmatchedPosition() {
        val ring = FrameRing(8)
        ring.record(10 * ms, 200f)
        val correlator = FrameCorrelator(ring, tolerancePx = 4f)

        assertEquals(-1L, correlator.streamDeltaNs(500f, 20 * ms))
    }

    @Test
    fun rejectsImpossibleLatency() {
        val ring = FrameRing(8)
        ring.record(100 * ms, 200f)
        val correlator = FrameCorrelator(ring, tolerancePx = 4f, maxLatencyNs = 120 * ms)

        // Image older than the draw it was matched to.
        assertEquals(-1L, correlator.streamDeltaNs(200f, 10 * ms))
        // Image too late for the ring entry to be plausible.
        assertEquals(-1L, correlator.streamDeltaNs(200f, 300 * ms))
    }

    @Test
    fun followsTheNewestDrawAfterWrapping() {
        val ring = FrameRing(4)
        for (i in 0 until 8) ring.record(i * 16 * ms, 100f + i * 8f)
        val correlator = FrameCorrelator(ring, tolerancePx = 3f)

        // Positions 100..156 wrapped out; only 128..156 remain.
        assertEquals(-1L, correlator.streamDeltaNs(100f, 200 * ms))
        assertEquals(8 * ms, correlator.streamDeltaNs(156f, 7 * 16 * ms + 8 * ms))
    }
}
