package uz.perfecttap.inject

/**
 * Parses `getevent -p` output. Only the ABS_MT ranges we need are extracted.
 *
 * Android's toybox `getevent` prints hex codes, older builds print symbolic
 * names, so both are accepted:
 *
 *   add device 3: /dev/input/event2
 *     name:     "virtio_input_multi_touch_1"
 *                 0035  : value 0, min 0, max 32767, fuzz 0, flat 0, resolution 0
 *
 *   ABS_MT_POSITION_X    : value 0, min 0, max 4095, fuzz 0, flat 0, resolution 0
 */
object GetEventParser {

    private val ADD_DEVICE = Regex("""add device \d+:\s*(\S+)""")
    private val NAME = Regex("""name:\s*"(.*)"""")

    private val POS_X = axisRegex("0035", "ABS_MT_POSITION_X")
    private val POS_Y = axisRegex("0036", "ABS_MT_POSITION_Y")
    private val PRESSURE = axisRegex("003a", "ABS_MT_PRESSURE")

    data class Range(
        val devPath: String,
        val name: String,
        val xMin: Int,
        val xMax: Int,
        val yMin: Int,
        val yMax: Int,
        val pressureMin: Int?,
        val pressureMax: Int?
    )

    fun parse(text: String): List<Range> {
        val blocks = text.split(Regex("""(?=add device \d+:)"""))
        return blocks.mapNotNull { block ->
            val devPath = ADD_DEVICE.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val x = POS_X.find(block) ?: return@mapNotNull null
            val y = POS_Y.find(block) ?: return@mapNotNull null
            val pressure = PRESSURE.find(block)
            Range(
                devPath = devPath,
                name = NAME.find(block)?.groupValues?.get(1).orEmpty(),
                xMin = x.groupValues[1].toInt(),
                xMax = x.groupValues[2].toInt(),
                yMin = y.groupValues[1].toInt(),
                yMax = y.groupValues[2].toInt(),
                pressureMin = pressure?.groupValues?.get(1)?.toIntOrNull(),
                pressureMax = pressure?.groupValues?.get(2)?.toIntOrNull()
            )
        }
    }

    private fun axisRegex(hexCode: String, symbolicName: String) = Regex(
        """(?:\bABS\s*\(0003\):\s*)?""" +
            """(?:$symbolicName|(?<![0-9A-Fa-f])$hexCode(?![0-9A-Fa-f]))""" +
            """\s*:\s*value\s+-?\d+\s*,\s*min\s+(-?\d+)\s*,\s*max\s+(-?\d+)""",
        RegexOption.IGNORE_CASE
    )
}
