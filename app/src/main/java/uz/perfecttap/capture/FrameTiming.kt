package uz.perfecttap.capture

import kotlin.math.sqrt

/**
 * Rolling window of inter-frame deltas. All buffers are preallocated, so
 * recording a frame does not allocate.
 */
class FrameTiming(private val capacity: Int = 240) {

    private val deltasNs = LongArray(capacity)
    private var count = 0
    private var writeIndex = 0
    private var lastTimestampNs = 0L
    private var hasLast = false

    @Synchronized
    fun add(timestampNs: Long) {
        if (hasLast) {
            val delta = timestampNs - lastTimestampNs
            // Ignore duplicates and gaps (pause/resume) that would distort stats.
            if (delta in 1..1_000_000_000L) {
                deltasNs[writeIndex] = delta
                writeIndex = (writeIndex + 1) % capacity
                if (count < capacity) count++
            }
        }
        lastTimestampNs = timestampNs
        hasLast = true
    }

    @Synchronized
    fun reset() {
        count = 0
        writeIndex = 0
        lastTimestampNs = 0L
        hasLast = false
    }

    @Synchronized
    fun samples(): Int = count

    @Synchronized
    fun avgDeltaNs(): Double {
        if (count == 0) return 0.0
        var sum = 0L
        for (i in 0 until count) sum += deltasNs[i]
        return sum.toDouble() / count
    }

    @Synchronized
    fun maxDeltaNs(): Long {
        var max = 0L
        for (i in 0 until count) if (deltasNs[i] > max) max = deltasNs[i]
        return max
    }

    /** Standard deviation of frame times: the jitter we care about. */
    @Synchronized
    fun jitterNs(): Double {
        if (count < 2) return 0.0
        val mean = avgDeltaNs()
        var sumSquares = 0.0
        for (i in 0 until count) {
            val diff = deltasNs[i] - mean
            sumSquares += diff * diff
        }
        return sqrt(sumSquares / count)
    }

    @Synchronized
    fun fps(): Double {
        val avg = avgDeltaNs()
        return if (avg <= 0.0) 0.0 else 1_000_000_000.0 / avg
    }
}
