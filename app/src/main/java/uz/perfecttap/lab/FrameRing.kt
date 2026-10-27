package uz.perfecttap.lab

import kotlin.math.abs

/**
 * Ring of recently drawn square positions. The lab square moves a fixed number
 * of pixels per display frame, so the position found by the detector identifies
 * which draw the captured frame contains. Matching that draw's vsync timestamp
 * to the image timestamp gives Δ_stream (PDF, 9-bo'lim).
 *
 * Fixed-size arrays: recording a frame allocates nothing.
 */
class FrameRing(private val capacity: Int = 256) {

    private val vsyncNs = LongArray(capacity)
    private val centers = FloatArray(capacity)
    private var size = 0
    private var next = 0

    val count: Int get() = size

    fun record(vsyncNs: Long, centerX: Float) {
        this.vsyncNs[next] = vsyncNs
        centers[next] = centerX
        next = (next + 1) % capacity
        if (size < capacity) size++
    }

    fun clear() {
        size = 0
        next = 0
    }

    fun vsyncAt(slot: Int): Long = vsyncNs[slot]

    fun centerAt(slot: Int): Float = centers[slot]

    /**
     * Slot of the draw whose centre is closest to [centerX], or -1.
     *
     * Only draws not older than [notOlderThanNs] are considered: the square
     * repeats its position on the way back, so a match from an older sweep would
     * silently report a longer latency (and, at high speeds, a wrong one).
     */
    fun matchCenter(
        centerX: Float,
        tolerancePx: Float,
        notOlderThanNs: Long,
        maxFrames: Int = 600
    ): Int {
        var bestSlot = -1
        var bestDistance = tolerancePx
        var slot = (next - 1 + capacity) % capacity
        repeat(minOf(size, maxFrames)) {
            if (vsyncNs[slot] < notOlderThanNs) return bestSlot
            val distance = abs(centers[slot] - centerX)
            if (distance < bestDistance) {
                bestDistance = distance
                bestSlot = slot
            }
            slot = (slot - 1 + capacity) % capacity
        }
        return bestSlot
    }
}
