package uz.perfecttap.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import uz.perfecttap.capture.ScanlineReader
import uz.perfecttap.capture.VerticalStripReader
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Haqiqiy o'yin skrinshotlarida (loyiha ildizida) avto-kliker aniqlash zanjiri:
 * Y-tasma → harakatlanuvchi blok (X) → uning ostidagi stack. CaptureService'ning
 * butun ekran (ROI = 0) rejimidagi parametrlari bilan bir xil.
 */
class GameScreenshotDetectionTest {

    private class Detection(
        val bandY: Int,
        val left: Float,
        val right: Float,
        val stackLeft: Float,
        val stackRight: Float
    )

    private fun detect(file: File): Detection? {
        // javax.imageio Android kompilyatsiya yo'lida yo'q, JVM'da esa bor.
        val image = Class.forName("javax.imageio.ImageIO")
            .getMethod("read", File::class.java)
            .invoke(null, file)
        val type = image.javaClass
        val w = type.getMethod("getWidth").invoke(image) as Int
        val h = type.getMethod("getHeight").invoke(image) as Int
        val getRgb = type.getMethod(
            "getRGB", Int::class.java, Int::class.java, Int::class.java, Int::class.java,
            IntArray::class.java, Int::class.java, Int::class.java
        )
        val pixels = getRgb.invoke(image, 0, 0, w, h, null, 0, w) as IntArray
        // ImageReader buferi kabi: RGBA baytlar, qator ketma-ket.
        val buffer = ByteBuffer.allocate(w * h * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until h) for (x in 0 until w) {
            val argb = pixels[y * w + x]
            buffer.put((argb ushr 16).toByte())
            buffer.put((argb ushr 8).toByte())
            buffer.put(argb.toByte())
            buffer.put((argb ushr 24).toByte())
        }
        buffer.flip()

        val band = 41
        val bandLimit = ((h * 0.95f).toInt() - band / 2).coerceAtLeast(band / 2)
        val strip = VerticalStripReader(81, 2, (h * 0.22f).toInt(), bandLimit + band / 2)
        val vertical = VerticalEdgeDetector(81, 2, strip.rows, requireBlockBelow = true)
        if (strip.read(buffer, w * 4, 4, w / 2, w, h) != strip.rows) return null
        val found = vertical.detect(strip.pixels) ?: return null
        val bandY = (strip.startY + found.centerRow * strip.rowStep).roundToInt()
        val movingBottom = strip.startY + found.bottomRow * strip.rowStep

        val slice = ScanlineReader(w, band)
        val moving = EdgeDetector(w)
        val rows = slice.readBand(buffer, w * 4, 4, bandY, w, h)
        if (!moving.detect(slice.pixels, rows)) return null
        val left = moving.bestLeft
        val right = moving.bestRight

        val stack = EdgeDetector(w)
        stack.expectedWidthPx = right - left
        val stackY = (movingBottom + found.heightPx * 0.5f).roundToInt()
        val stackRows = slice.readBand(buffer, w * 4, 4, stackY, w, h)
        if (!stack.detect(slice.pixels, stackRows)) return null
        println("${file.name}: bandY=$bandY h=${found.heightPx} moving=$left..$right stack=${stack.bestLeft}..${stack.bestRight}")
        return Detection(bandY, left, right, stack.bestLeft, stack.bestRight)
    }

    private fun screenshot(name: String): File {
        val file = File("../$name")
        assumeTrue("skrinshot yo'q: $name", file.exists())
        return file
    }

    /** Panel ochiq, skanerlash: to'q blok kumush stack ustida, «0» raqami tepada. */
    @Test
    fun findsTheDarkMovingBlockAboveTheSilverStack() {
        val d = detect(screenshot("Screenshot_20261027_084119.jpg"))
        assertNotNull("maydon topilmadi", d)
        d!!
        // To'q blok: x ≈ 260..905, kumush stack: x ≈ 216..863 (skrinshotdan).
        assertEquals(260f, d.left, 8f)
        assertEquals(905f, d.right, 8f)
        assertEquals(216f, d.stackLeft, 8f)
        assertEquals(863f, d.stackRight, 8f)
        assertEquals(1633f, d.bandY.toFloat(), 25f)
    }

    /** Birinchi skrinshot: panel yo'q, «0» raqami va ikki blok. */
    @Test
    fun findsTheBlocksOnTheStartScreen() {
        val d = detect(screenshot("Screenshot.jpg"))
        assertNotNull("maydon topilmadi", d)
        d!!
        // To'q blok yuqorida (x ≈ 101..748), kumush pastda (x ≈ 217..864).
        assertEquals(101f, d.left, 8f)
        assertEquals(748f, d.right, 8f)
        assertEquals(217f, d.stackLeft, 8f)
        assertEquals(864f, d.stackRight, 8f)
    }

    /** «PERFECT!» va «+2» yozuvlari blok ustida turibdi. */
    @Test
    fun ignoresThePerfectLabelAboveTheStack() {
        val d = detect(screenshot("Screenshot_20261021_160712_Telegram.jpg"))
        assertNotNull("maydon topilmadi", d)
        d!!
        // Eng yuqori (harakatlanuvchi) blok: x ≈ 146..755, y ≈ 1283..1360.
        assertEquals(146f, d.left, 10f)
        assertEquals(755f, d.right, 10f)
        assertEquals(1321f, d.bandY.toFloat(), 25f)
    }

    /** Skrinshotni ImageReader buferi ko'rinishida yuklaydi (RGBA). */
    private fun load(file: File): Triple<ByteBuffer, Int, Int> {
        val image = Class.forName("javax.imageio.ImageIO")
            .getMethod("read", File::class.java)
            .invoke(null, file)
        val type = image.javaClass
        val w = type.getMethod("getWidth").invoke(image) as Int
        val h = type.getMethod("getHeight").invoke(image) as Int
        val pixels = type.getMethod(
            "getRGB", Int::class.java, Int::class.java, Int::class.java, Int::class.java,
            IntArray::class.java, Int::class.java, Int::class.java
        ).invoke(image, 0, 0, w, h, null, 0, w) as IntArray
        val buffer = ByteBuffer.allocate(w * h * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (p in pixels) {
            buffer.put((p ushr 16).toByte())
            buffer.put((p ushr 8).toByte())
            buffer.put(p.toByte())
            buffer.put((p ushr 24).toByte())
        }
        buffer.flip()
        return Triple(buffer, w, h)
    }

    private fun verticalAt(file: File, centerX: Int): VerticalEdgeDetector.Result? {
        val (buffer, w, h) = load(file)
        val band = 41
        val bandLimit = ((h * 0.95f).toInt() - band / 2).coerceAtLeast(band / 2)
        val strip = VerticalStripReader(81, 2, (h * 0.22f).toInt(), bandLimit + band / 2)
        strip.read(buffer, w * 4, 4, centerX, w, h)
        return VerticalEdgeDetector(81, 2, strip.rows, requireBlockBelow = true)
            .detect(strip.pixels)
            ?.let { it.copy(topRow = strip.startY + it.topRow * 2, bottomRow = strip.startY + it.bottomRow * 2) }
    }

    /**
     * Haqiqiy avto sessiya kadri (hisob 187): bloklar ensiz (~155 px), harakatlanuvchi
     * blok (x 762..917) stack (x 572..728) ustida emas. Stack ustunida tasma stack
     * tepasini va blok qadamini beradi; blok tagida esa hech narsa yo'q — shu holat
     * uchun CaptureService qadamni eslab qoladi.
     */
    @Test
    fun narrowBlocksAwayFromTheStack() {
        val file = screenshot("auto-20261027-090159-frame-1.png")
        val atStack = verticalAt(file, 650)
        assertNotNull(atStack)
        atStack!!
        assertEquals(1240f, atStack.topRow, 6f)
        assertEquals(1314f, atStack.bottomRow, 6f)
        assertEquals(86f, atStack.pitchPx, 6f)

        assertEquals(null, verticalAt(file, 840))
    }
}
