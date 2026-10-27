package uz.perfecttap.detect

/**
 * Tracks edge stability over the last [window] frames, which is how the
 * 3-bosqich criterion (±0.5 px over 50 consecutive frames) is verified on a
 * real device: a block has a constant width, so any spread in the measured
 * width is edge noise.
 *
 * Storage is a fixed ring buffer; nothing is allocated per frame.
 */
class CalibrationMonitor(private val window: Int = 50) {

    private val widths = FloatArray(window)
    private val scratch = FloatArray(window)
    private var count = 0
    private var index = 0

    val samples: Int get() = count
    val capacity: Int get() = window

    fun add(width: Float) {
        if (width <= 0f) return
        widths[index] = width
        index = (index + 1) % window
        if (count < window) count++
    }

    fun reset() {
        count = 0
        index = 0
    }

    fun medianWidth(): Float {
        if (count == 0) return 0f
        System.arraycopy(widths, 0, scratch, 0, count)
        java.util.Arrays.sort(scratch, 0, count)
        return if (count % 2 == 1) {
            scratch[count / 2]
        } else {
            (scratch[count / 2 - 1] + scratch[count / 2]) * 0.5f
        }
    }

    /** p95 of |width - median|: the width jitter used as the accuracy proxy. */
    fun p95WidthDrift(): Float {
        if (count < 2) return 0f
        val median = medianWidth()
        System.arraycopy(widths, 0, scratch, 0, count)
        for (i in 0 until count) scratch[i] = kotlin.math.abs(scratch[i] - median)
        java.util.Arrays.sort(scratch, 0, count)
        val rank = ((count - 1) * 0.95f).toInt()
        return scratch[rank]
    }
}
