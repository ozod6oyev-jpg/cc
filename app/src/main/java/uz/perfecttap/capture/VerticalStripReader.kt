package uz.perfecttap.capture

import java.nio.ByteBuffer

/**
 * Reads a narrow vertical strip (used to follow the moving block's Y) into a
 * reused IntArray. Rows are sampled every [rowStep] pixels, which is enough for
 * block boundaries and much cheaper than reading the full frame.
 */
class VerticalStripReader(
    val stripWidth: Int,
    val rowStep: Int,
    val startY: Int,
    val endY: Int
) {

    val rows: Int = ((endY - startY) / rowStep).coerceAtLeast(1)
    val pixels = IntArray(stripWidth * rows)

    /** Returns how many strip rows were read; partial rows are dropped. */
    fun read(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        centerX: Int,
        imageWidth: Int,
        imageHeight: Int
    ): Int {
        if (buffer.capacity() <= 0) return 0
        val left = (centerX - stripWidth / 2).coerceIn(0, (imageWidth - stripWidth).coerceAtLeast(0))
        val limit = buffer.limit()
        var rowsRead = 0
        for (r in 0 until rows) {
            val y = startY + r * rowStep
            if (y >= imageHeight) break
            val base = y * rowStride
            var read = 0
            for (c in 0 until stripWidth) {
                val offset = base + (left + c) * pixelStride
                if (offset < 0 || offset + 4 > limit) break
                pixels[r * stripWidth + c] = buffer.getInt(offset)
                read++
            }
            if (read < stripWidth) break
            rowsRead++
        }
        return rowsRead
    }
}
