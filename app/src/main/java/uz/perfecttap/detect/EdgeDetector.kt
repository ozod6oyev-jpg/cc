package uz.perfecttap.detect

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 1D gradient edge detector over a horizontal band (PDF, 3-bosqich).
 *
 * Pipeline per frame:
 *  1. luminance  L = 0.299R + 0.587G + 0.114B (integer approximation)
 *  2. column average over the whole band, so texture noise is averaged out
 *  3. centred gradient  G[i] = |L[i+1] - L[i-1]|  plus its sign
 *  4. 3-tap smoothing, adaptive threshold  T = max(T_min, 0.2 * G_max)
 *  5. local maxima with non-max suppression
 *  6. sub-pixel parabola around each peak
 *  7. pair neighbouring peaks of opposite sign whose distance passes the
 *     width filter; the strongest pair is the block in this frame
 *
 * All buffers are allocated once for a given [width]; [detect] allocates nothing,
 * which keeps the capture thread at the zero-allocation target.
 */
class EdgeDetector(
    private val width: Int,
    private val config: Config = Config()
) {

    data class Config(
        /** T_min: minimum gradient for a real edge (background is near black). */
        val minGradient: Float = 18f,
        /** G_min relative to the strongest edge in the frame. */
        val relativeThreshold: Float = 0.2f,
        /** W_min: narrower runs are texture noise, not a block. */
        val minWidthPx: Int = 20,
        /** W_max: wider runs are the background, not a block. <= 0 means 90% of the band width. */
        val maxWidthPx: Int = -1,
        val peakMinDistance: Int = 6,
        val smoothing: Boolean = true,
        /** Ignore peaks touching the band borders (they have no neighbouring sample). */
        val borderMargin: Int = 2
    )

    private val sums = IntArray(width)
    private val avg = FloatArray(width)
    private val grad = FloatArray(width)
    private val smoothed = FloatArray(width)
    private val sign = ByteArray(width)
    private val peakPos = FloatArray(width)
    private val peakStrength = FloatArray(width)
    private val peakSign = ByteArray(width)

    /** Number of gradient peaks above the threshold in the last frame. */
    var peakCount = 0
        private set

    /** Number of width-filtered edge pairs in the last frame. */
    var blockCount = 0
        private set

    /** Best block of the last frame; valid when [hasBlock] is true. */
    var hasBlock = false
        private set
    var bestLeft = 0f
        private set
    var bestRight = 0f
        private set
    var bestScore = 0f
        private set

    /** Wall time of the last [detect] call in nanoseconds. */
    var detectNanos = 0L
        private set

    /**
     * Mean luminance of the analysed band (0..255). A band that is empty because
     * the camera sees a black surface reads ~0, which distinguishes "nothing on
     * screen" from "block is just outside this row".
     */
    var meanLuminance = 0f
        private set

    /**
     * Optional expected block width (`<= 0` disables). Drawn content inside a
     * block (numbers, highlights) creates strong interior edges, so when the
     * expected width is known the pair closest to it wins over the strongest
     * one. The loosest plausible pair is still used as a fallback.
     */
    var expectedWidthPx: Float = -1f

    /** Accepted deviation from [expectedWidthPx]; `<= 0` means 20% of it. */
    var expectedWidthTolerancePx: Float = -1f

    val bestWidth: Float get() = bestRight - bestLeft
    val bestCenter: Float get() = (bestLeft + bestRight) * 0.5f

    /**
     * Runs the detector over [rows] rows of [pixels] (row-major, [width] per row).
     * Returns true when a block was found.
     */
    fun detect(pixels: IntArray, rows: Int): Boolean {
        val started = System.nanoTime()
        if (rows <= 0 || pixels.size < width * rows) {
            resetResult()
            detectNanos = System.nanoTime() - started
            return false
        }

        // 1-2. luminance summed over the band, then averaged per column
        java.util.Arrays.fill(sums, 0)
        var lumSum = 0L
        for (r in 0 until rows) {
            val base = r * width
            for (x in 0 until width) {
                val p = pixels[base + x]
                val lum = (77 * (p and 0xFF) + 150 * ((p ushr 8) and 0xFF) + 29 * ((p ushr 16) and 0xFF)) ushr 8
                sums[x] += lum
                lumSum += lum
            }
        }
        meanLuminance = lumSum.toFloat() / (rows * width)
        val invRows = 1f / rows
        for (x in 0 until width) avg[x] = sums[x] * invRows

        // 3. centred gradient with sign
        grad[0] = 0f
        sign[0] = 0
        grad[width - 1] = 0f
        sign[width - 1] = 0
        var maxGradient = 0f
        for (x in 1 until width - 1) {
            val d = avg[x + 1] - avg[x - 1]
            grad[x] = abs(d)
            sign[x] = if (d > 0f) 1 else if (d < 0f) -1 else 0
            if (grad[x] > maxGradient) maxGradient = grad[x]
        }
        if (maxGradient <= 0f) {
            resetResult()
            detectNanos = System.nanoTime() - started
            return false
        }

        // 4. smoothing + adaptive threshold
        val source: FloatArray
        if (config.smoothing) {
            smoothed[0] = 0f
            smoothed[width - 1] = 0f
            for (x in 1 until width - 1) {
                smoothed[x] = (grad[x - 1] + 2f * grad[x] + grad[x + 1]) * 0.25f
            }
            source = smoothed
        } else {
            source = grad
        }
        val threshold = max(config.minGradient, config.relativeThreshold * maxGradient)

        // 5. local maxima with non-max suppression
        peakCount = 0
        val margin = config.borderMargin
        for (x in margin until width - margin) {
            val value = source[x]
            if (value <= threshold) continue
            if (value < source[x - 1] || value <= source[x + 1]) continue
            if (peakCount > 0 && x - peakPos[peakCount - 1].toInt() < config.peakMinDistance) {
                if (value > peakStrength[peakCount - 1]) {
                    peakPos[peakCount - 1] = subPixel(source, x)
                    peakStrength[peakCount - 1] = value
                    peakSign[peakCount - 1] = sign[x]
                }
                continue
            }
            peakPos[peakCount] = subPixel(source, x)
            peakStrength[peakCount] = value
            peakSign[peakCount] = sign[x]
            peakCount++
        }

        // 6-7. pair peaks of opposite sign, filter by width, keep the strongest pair.
        // Every pair is considered (not only neighbours) so a texture edge inside a
        // block cannot split the true left/right edges apart.
        blockCount = 0
        hasBlock = false
        bestScore = 0f
        val expected = expectedWidthPx
        val tolerance = if (expectedWidthTolerancePx > 0f) {
            expectedWidthTolerancePx
        } else {
            expected * 0.2f
        }
        var matchDeviation = Float.MAX_VALUE
        var matchLeft = 0f
        var matchRight = 0f
        var matchScore = 0f
        var bestLeftAny = 0f
        var bestRightAny = 0f
        var bestScoreAny = 0f
        val maxWidth = if (config.maxWidthPx > 0) config.maxWidthPx else width * 9 / 10
        for (i in 0 until peakCount - 1) {
            if (peakSign[i].toInt() == 0) continue
            for (j in i + 1 until peakCount) {
                if (peakSign[j].toInt() == peakSign[i].toInt()) continue
                val a = peakPos[i]
                val b = peakPos[j]
                val left = min(a, b)
                val right = max(a, b)
                val widthPx = right - left
                if (widthPx < config.minWidthPx) continue
                if (widthPx > maxWidth) break
                blockCount++
                val score = min(peakStrength[i], peakStrength[j])
                if (expected > 0f) {
                    val deviation = abs(widthPx - expected)
                    if (deviation <= tolerance && deviation < matchDeviation) {
                        matchDeviation = deviation
                        matchLeft = left
                        matchRight = right
                        matchScore = score
                    }
                }
                if (score > bestScoreAny) {
                    bestScoreAny = score
                    bestLeftAny = left
                    bestRightAny = right
                }
            }
        }
        if (matchDeviation < Float.MAX_VALUE) {
            bestLeft = matchLeft
            bestRight = matchRight
            bestScore = matchScore
            hasBlock = true
        } else if (bestScoreAny > 0f) {
            bestLeft = bestLeftAny
            bestRight = bestRightAny
            bestScore = bestScoreAny
            hasBlock = true
        }

        detectNanos = System.nanoTime() - started
        return hasBlock
    }

    /** Parabola vertex through three samples: Δ = (G[i-1] - G[i+1]) / (2 * (G[i-1] - 2G[i] + G[i+1])). */
    private fun subPixel(values: FloatArray, i: Int): Float {
        val denominator = values[i - 1] - 2f * values[i] + values[i + 1]
        if (abs(denominator) < 1e-6f) return i.toFloat()
        val offset = 0.5f * (values[i - 1] - values[i + 1]) / denominator
        return i + offset.coerceIn(-0.5f, 0.5f)
    }

    private fun resetResult() {
        peakCount = 0
        blockCount = 0
        hasBlock = false
        bestScore = 0f
        bestLeft = 0f
        bestRight = 0f
    }
}
