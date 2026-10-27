package uz.perfecttap.inject

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GetEventParserTest {

    private val sample = """
        add device 1: /dev/input/event4
          bus:      0018
          name:     "sec_touchscreen"
          events:
            KEY (0001): BTN_TOUCH
            ABS (0003):
              ABS_MT_POSITION_X    : value 0, min 0, max 4095, fuzz 0, flat 0, resolution 0
              ABS_MT_POSITION_Y    : value 0, min 0, max 4095, fuzz 0, flat 0, resolution 0
              ABS_MT_PRESSURE      : value 0, min 0, max 255, fuzz 0, flat 0, resolution 0

        add device 2: /dev/input/event0
          name:     "gpio-keys"
          events:
            ABS_MT_POSITION_X    : value 0, min 0, max 10, fuzz 0, flat 0, resolution 0
            ABS_MT_POSITION_Y    : value 0, min 0, max 10, fuzz 0, flat 0, resolution 0
    """.trimIndent()

    @Test
    fun parsesRangesPerDevice() {
        val ranges = GetEventParser.parse(sample)
        assertEquals(2, ranges.size)

        val touch = ranges.first { it.devPath == "/dev/input/event4" }
        assertEquals("sec_touchscreen", touch.name)
        assertEquals(0, touch.xMin)
        assertEquals(4095, touch.xMax)
        assertEquals(0, touch.yMin)
        assertEquals(4095, touch.yMax)
        assertEquals(255, touch.pressureMax)
    }

    // Real output from Android's toybox getevent (emulator, Android 14): hex codes.
    private val toyboxSample = """
        add device 3: /dev/input/event2
          name:     "virtio_input_multi_touch_1"
          events:
            KEY (0001): 0141  014b
            ABS (0003): 0000  : value 0, min 0, max 32767, fuzz 0, flat 0, resolution 0
                        0035  : value 0, min 0, max 32767, fuzz 0, flat 0, resolution 0
                        0036  : value 0, min 0, max 32767, fuzz 0, flat 0, resolution 0
                        003a  : value 0, min 0, max 1024, fuzz 0, flat 0, resolution 0
    """.trimIndent()

    @Test
    fun parsesToyboxHexCodes() {
        val range = GetEventParser.parse(toyboxSample).single()
        assertEquals("/dev/input/event2", range.devPath)
        assertEquals("virtio_input_multi_touch_1", range.name)
        assertEquals(32767, range.xMax)
        assertEquals(32767, range.yMax)
        assertEquals(1024, range.pressureMax)
    }

    @Test
    fun parsesHexCodeOnTheFirstAbsLine() {
        val text = """
            add device 5: /dev/input/event5
              name:     "touchscreen"
              events:
                ABS (0003): 0035  : value 0, min 0, max 4095, fuzz 0, flat 0, resolution 0
                            0036  : value 0, min 0, max 4095, fuzz 0, flat 0, resolution 0
        """.trimIndent()
        val range = GetEventParser.parse(text).single()
        assertEquals(4095, range.xMax)
        assertEquals(4095, range.yMax)
        assertNull(range.pressureMax)
    }

    @Test
    fun missingPressureIsNull() {
        val touch = GetEventParser.parse(sample).first { it.devPath == "/dev/input/event0" }
        assertNull(touch.pressureMax)
        assertNull(touch.pressureMin)
    }

    @Test
    fun ignoresDeviceWithoutMtAxes() {
        val text = """
            add device 1: /dev/input/event1
              name:     "gpio-keys"
        """.trimIndent()
        assertEquals(0, GetEventParser.parse(text).size)
    }
}
