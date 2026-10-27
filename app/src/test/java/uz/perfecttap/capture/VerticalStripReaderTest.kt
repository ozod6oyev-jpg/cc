package uz.perfecttap.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VerticalStripReaderTest {

    private fun buffer(width: Int, height: Int): ByteBuffer {
        val b = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until height) {
            for (x in 0 until width) b.putInt(y * width + x)
        }
        b.position(0)
        b.limit(b.capacity())
        return b
    }

    @Test
    fun readsCentredStripWithRowStep() {
        val b = buffer(10, 10)
        val reader = VerticalStripReader(stripWidth = 3, rowStep = 2, startY = 0, endY = 10)

        assertEquals(5, reader.rows)
        val read = reader.read(b, rowStride = 40, pixelStride = 4, centerX = 5, imageWidth = 10, imageHeight = 10)

        assertEquals(5, read)
        // centreX 5 -> strip covers x=4,5,6 of row 0, 2, 4, 6, 8
        assertEquals(listOf(4, 5, 6), reader.pixels.take(3))
        assertEquals(listOf(24, 25, 26), reader.pixels.slice(3..5))
        assertEquals(listOf(84, 85, 86), reader.pixels.slice(12..14))
    }

    @Test
    fun clampsStripToImageEdges() {
        val b = buffer(10, 10)
        val reader = VerticalStripReader(stripWidth = 4, rowStep = 2, startY = 0, endY = 4)
        val read = reader.read(b, rowStride = 40, pixelStride = 4, centerX = 1, imageWidth = 10, imageHeight = 10)

        assertEquals(2, read)
        assertEquals(listOf(0, 1, 2, 3), reader.pixels.take(4))
    }

    @Test
    fun stopsAtImageBottom() {
        val b = buffer(10, 10)
        val reader = VerticalStripReader(stripWidth = 3, rowStep = 2, startY = 6, endY = 20)
        val read = reader.read(b, rowStride = 40, pixelStride = 4, centerX = 5, imageWidth = 10, imageHeight = 10)

        // rows at y=6, 8 are valid; y=10 is past the image
        assertEquals(2, read)
        assertEquals(64, reader.pixels[0])
    }

    @Test
    fun dropsPartialRowsAtBufferLimit() {
        val b = buffer(10, 10)
        // Only the first strip row (y=0) fits; the next sampled row is y=2 at byte 80.
        b.limit(40 * 2 + 4)
        val reader = VerticalStripReader(stripWidth = 3, rowStep = 2, startY = 0, endY = 10)

        val read = reader.read(b, rowStride = 40, pixelStride = 4, centerX = 5, imageWidth = 10, imageHeight = 10)
        assertEquals(1, read)
    }
}
