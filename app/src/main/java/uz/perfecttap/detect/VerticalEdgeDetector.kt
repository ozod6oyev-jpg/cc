package uz.perfecttap.detect

import kotlin.math.abs
import kotlin.math.max

/**
 * Finds the moving block's vertical position from a narrow vertical strip.
 *
 * The strip is averaged across its columns, then horizontal edges (block top and
 * block bottom / stack boundary) show up as gradient peaks. The first pair of
 * consecutive peaks with a plausible block height is returned, so the X scan
 * band can follow the block as the stack grows and the field shifts.
 *
 * All buffers are preallocated; [detect] allocates nothing.
 */
class VerticalEdgeDetector(
    private val stripWidth: Int,
    private val rowStep: Int,
    private val rows: Int,
    private val minBlockHeightPx: Int = 30,
    private val maxBlockHeightPx: Int = 400
) {

    /** Row positions are in strip rows; multiply by [rowStep] for pixels. */
    data class Result(
        val topRow: Float,
        val bottomRow: Float,
        val centerRow: Float,
        val heightPx: Float,
        val peaks: Int
    )

    private val avg = FloatArray(rows)
    private val grad = FloatArray(rows)
    private val smooth = FloatArray(rows)
    private val peakRow = FloatArray(rows)
    private val peakStrength = FloatArray(rows)

    var lastPeaks = 0
        private set

    /** Strip brightness: a black strip means the camera does not see the scene. */
    var lastMeanLum = 0f
        private set
    var lastMaxLum = 0f
        private set

    /** Returns the first plausible block edge pair, or null when there is none. */
    fun detect(pixels: IntArray): Result? {
        if (pixels.size < stripWidth * rows) return null

        for (r in 0 until rows) {
            val base = r * stripWidth
            var sum = 0
            for (c in 0 until stripWidth) {
                val p = pixels[base + c]
                sum += (77 * (p and 0xFF) + 150 * ((p ushr 8) and 0xFF) + 29 * ((p ushr 16) and 0xFF)) ushr 8
            }
            avg[r] = sum.toFloat() / stripWidth
        }
        var lumSum = 0f
        var lumMax = 0f
        for (r in 0 until rows) {
            lumSum += avg[r]
            if (avg[r] > lumMax) lumMax = avg[r]
        }
        lastMeanLum = lumSum / rows
        lastMaxLum = lumMax

        grad[0] = 0f
        grad[rows - 1] = 0f
        var maxGradient = 0f
        for (r in 1 until rows - 1) {
            val d = avg[r + 1] - avg[r - 1]
            grad[r] = abs(d)
            if (grad[r] > maxGradient) maxGradient = grad[r]
        }
        if (maxGradient <= 1f) {
            lastPeaks = 0
            return null
        }

        smooth[0] = 0f
        smooth[rows - 1] = 0f
        for (r in 1 until rows - 1) {
            smooth[r] = (grad[r - 1] + 2f * grad[r] + grad[r + 1]) * 0.25f
        }

        // The moving block's top edge is strong; the boundary between it and the
        // block below can be low contrast (similar materials), so peaks are kept
        // at a weak threshold and the pair is validated afterwards.
        val strongThreshold = max(8f, 0.18f * maxGradient)
        val weakThreshold = max(4f, 0.05f * maxGradient)
        var peakCount = 0
        for (r in 1 until rows - 1) {
            val value = smooth[r]
            if (value <= weakThreshold) continue
            if (value < smooth[r - 1] || value <= smooth[r + 1]) continue
            val denominator = smooth[r - 1] - 2f * smooth[r] + smooth[r + 1]
            val offset = if (abs(denominator) > 1e-6f) {
                (0.5f * (smooth[r - 1] - smooth[r + 1]) / denominator).coerceIn(-0.5f, 0.5f)
            } else {
                0f
            }
            peakRow[peakCount] = r + offset
            peakStrength[peakCount] = value
            peakCount++
        }
        lastPeaks = peakCount
        if (peakCount < 2) return null

        for (i in 0 until peakCount - 1) {
            if (peakStrength[i] < strongThreshold) continue
            for (j in i + 1 until peakCount) {
                if (peakStrength[j] < weakThreshold) continue
                val heightPx = (peakRow[j] - peakRow[i]) * rowStep
                if (heightPx < minBlockHeightPx) continue
                if (heightPx > maxBlockHeightPx) break
                return Result(
                    topRow = peakRow[i],
                    bottomRow = peakRow[j],
                    centerRow = (peakRow[i] + peakRow[j]) * 0.5f,
                    heightPx = heightPx,
                    peaks = peakCount
                )
            }
        }
        return null
    }
}
