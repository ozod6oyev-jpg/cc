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

    /**
     * Lab sessiyasidagi xato: chetdan qaytayotgan blok bir xil X dan o'tadi va
     * kadr oldingi supurishdagi chizishga bog'lanib, Δ_stream ~100 ms ga sakrardi.
     */
    @Test
    fun matchesTheReturnPassAfterABounce() {
        val ring = FrameRing(64)
        // Outbound: 100..172 px moving right, then back from 173 moving left.
        for (i in 0..9) ring.record(i * 8 * ms, 100f + 8f * i, 1)
        for (i in 10..19) ring.record(i * 8 * ms, 173f - 8f * (i - 9), -1)
        val correlator = FrameCorrelator(ring, tolerancePx = 4f)

        // Two frames of the return pass, captured 20 ms after their draws
        // (133 px at 112 ms, 125 px at 120 ms). 124.2 px is closer to the
        // outbound draw (124 px at 24 ms), but that pass moved the other way.
        correlator.streamDeltaNs(133f, 132 * ms)
        assertEquals(20 * ms, correlator.streamDeltaNs(124.2f, 140 * ms))
        assertEquals(120 * ms, correlator.lastVsyncNs)
    }

    @Test
    fun prefersTheDrawClosestToTheStableDelay() {
        val ring = FrameRing(64)
        // The square sits still at 300 px for a while: every draw matches.
        for (i in 0..20) ring.record(i * 8 * ms, 300f)
        val correlator = FrameCorrelator(ring, tolerancePx = 4f)
        // A settled 24 ms delay (the newest draw is picked without a hint).
        for (k in 0..3) {
            ring.record((21 + k) * 8 * ms, 300f)
            correlator.streamDeltaNs(300f, (21 + k) * 8 * ms + 24 * ms)
        }
        val delta = correlator.streamDeltaNs(300f, 24 * 8 * ms + 24 * ms)
        assertEquals(24 * ms, delta)
    }
}
