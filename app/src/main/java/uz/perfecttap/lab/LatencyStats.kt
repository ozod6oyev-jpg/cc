package uz.perfecttap.lab

import kotlin.math.abs
import kotlin.math.sqrt

/** Rolling latency window: mean, p95 and jitter (standard deviation). */
class LatencyStats(private val capacity: Int = 400) {

    private val values = LongArray(capacity)
    private val sorted = LongArray(capacity)
    private var size = 0
    private var next = 0

    val samples: Int get() = size

    fun add(deltaNs: Long) {
        if (deltaNs <= 0L) return
        values[next] = deltaNs
        next = (next + 1) % capacity
        if (size < capacity) size++
    }

    fun reset() {
        size = 0
        next = 0
    }

    fun meanNs(): Double {
        if (size == 0) return 0.0
        var sum = 0L
        for (i in 0 until size) sum += values[i]
        return sum.toDouble() / size
    }

    fun p95Ns(): Double {
        if (size == 0) return 0.0
        System.arraycopy(values, 0, sorted, 0, size)
        java.util.Arrays.sort(sorted, 0, size)
        return sorted[((size - 1) * 0.95).toInt()].toDouble()
    }

    /** Standard deviation of the samples; the lab compares it against one frame. */
    fun jitterNs(): Double {
        if (size < 2) return 0.0
        val mean = meanNs()
        var sum = 0.0
        for (i in 0 until size) {
            val diff = values[i] - mean
            sum += diff * diff
        }
        return sqrt(sum / size)
    }

    /** true when the spread stays within one display frame, the realistic target. */
    fun meetsJitterCriterion(thresholdMs: Double = 16.7): Boolean =
        size >= 2 && jitterNs() / 1_000_000.0 < thresholdMs

    /** p95 of |value - mean|, a robust alternative to the standard deviation. */
    fun p95DeviationNs(): Double {
        if (size < 2) return 0.0
        val mean = meanNs()
        System.arraycopy(values, 0, sorted, 0, size)
        for (i in 0 until size) sorted[i] = abs(sorted[i] - mean.toLong())
        java.util.Arrays.sort(sorted, 0, size)
        return sorted[((size - 1) * 0.95).toInt()].toDouble()
    }
}
