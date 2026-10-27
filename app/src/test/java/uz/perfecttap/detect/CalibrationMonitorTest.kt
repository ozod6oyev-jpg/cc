package uz.perfecttap.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationMonitorTest {

    @Test
    fun emptyMonitorHasZeroStats() {
        val monitor = CalibrationMonitor(50)
        assertEquals(0, monitor.samples)
        assertEquals(0f, monitor.medianWidth(), 0f)
        assertEquals(0f, monitor.p95WidthDrift(), 0f)
    }

    @Test
    fun constantWidthHasNoDrift() {
        val monitor = CalibrationMonitor(50)
        repeat(50) { monitor.add(256.25f) }

        assertEquals(50, monitor.samples)
        assertEquals(256.25f, monitor.medianWidth(), 0.001f)
        assertEquals(0f, monitor.p95WidthDrift(), 0.001f)
    }

    @Test
    fun p95TracksOutlierSpread() {
        val monitor = CalibrationMonitor(20)
        repeat(18) { monitor.add(100f) }
        repeat(2) { monitor.add(106f) }

        assertEquals(100f, monitor.medianWidth(), 0.001f)
        // Nearest-rank p95 over 20 samples is the 19th value: 106 -> drift 6.
        assertEquals(6f, monitor.p95WidthDrift(), 0.001f)
    }

    @Test
    fun windowKeepsOnlyNewestSamples() {
        val monitor = CalibrationMonitor(5)
        repeat(5) { monitor.add(50f) }
        repeat(5) { monitor.add(200f) }

        assertEquals(5, monitor.samples)
        assertEquals(200f, monitor.medianWidth(), 0.001f)
    }

    @Test
    fun resetClearsWindow() {
        val monitor = CalibrationMonitor(5)
        repeat(3) { monitor.add(80f) }
        monitor.reset()

        assertEquals(0, monitor.samples)
        assertEquals(0f, monitor.p95WidthDrift(), 0f)
    }

    @Test
    fun ignoresInvalidWidths() {
        val monitor = CalibrationMonitor(5)
        monitor.add(0f)
        monitor.add(-12f)

        assertEquals(0, monitor.samples)
    }

    @Test
    fun driftStaysTinyForRealisticJitter() {
        val monitor = CalibrationMonitor(50)
        val random = java.util.Random(3)
        repeat(50) { monitor.add(256f + (random.nextFloat() - 0.5f) * 0.4f) }

        assertTrue(monitor.p95WidthDrift() < 0.2f)
    }
}
