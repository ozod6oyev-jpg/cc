package uz.perfecttap.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class EdgeDetectorTest {

    private val width = 1080
    private val rows = 41

    /**
     * Renders a band where each pixel's luminance is the covered fraction of a
     * block [left, right] between [bg] and [fg], plus per-pixel noise and an
     * optional texture stripe inside the block.
     */
    private fun renderBand(
        left: Float,
        right: Float,
        bg: Float = 30f,
        fg: Float = 200f,
        noise: Int = 0,
        stripe: Int = 0,
        seed: Long = 7L,
        bandRows: Int = rows
    ): IntArray {
        val pixels = IntArray(width * bandRows)
        val random = if (noise > 0) Random(seed) else null
        val luma = FloatArray(width)
        for (x in 0 until width) {
            val a = x - 0.5f
            val b = x + 0.5f
            val overlap = maxOf(0f, minOf(b, right) - maxOf(a, left))
            val fraction = (overlap / (b - a)).coerceIn(0f, 1f)
            var value = bg + (fg - bg) * fraction
            if (stripe != 0 && fraction >= 1f && (x / 3) % 2 == 0) {
                value += stripe
            }
            luma[x] = value.coerceIn(0f, 255f)
        }
        for (r in 0 until bandRows) {
            for (x in 0 until width) {
                var v = luma[x].toInt()
                if (random != null) {
                    v += random.nextInt(2 * noise + 1) - noise
                }
                v = v.coerceIn(0, 255)
                pixels[r * width + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        return pixels
    }

    @Test
    fun detectsWholePixelEdges() {
        val detector = EdgeDetector(width)
        val found = detector.detect(renderBand(400f, 656f), rows)

        assertTrue(found)
        assertEquals(400.0, detector.bestLeft.toDouble(), 0.2)
        assertEquals(656.0, detector.bestRight.toDouble(), 0.2)
        assertEquals(256.0, detector.bestWidth.toDouble(), 0.3)
        assertEquals(528.0, detector.bestCenter.toDouble(), 0.2)
        assertEquals(2, detector.peakCount)
    }

    @Test
    fun subPixelAccuracyIsBetterThanHalfAPixel() {
        val detector = EdgeDetector(width)
        for (fraction in listOf(0.0f, 0.1f, 0.25f, 0.4f, 0.5f, 0.6f, 0.75f, 0.9f)) {
            val left = 400f + fraction
            val right = left + 256f
            assertTrue(detector.detect(renderBand(left, right), rows))
            assertEquals("left edge at $left", left.toDouble(), detector.bestLeft.toDouble(), 0.15)
            assertEquals("right edge", right.toDouble(), detector.bestRight.toDouble(), 0.15)
        }
    }

    @Test
    fun findsEdgesUnderNoise() {
        val detector = EdgeDetector(width)
        var worst = 0.0
        for (frame in 0 until 20) {
            assertTrue(detector.detect(renderBand(412.3f, 668.7f, noise = 8, seed = frame.toLong()), rows))
            worst = maxOf(worst, kotlin.math.abs(detector.bestLeft - 412.3f).toDouble())
            worst = maxOf(worst, kotlin.math.abs(detector.bestRight - 668.7f).toDouble())
        }
        assertTrue("worst error $worst px", worst < 0.5)
    }

    @Test
    fun ignoresTextureStripesInsideBlock() {
        val detector = EdgeDetector(width)
        assertTrue(detector.detect(renderBand(300f, 556f, stripe = 35, noise = 5), rows))
        assertEquals(300.0, detector.bestLeft.toDouble(), 0.5)
        assertEquals(556.0, detector.bestRight.toDouble(), 0.5)
    }

    @Test
    fun rejectsNarrowBrightStripe() {
        val detector = EdgeDetector(width)
        // 10 px stripe is below W_min (20 px) and must not be reported as a block.
        assertFalse(detector.detect(renderBand(500f, 510f), rows))
    }

    @Test
    fun reportsWiderBlockWhenNarrowNoiseIsPresent() {
        val detector = EdgeDetector(width)
        // Narrow stripe is built by adding a small bright run inside the block.
        val pixels = renderBand(300f, 556f)
        for (r in 0 until rows) {
            for (x in 400 until 408) {
                pixels[r * width + x] = (0xFF shl 24) or (250 shl 16) or (250 shl 8) or 250
            }
        }
        assertTrue(detector.detect(pixels, rows))
        assertEquals(300.0, detector.bestLeft.toDouble(), 0.5)
        assertEquals(556.0, detector.bestRight.toDouble(), 0.5)
    }

    @Test
    fun detectsDarkBlockOnBrightBackground() {
        val detector = EdgeDetector(width)
        assertTrue(detector.detect(renderBand(420f, 676f, bg = 210f, fg = 40f), rows))
        assertEquals(420.0, detector.bestLeft.toDouble(), 0.2)
        assertEquals(676.0, detector.bestRight.toDouble(), 0.2)
    }

    @Test
    fun rejectsBackgroundWideRun() {
        val detector = EdgeDetector(width)
        // 1010 px wide (> 90% of the band) is the background, not a block.
        assertFalse(detector.detect(renderBand(30f, 1040f), rows))
    }

    @Test
    fun flatBandHasNoEdges() {
        val detector = EdgeDetector(width)
        assertFalse(detector.detect(renderBand(0f, 0f, bg = 90f, fg = 90f), rows))
        assertFalse(detector.hasBlock)
        assertEquals(0, detector.peakCount)
    }

    @Test
    fun fiftyConsecutiveFramesStayWithinTolerance() {
        val detector = EdgeDetector(width)
        val monitor = CalibrationMonitor(50)
        var worst = 0.0
        repeat(50) { frame ->
            val left = 512.4f
            val right = left + 288f
            assertTrue("frame $frame", detector.detect(renderBand(left, right, noise = 6, seed = frame.toLong()), rows))
            monitor.add(detector.bestWidth)
            worst = maxOf(worst, kotlin.math.abs(detector.bestLeft - left).toDouble())
            worst = maxOf(worst, kotlin.math.abs(detector.bestRight - right).toDouble())
        }
        assertEquals(50, monitor.samples)
        assertTrue("worst edge error $worst px", worst < 0.5)
        assertTrue("width drift p95 ${monitor.p95WidthDrift()}", monitor.p95WidthDrift() < 0.5f)
    }

    @Test
    fun zeroAllocationAfterWarmUp() {
        val detector = EdgeDetector(width)
        val pixels = renderBand(400f, 656f, noise = 4)
        repeat(20) { detector.detect(pixels, rows) }

        val runtime = Runtime.getRuntime()
        System.gc()
        Thread.sleep(50)
        val before = runtime.totalMemory() - runtime.freeMemory()
        repeat(200) { detector.detect(pixels, rows) }
        val after = runtime.totalMemory() - runtime.freeMemory()
        val perFrame = (after - before).toDouble() / 200
        assertTrue("allocated $perFrame bytes/frame", perFrame < 256)
    }
}
