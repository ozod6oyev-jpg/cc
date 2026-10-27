package uz.perfecttap.inject

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcInputDevicesParserTest {

    private val sample = """
        I: Bus=0019 Vendor=0001 Product=0001 Version=0100
        N: Name="gpio-keys"
        P: Phys=gpio-keys/input0
        H: Handlers=event0
        B: EV=3
        B: ABS=0

        I: Bus=0018 Vendor=04e8 Product=7401 Version=0100
        N: Name="sec_touchscreen"
        P: Phys=18-0048/input0
        H: Handlers=event4
        B: EV=b
        B: ABS=60000000000000

        I: Bus=0003 Vendor=0000 Product=0000 Version=0111
        N: Name="uinput-remote"
        H: Handlers=event7
        B: ABS=60000000000000
    """.trimIndent() + "\n"

    @Test
    fun parsesNameEventNodeAndMultitouch() {
        val devices = ProcInputDevicesParser.parse(sample)
        assertEquals(3, devices.size)

        val keys = devices.first { it.name == "gpio-keys" }
        assertEquals("event0", keys.eventNode)
        assertEquals("/dev/input/event0", keys.devPath)
        assertFalse(keys.hasMultiTouch)

        val touch = devices.first { it.name == "sec_touchscreen" }
        assertTrue(touch.hasMultiTouch)
    }

    @Test
    fun picksSamsungTouchscreen() {
        val best = ProcInputDevicesParser.pickBest(ProcInputDevicesParser.parse(sample))
        assertNotNull(best)
        assertEquals("sec_touchscreen", best!!.name)
        assertEquals("/dev/input/event4", best.devPath)
    }

    @Test
    fun onlyOneAxisIsNotMultitouch() {
        val text = """
            N: Name="vibrator"
            H: Handlers=event2
            B: ABS=20000000000000
        """.trimIndent()
        val device = ProcInputDevicesParser.parse(text).single()
        assertFalse(device.hasMultiTouch)
        assertNull(ProcInputDevicesParser.pickBest(listOf(device)))
    }

    @Test
    fun parsesDevicesWithoutBlankLinesBetweenThem() {
        val text = """
            I: Bus=0019 Vendor=0001
            N: Name="gpio-keys"
            H: Handlers=event0
            B: ABS=0
            I: Bus=0018 Vendor=04e8
            N: Name="sec_touchscreen"
            H: Handlers=event4
            B: ABS=60000000000000
        """.trimIndent()
        val devices = ProcInputDevicesParser.parse(text)
        assertEquals(2, devices.size)
        assertEquals("sec_touchscreen", ProcInputDevicesParser.pickBest(devices)?.name)
    }

    @Test
    fun skipsVirtualMultitouchDevices() {
        val text = """
            I: Bus=0003 Vendor=0000
            N: Name="uinput-remote"
            H: Handlers=event7
            B: ABS=60000000000000
        """.trimIndent()
        val device = ProcInputDevicesParser.parse(text).single()
        assertTrue(device.hasMultiTouch)
        assertNull(ProcInputDevicesParser.pickBest(listOf(device)))
    }

    @Test
    fun readsMultiWordBitmapFromThe32BitKernel() {
        // 32-bit kernel: two 32-bit words, most significant first.
        val text = """
            N: Name="sec_touchscreen"
            H: Handlers=event4
            B: ABS=2658000 3
        """.trimIndent()
        assertTrue(ProcInputDevicesParser.parse(text).single().hasMultiTouch)
    }

    @Test
    fun lowBitsAloneAreNotMultitouch() {
        // ABS_X/ABS_Y only (a single-touch panel or a joystick).
        val text = """
            N: Name="touch"
            H: Handlers=event4
            B: ABS=0 3
        """.trimIndent()
        assertFalse(ProcInputDevicesParser.parse(text).single().hasMultiTouch)
    }

    @Test
    fun ignoresBlocksWithoutEventHandler() {
        val text = """
            N: Name="something"
            H: Handlers=mouse0
            B: ABS=60000000000000
        """.trimIndent()
        assertTrue(ProcInputDevicesParser.parse(text).isEmpty())
    }
}
