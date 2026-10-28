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
    private val maxBlockHeightPx: Int = 400,
    /**
     * Haqiqiy o'yin (qora fon, bloklar orasida tirqish): harakatlanuvchi blok —
     * **tagida o'xshash blok turgan eng yuqori blok** ([detectBlockOnStack]).
     */
    private val requireBlockBelow: Boolean = false
) {

    /** Row positions are in strip rows; multiply by [rowStep] for pixels. */
    data class Result(
        val topRow: Float,
        val bottomRow: Float,
        val centerRow: Float,
        val heightPx: Float,
        val peaks: Int,
        /**
         * Blok qadami: shu blok tepasidan ostidagi blok tepasigacha (px). Faqat
         * [requireBlockBelow] rejimida o'lchanadi, aks holda [heightPx].
         */
        val pitchPx: Float = heightPx
    )

    private val avg = FloatArray(rows)
    private val grad = FloatArray(rows)
    private val smooth = FloatArray(rows)
    private val peakRow = FloatArray(rows)
    private val peakStrength = FloatArray(rows)
    private val blockTop = FloatArray(rows)
    private val blockBottom = FloatArray(rows)

    var lastPeaks = 0
        private set

    /**
     * [requireBlockBelow] rejimi: ma'lum blok balandligi va qadami (px, `<= 0` =
     * noma'lum). Berilsa, ulardan farq qiladigan bo'laklar rad etiladi — blok
     * ustida suzib chiqadigan «PERFECT!» (≈58 px) / «+2» yozuvlari va hisob
     * raqamlari harakatlanuvchi blok (≈82 px) o'rniga olinmaydi.
     */
    var expectedHeightPx = 0f
    var expectedPitchPx = 0f

    /**
     * [requireBlockBelow] rejimi: blok markazi kutilgan qator ([expectedCenterRow],
     * `< 0` = noma'lum) dan ko'pi bilan [maxJumpRows] uzoqda bo'lishi kerak.
     * Harakatlanuvchi blok kadrdan kadrga ko'pi bilan bir qadam suriladi; hisob
     * raqami yoki «PERFECT!» yozuvi esa bir necha qadam yuqorida turadi.
     */
    var expectedCenterRow = -1f
    var maxJumpRows = 0f

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
        if (requireBlockBelow) {
            lastPeaks = 0
            return detectBlockOnStack()
        }

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

    /**
     * Haqiqiy o'yinda fon qora va bloklar orasida bir necha piksellik qora
     * tirqish bor. Gradient cho'qqilari bu yerda yaramaydi: to'q blokning pastki
     * qirrasi (55 → 0) keyingi kumush blokning kuchli qirrasi (0 → 246) ichida
     * yutilib ketadi. Shuning uchun tasma **yorqinlik bo'laklariga** ajratiladi:
     * fondan yorug' qatorlar ketma-ketligi — bitta blok (yoki yozuv).
     */
    private fun detectBlockOnStack(): Result? {
        var blocks = 0
        var runStart = -1
        for (r in 0..rows) {
            val bright = r < rows && avg[r] >= BACKGROUND_LUMINANCE
            if (bright && runStart < 0) {
                runStart = r
            } else if (!bright && runStart >= 0) {
                val heightPx = (r - runStart) * rowStep
                if (heightPx in minBlockHeightPx..maxBlockHeightPx) {
                    blockTop[blocks] = runStart.toFloat()
                    blockBottom[blocks] = r.toFloat()
                    blocks++
                }
                runStart = -1
            }
        }
        val onStack = blockOnStack(blocks)
        val tracking = expectedHeightPx > 0f && expectedCenterRow >= 0f && maxJumpRows > 0f
        if (!tracking) return onStack
        // Kuzatuv davom etyapti: kutilgan joy yaqinidagi blok tagida blok bo'lmasa
        // ham olinadi. Tor stackda (211356: 38 px) harakatlanuvchi blok deyarli
        // doim stack ustida emas — tagida blok talab qilinsa, tasma qo'yishdan
        // keyingi kamera surilishiga ergashmay, blok har safar ~1 s yo'qolardi.
        val trackRows = maxJumpRows * TRACK_JUMP_SHARE
        if (onStack != null && abs(onStack.centerRow - expectedCenterRow) <= trackRows) return onStack
        return loneBlock(blocks, trackRows) ?: onStack
    }

    /**
     * Tagida (kichik tirqish bilan) o'xshash balandlikdagi blok turgan eng
     * yuqori blok — stack ustidagi harakatlanuvchi blok. «0» raqami va
     * «PERFECT!»/«+2» yozuvlari ostidagi tirqish katta, shuning uchun o'tmaydi.
     */
    private fun blockOnStack(blocks: Int): Result? {
        for (a in 0 until blocks) {
            val heightPx = (blockBottom[a] - blockTop[a]) * rowStep
            if (expectedHeightPx > 0f &&
                abs(heightPx - expectedHeightPx) > expectedHeightPx * HEIGHT_TOLERANCE
            ) continue
            if (expectedCenterRow >= 0f &&
                abs((blockTop[a] + blockBottom[a]) * 0.5f - expectedCenterRow) > maxJumpRows
            ) continue
            val maxGapRows = max(12f, 0.2f * heightPx) / rowStep
            for (b in a + 1 until blocks) {
                val gap = blockTop[b] - blockBottom[a]
                if (gap > maxGapRows) break
                val below = (blockBottom[b] - blockTop[b]) * rowStep
                if (below < heightPx * 0.6f || below > heightPx * 1.6f) continue
                val pitchPx = (blockTop[b] - blockTop[a]) * rowStep
                if (expectedPitchPx > 0f &&
                    abs(pitchPx - expectedPitchPx) > expectedPitchPx * PITCH_TOLERANCE
                ) continue
                return Result(
                    topRow = blockTop[a],
                    bottomRow = blockBottom[a],
                    centerRow = (blockTop[a] + blockBottom[a]) * 0.5f,
                    heightPx = heightPx,
                    peaks = lastPeaks,
                    pitchPx = pitchPx
                )
            }
        }
        return null
    }

    /** Kutilgan balandlikdagi, kutilgan markazga eng yaqin bo'lak ([trackRows] ichida). */
    private fun loneBlock(blocks: Int, trackRows: Float): Result? {
        var best = -1
        var bestDistance = Float.MAX_VALUE
        for (a in 0 until blocks) {
            val heightPx = (blockBottom[a] - blockTop[a]) * rowStep
            if (abs(heightPx - expectedHeightPx) > expectedHeightPx * HEIGHT_TOLERANCE) continue
            val distance = abs((blockTop[a] + blockBottom[a]) * 0.5f - expectedCenterRow)
            if (distance > trackRows || distance >= bestDistance) continue
            best = a
            bestDistance = distance
        }
        if (best < 0) return null
        val heightPx = (blockBottom[best] - blockTop[best]) * rowStep
        return Result(
            topRow = blockTop[best],
            bottomRow = blockBottom[best],
            centerRow = (blockTop[best] + blockBottom[best]) * 0.5f,
            heightPx = heightPx,
            peaks = lastPeaks,
            pitchPx = if (expectedPitchPx > 0f) expectedPitchPx else heightPx
        )
    }

    private companion object {
        /** Bundan qorong'i qator — fon (qora osmon, yulduzlar tasmada yo'qoladi). */
        const val BACKGROUND_LUMINANCE = 12f
        /**
         * Kuzatuvda blok kutilgan joydan [maxJumpRows] ning shu ulushidan uzoq
         * bo'lmasligi kerak (≈0.75 qadam): bir qadam pastdagi stack bloki olinmaydi.
         */
        const val TRACK_JUMP_SHARE = 0.5f
        /** Kutilgan balandlik / qadamdan ruxsat etilgan farq (nisbat). */
        const val HEIGHT_TOLERANCE = 0.2f
        const val PITCH_TOLERANCE = 0.15f
    }
}
