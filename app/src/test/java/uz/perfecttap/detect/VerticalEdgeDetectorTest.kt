package uz.perfecttap.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerticalEdgeDetectorTest {

    private val stripWidth = 5
    private val rowStep = 2

    /** Builds a strip where each sampled row has the given luminance. */
    private fun strip(rows: Int, luma: (row: Int) -> Int): IntArray {
        val pixels = IntArray(stripWidth * rows)
        for (r in 0 until rows) {
            val v = luma(r).coerceIn(0, 255)
            for (c in 0 until stripWidth) {
                pixels[r * stripWidth + c] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        return pixels
    }

    @Test
    fun findsTopAndBottomOfMovingBlock() {
        val rows = 200
        // Block from y=200..290 px, i.e. rows 100..145.
        val pixels = strip(rows) { r ->
            when {
                r < 100 -> 8
                r < 145 -> 230
                else -> 70
            }
        }
        val detector = VerticalEdgeDetector(stripWidth, rowStep, rows)
        val result = detector.detect(pixels)!!

        assertEquals(100f, result.topRow, 1.0f)
        assertEquals(145f, result.bottomRow, 1.0f)
        assertEquals(122.5f, result.centerRow, 1.0f)
        assertEquals(90f, result.heightPx, 2f)
        assertEquals(244.5f, result.centerRow * rowStep, 2f)
    }

    @Test
    fun returnsFirstPairNotLowerStackEdges() {
        val rows = 220
        // Moving block rows 100..145, then stack edges every ~45 rows.
        val pixels = strip(rows) { r ->
            when {
                r < 100 -> 8
                r < 145 -> 230
                r < 160 -> 70
                r < 205 -> 40
                else -> 90
            }
        }
        val detector = VerticalEdgeDetector(stripWidth, rowStep, rows)
        val result = detector.detect(pixels)!!

        assertEquals(100f, result.topRow, 1.0f)
        assertEquals(145f, result.bottomRow, 1.0f)
    }

    @Test
    fun findsLowContrastBoundaryBelowWhiteBlock() {
        val rows = 200
        // White moving block on a light-gray top block: the boundary is only 23 levels.
        val pixels = strip(rows) { r ->
            when {
                r < 100 -> 8
                r < 145 -> 255
                else -> 232
            }
        }
        val detector = VerticalEdgeDetector(stripWidth, rowStep, rows)
        val result = detector.detect(pixels)!!

        assertEquals(100f, result.topRow, 1.0f)
        assertEquals(145f, result.bottomRow, 1.5f)
    }

    @Test
    fun flatStripHasNoResult() {
        val detector = VerticalEdgeDetector(stripWidth, rowStep, 100)
        assertNull(detector.detect(strip(100) { 120 }))
        assertEquals(0, detector.lastPeaks)
    }

    @Test
    fun ignoresTooThinBlock() {
        // 10 px tall block is below the 30 px minimum.
        val rows = 100
        val pixels = strip(rows) { r -> if (r in 40..44) 250 else 5 }
        val detector = VerticalEdgeDetector(stripWidth, rowStep, rows)
        assertNull(detector.detect(pixels))
    }

    @Test
    fun handlesFractionalBlockBoundary() {
        val rows = 200
        // Soft top edge: row 100 is half covered, 101 is full.
        val pixels = strip(rows) { r ->
            when {
                r < 100 -> 10
                r == 100 -> 120
                r < 146 -> 230
                else -> 70
            }
        }
        val detector = VerticalEdgeDetector(stripWidth, rowStep, rows)
        val result = detector.detect(pixels)!!

        assertTrue(result.topRow in 99.5f..101.5f)
        assertEquals(146f, result.bottomRow, 1.2f)
    }

    /**
     * Haqiqiy o'yin: perfect qo'yishdan keyin «PERFECT!» yozuvi (≈58 px) yangi
     * harakatlanuvchi blok (82 px) ustida kichik tirqish bilan suzadi. Shakl
     * noma'lum bo'lsa yozuv olinadi; ma'lum balandlik/qadam bilan — blok.
     */
    @Test
    fun knownBlockShapeRejectsThePerfectLabelAboveTheBlock() {
        val rows = 400
        val pixels = strip(rows) { r ->
            when (r) {
                in 100 until 129 -> 200 // yozuv: 58 px
                in 133 until 174 -> 60  // harakatlanuvchi blok: 82 px
                in 176 until 217 -> 230 // stack
                in 219 until 260 -> 230
                else -> 0
            }
        }
        val detector = VerticalEdgeDetector(stripWidth, rowStep, rows, requireBlockBelow = true)
        assertEquals(100f, detector.detect(pixels)!!.topRow, 0.5f)

        detector.expectedHeightPx = 82f
        detector.expectedPitchPx = 86f
        val result = detector.detect(pixels)!!
        assertEquals(133f, result.topRow, 0.5f)
        assertEquals(82f, result.heightPx, 1f)
        assertEquals(86f, result.pitchPx, 1f)
    }

    /**
     * Shakli blokka o'xshash narsa (kattalashib chiqayotgan yozuv, raqam) bir
     * necha qadam yuqorida: kutilgan Y oynasidan tashqarida — rad etiladi.
     */
    @Test
    fun expectedCenterWindowRejectsFarBlockLikeShapes() {
        val rows = 500
        val pixels = strip(rows) { r ->
            when (r) {
                in 20 until 61 -> 200   // blokka o'xshash yozuv (82 px)
                in 63 until 104 -> 200  // uning ostidagi bo'lak
                in 233 until 274 -> 60  // harakatlanuvchi blok
                in 276 until 317 -> 230 // stack
                in 319 until 360 -> 230
                else -> 0
            }
        }
        val detector = VerticalEdgeDetector(stripWidth, rowStep, rows, requireBlockBelow = true)
        detector.expectedHeightPx = 82f
        detector.expectedPitchPx = 86f
        assertEquals(20f, detector.detect(pixels)!!.topRow, 0.5f)

        detector.expectedCenterRow = 253.5f
        detector.maxJumpRows = 86f * 1.5f / rowStep
        assertEquals(233f, detector.detect(pixels)!!.topRow, 0.5f)
    }

    /**
     * Haqiqiy o'yin (211356): stack 38 px ga torayganda harakatlanuvchi blok
     * tasma ustunida yolg'iz — tagida blok yo'q. Kuzatuvda (kutilgan joy
     * ma'lum) u baribir olinishi kerak, aks holda tasma kamera surilishiga
     * ergashmaydi; pastroqdagi keng stack bloklari esa olinmaydi.
     */
    @Test
    fun trackedBlockWithoutAStackBelowIsStillFound() {
        val rows = 500
        val pixels = strip(rows) { r ->
            when (r) {
                in 240 until 281 -> 200 // harakatlanuvchi blok, kamera bilan surilgan
                in 326 until 367 -> 230 // ancha pastdagi keng stack bloklari
                in 369 until 410 -> 230
                else -> 0
            }
        }
        val detector = VerticalEdgeDetector(stripWidth, rowStep, rows, requireBlockBelow = true)
        detector.expectedHeightPx = 82f
        detector.expectedPitchPx = 86f
        detector.maxJumpRows = 86f * 1.5f / rowStep
        // Kuzatuvsiz: tagida blok turgan eng yuqori bo'lak (stack).
        assertEquals(326f, detector.detect(pixels)!!.topRow, 0.5f)

        // Blok oldingi kadrda 6 px yuqorida edi.
        detector.expectedCenterRow = 257.5f
        val result = detector.detect(pixels)!!
        assertEquals(240f, result.topRow, 0.5f)
        assertEquals(86f, result.pitchPx, 0.5f)

        // Kutilgan joydan bir qadam uzoqda yolg'iz bo'lak — kuzatuv emas.
        detector.expectedCenterRow = 217.5f
        assertNull(detector.detect(pixels))
    }
}
