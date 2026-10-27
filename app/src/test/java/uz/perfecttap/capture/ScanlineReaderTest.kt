package uz.perfecttap.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ScanlineReaderTest {

    private fun rgbaBuffer(width: Int, height: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until height) {
            for (x in 0 until width) {
                buffer.putInt(y * width + x) // distinct value per pixel, like ABGR int
            }
        }
        buffer.position(0)
        return buffer
    }

    @Test
    fun readsRequestedRow() {
        val buffer = rgbaBuffer(width = 4, height = 6)
        buffer.limit(buffer.capacity())
        val slice = ScanlineReader(4)
        val read = slice.read(buffer, rowStride = 16, pixelStride = 4, y = 2, imageWidth = 4, imageHeight = 6)

        assertEquals(4, read)
        assertEquals(listOf(8, 9, 10, 11), slice.pixels.toList())
    }

    @Test
    fun clampsRowToImageHeight() {
        val buffer = rgbaBuffer(width = 3, height = 5)
        buffer.limit(buffer.capacity())
        val slice = ScanlineReader(3)
        slice.read(buffer, rowStride = 12, pixelStride = 4, y = 99, imageWidth = 3, imageHeight = 5)

        assertEquals(listOf(12, 13, 14), slice.pixels.toList())
    }

    @Test
    fun handlesRowPadding() {
        // rowStride larger than width * pixelStride (as seen on some GPU buffers)
        val width = 3
        val height = 2
        val rowStride = 16
        val buffer = ByteBuffer.allocateDirect(rowStride * height).order(ByteOrder.LITTLE_ENDIAN)
        for (x in 0 until width) buffer.putInt(x, 100 + x)
        for (x in 0 until width) buffer.putInt(rowStride + x * 4, 200 + x)
        buffer.limit(buffer.capacity())

        val slice = ScanlineReader(width)
        slice.read(buffer, rowStride, pixelStride = 4, y = 1, imageWidth = width, imageHeight = height)

        assertEquals(listOf(200, 201, 202), slice.pixels.toList())
    }

    @Test
    fun readsCentredBand() {
        val buffer = rgbaBuffer(width = 4, height = 6)
        buffer.limit(buffer.capacity())
        val slice = ScanlineReader(4, bandHeight = 3)
        val rows = slice.readBand(buffer, rowStride = 16, pixelStride = 4, centerY = 3, imageWidth = 4, imageHeight = 6)

        assertEquals(3, rows)
        assertEquals((8..19).toList(), slice.pixels.toList())
    }

    @Test
    fun bandClampsToImageBottom() {
        val buffer = rgbaBuffer(width = 4, height = 6)
        buffer.limit(buffer.capacity())
        val slice = ScanlineReader(4, bandHeight = 3)
        val rows = slice.readBand(buffer, rowStride = 16, pixelStride = 4, centerY = 99, imageWidth = 4, imageHeight = 6)

        assertEquals(3, rows)
        assertEquals((12..23).toList(), slice.pixels.toList())
    }

    @Test
    fun bandStopsWhenBufferEnds() {
        val buffer = rgbaBuffer(width = 4, height = 6)
        buffer.limit(3 * 16) // rows 0..2 only; the band starts at row 2
        val slice = ScanlineReader(4, bandHeight = 2)
        val rows = slice.readBand(buffer, rowStride = 16, pixelStride = 4, centerY = 3, imageWidth = 4, imageHeight = 6)

        assertEquals(1, rows)
        assertEquals(8, slice.pixels[0])
        assertEquals(11, slice.pixels[3])
        assertEquals(0, slice.pixels[4])
    }

    @Test
    fun stopsAtBufferLimit() {
        val buffer = rgbaBuffer(width = 4, height = 4)
        buffer.limit(8) // only two pixels of row 0 are inside the buffer
        val slice = ScanlineReader(4)
        val read = slice.read(buffer, rowStride = 16, pixelStride = 4, y = 0, imageWidth = 4, imageHeight = 4)

        assertEquals(2, read)
        assertEquals(0, slice.pixels[0])
        assertEquals(1, slice.pixels[1])
    }

    @Test
    fun emptyBufferReadsNothing() {
        val buffer = ByteBuffer.allocateDirect(0)
        val slice = ScanlineReader(4)
        assertEquals(0, slice.read(buffer, 16, 4, 0, 4, 4))
    }

    @Test
    fun narrowerImageOnlyFillsAvailablePixels() {
        val buffer = rgbaBuffer(width = 2, height = 2)
        buffer.limit(buffer.capacity())
        val slice = ScanlineReader(4)
        val read = slice.read(buffer, rowStride = 8, pixelStride = 4, y = 0, imageWidth = 2, imageHeight = 2)

        assertEquals(2, read)
        assertEquals(4, slice.pixels.size)
        assertTrue(slice.pixels[3] == 0)
    }
}

class FrameTimingTest {

    @Test
    fun measuresSixtyFps() {
        val timing = FrameTiming(capacity = 120)
        var t = 0L
        repeat(60) {
            timing.add(t)
            t += 16_666_667L
        }

        assertEquals(59, timing.samples())
        assertEquals(60.0, timing.fps(), 0.05)
        assertEquals(16.666, timing.avgDeltaNs() / 1_000_000.0, 0.01)
        assertEquals(16_666_667L, timing.maxDeltaNs())
        assertTrue(timing.jitterNs() < 1.0)
    }

    @Test
    fun ignoresGapsAndDuplicates() {
        val timing = FrameTiming(capacity = 16)
        timing.add(0)
        timing.add(8_000_000)          // counted
        timing.add(8_000_000)          // duplicate, ignored
        timing.add(2_000_000_000)      // gap > 1s, ignored

        assertEquals(1, timing.samples())
        assertEquals(8_000_000L, timing.maxDeltaNs())
    }

    @Test
    fun resetClearsWindow() {
        val timing = FrameTiming(capacity = 4)
        timing.add(0)
        timing.add(10_000_000)
        timing.reset()

        assertEquals(0, timing.samples())
        assertEquals(0.0, timing.fps(), 0.0)
        assertEquals(0L, timing.maxDeltaNs())
    }

    @Test
    fun wrapsAroundWithoutAllocating() {
        val timing = FrameTiming(capacity = 4)
        var t = 0L
        repeat(10) {
            timing.add(t)
            t += 10_000_000L
        }
        assertEquals(4, timing.samples())
        assertEquals(10_000_000L, timing.maxDeltaNs())
    }
}
