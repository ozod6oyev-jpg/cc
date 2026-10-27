package uz.perfecttap.inject

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EvdevEncoderTest {

    @Test
    fun structSizesMatchAbi() {
        assertEquals(24, EvdevEncoder(is64Bit = true).structSize)
        assertEquals(16, EvdevEncoder(is64Bit = false).structSize)
    }

    @Test
    fun encodesLittleEndian64BitLayout() {
        val bytes = EvdevEncoder(is64Bit = true).event(Evdev.EV_ABS, Evdev.ABS_MT_POSITION_X, 4095)
        assertEquals(24, bytes.size)

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0L, buffer.long)
        assertEquals(0L, buffer.long)
        assertEquals(Evdev.EV_ABS, buffer.short.toInt())
        assertEquals(Evdev.ABS_MT_POSITION_X, buffer.short.toInt())
        assertEquals(4095, buffer.int)
    }

    @Test
    fun encodesLittleEndian32BitLayout() {
        val bytes = EvdevEncoder(is64Bit = false).event(Evdev.EV_KEY, Evdev.BTN_TOUCH, 1)
        assertEquals(16, bytes.size)

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, buffer.int)
        assertEquals(0, buffer.int)
        assertEquals(Evdev.EV_KEY, buffer.short.toInt())
        assertEquals(Evdev.BTN_TOUCH, buffer.short.toInt())
        assertEquals(1, buffer.int)
    }

    @Test
    fun downSequenceEndsWithSynReportAndCarriesTrackingId() {
        val encoder = EvdevEncoder(is64Bit = true)
        val events = decode(encoder.down(1200, 3400, 7), 24, true)

        assertEquals(7, events.size)
        assertEquals(Triple(Evdev.EV_ABS, Evdev.ABS_MT_SLOT, 0), events[0])
        assertEquals(Triple(Evdev.EV_ABS, Evdev.ABS_MT_TRACKING_ID, 7), events[1])
        assertEquals(Triple(Evdev.EV_ABS, Evdev.ABS_MT_POSITION_X, 1200), events[2])
        assertEquals(Triple(Evdev.EV_ABS, Evdev.ABS_MT_POSITION_Y, 3400), events[3])
        assertEquals(Triple(Evdev.EV_KEY, Evdev.BTN_TOUCH, 1), events[4])
        assertEquals(Triple(Evdev.EV_KEY, Evdev.BTN_TOOL_FINGER, 1), events[5])
        assertEquals(Triple(Evdev.EV_SYN, Evdev.SYN_REPORT, 0), events[6])
    }

    @Test
    fun downSequenceCanIncludePressure() {
        val encoder = EvdevEncoder(is64Bit = false)
        val events = decode(encoder.down(1, 2, 3, pressure = 128), 16, false)
        assertEquals(8, events.size)
        assertEquals(Triple(Evdev.EV_ABS, Evdev.ABS_MT_PRESSURE, 128), events[4])
        assertEquals(Triple(Evdev.EV_SYN, Evdev.SYN_REPORT, 0), events.last())
    }

    @Test
    fun upSequenceReleasesTrackingId() {
        val encoder = EvdevEncoder(is64Bit = true)
        val events = decode(encoder.up(), 24, true)

        assertEquals(5, events.size)
        assertEquals(Triple(Evdev.EV_ABS, Evdev.ABS_MT_TRACKING_ID, -1), events[1])
        assertEquals(Triple(Evdev.EV_KEY, Evdev.BTN_TOUCH, 0), events[2])
        assertEquals(Triple(Evdev.EV_SYN, Evdev.SYN_REPORT, 0), events.last())
    }

    @Test
    fun printfEscapesAreBinarySafe() {
        val bytes = byteArrayOf(0x00, 0xFF.toByte(), 0x5A, 0x0A)
        assertEquals("\\000\\377\\132\\012", bytes.toPrintfEscapes())
    }

    @Test
    fun trackingIdWraps() {
        assertEquals(2, nextTrackingId(1))
        assertEquals(1, nextTrackingId(65_000))
    }

    private fun decode(bytes: ByteArray, size: Int, is64: Boolean): List<Triple<Int, Int, Int>> {
        assertTrue(bytes.size % size == 0)
        return (0 until bytes.size / size).map { index ->
            val buffer = ByteBuffer.wrap(bytes, index * size, size).order(ByteOrder.LITTLE_ENDIAN)
            if (is64) {
                buffer.long
                buffer.long
            } else {
                buffer.int
                buffer.int
            }
            Triple(buffer.short.toInt(), buffer.short.toInt(), buffer.int)
        }
    }
}
