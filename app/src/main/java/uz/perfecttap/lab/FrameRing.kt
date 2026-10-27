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
    /** Harakat yo'nalishi (+1 / -1; 0 = noma'lum) shu chizishda. */
    private val directions = ByteArray(capacity)
    private var size = 0
    private var next = 0

    val count: Int get() = size

    fun record(vsyncNs: Long, centerX: Float, direction: Int = 0) {
        this.vsyncNs[next] = vsyncNs
        centers[next] = centerX
        directions[next] = direction.coerceIn(-1, 1).toByte()
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
     *
     * The square passes every position twice per sweep (there and back), so a
     * position alone is ambiguous near the edges. [direction] (`0` = unknown)
     * drops draws moving the other way, and [expectedVsyncNs] (`<= 0` = none)
     * picks, among the remaining passes, the draw closest to the expected time.
     */
    fun matchCenter(
        centerX: Float,
        tolerancePx: Float,
        notOlderThanNs: Long,
        maxFrames: Int = 600,
        direction: Int = 0,
        expectedVsyncNs: Long = 0L
    ): Int {
        var bestSlot = -1
        var bestDistance = tolerancePx
        var bestTimeError = Long.MAX_VALUE
        var slot = (next - 1 + capacity) % capacity
        repeat(minOf(size, maxFrames)) {
            if (vsyncNs[slot] < notOlderThanNs) return bestSlot
            val distance = abs(centers[slot] - centerX)
            val slotDirection = directions[slot].toInt()
            val directionOk = direction == 0 || slotDirection == 0 || slotDirection == direction
            if (distance < tolerancePx && directionOk) {
                if (expectedVsyncNs > 0L) {
                    val timeError = abs(vsyncNs[slot] - expectedVsyncNs)
                    if (timeError < bestTimeError ||
                        (timeError == bestTimeError && distance < bestDistance)
                    ) {
                        bestTimeError = timeError
                        bestDistance = distance
                        bestSlot = slot
                    }
                } else if (distance < bestDistance) {
                    bestDistance = distance
                    bestSlot = slot
                }
            }
            slot = (slot - 1 + capacity) % capacity
        }
        return bestSlot
    }
}
