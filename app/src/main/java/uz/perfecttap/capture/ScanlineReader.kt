package uz.perfecttap.capture

import java.nio.ByteBuffer

/**
 * Copies a horizontal band of an RGBA_8888 ImageReader buffer into a reused
 * IntArray. No per-frame allocation: the array lives for the capture session.
 *
 * Pixels are stored as packed ints (AABBGGRR for the usual little-endian
 * layout), which is what the detector unpacks.
 */
class ScanlineReader(val width: Int, val bandHeight: Int = 1) {

    val pixels = IntArray(width * bandHeight)

    /**
     * Reads one row [y] into row 0 and returns how many pixels were written.
     * Rows are addressed with rowStride (which includes padding), pixels with
     * pixelStride.
     */
    fun read(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        y: Int,
        imageWidth: Int,
        imageHeight: Int
    ): Int {
        if (buffer.capacity() <= 0) return 0
        val row = y.coerceIn(0, (imageHeight - 1).coerceAtLeast(0))
        return readRow(buffer, rowStride, pixelStride, row, 0, imageWidth, buffer.limit())
    }

    /**
     * Reads [bandHeight] rows centred on [centerY] into [pixels], top row first,
     * and returns the number of rows actually written. The band is clamped to
     * the image, so the caller can request a band near the screen edge.
     */
    fun readBand(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        centerY: Int,
        imageWidth: Int,
        imageHeight: Int
    ): Int {
        if (buffer.capacity() <= 0 || bandHeight <= 0) return 0
        val maxTop = (imageHeight - bandHeight).coerceAtLeast(0)
        val top = (centerY - bandHeight / 2).coerceIn(0, maxTop)
        val limit = buffer.limit()
        var rowsRead = 0
        for (r in 0 until bandHeight) {
            val y = top + r
            if (y >= imageHeight) break
            val read = readRow(buffer, rowStride, pixelStride, y, r * width, imageWidth, limit)
            // A partial row would mix fresh pixels with stale ones, so it is dropped.
            if (read < minOf(width, imageWidth)) break
            rowsRead++
        }
        return rowsRead
    }

    private fun readRow(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        row: Int,
        destinationOffset: Int,
        imageWidth: Int,
        limit: Int
    ): Int {
        val usable = minOf(width, imageWidth)
        val base = row * rowStride
        var read = 0
        for (x in 0 until usable) {
            val offset = base + x * pixelStride
            if (offset < 0 || offset + 4 > limit) break
            pixels[destinationOffset + x] = buffer.getInt(offset)
            read++
        }
        return read
    }
}
