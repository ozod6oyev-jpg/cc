package uz.perfecttap.inject

import org.junit.Assert.assertEquals
import org.junit.Test

class AbsAxisTest {

    private val axis = AbsAxis(0, 4095)

    @Test
    fun mapsDisplayCenterToRawCenter() {
        assertEquals(2048, axis.toRaw(540f, 1080))
    }

    @Test
    fun mapsEdges() {
        assertEquals(0, axis.toRaw(0f, 1080))
        assertEquals(4095, axis.toRaw(1080f, 1080))
    }

    @Test
    fun clampsOutOfBoundsPixels() {
        assertEquals(0, axis.toRaw(-50f, 1080))
        assertEquals(4095, axis.toRaw(5000f, 1080))
    }

    @Test
    fun respectsNonZeroMinimum() {
        val shifted = AbsAxis(100, 1100)
        assertEquals(100, shifted.toRaw(0f, 1000))
        assertEquals(600, shifted.toRaw(500f, 1000))
    }

    @Test
    fun degenerateAxisFallsBackToMin() {
        assertEquals(0, AbsAxis(0, 0).toRaw(500f, 1000))
        assertEquals(0, axis.toRaw(500f, 0))
    }
}
