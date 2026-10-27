package uz.perfecttap.inject

/**
 * Parses /proc/bus/input/devices and picks the touchscreen node.
 *
 * Example block:
 *   I: Bus=0018 Vendor=04e8 Product=... Version=...
 *   N: Name="sec_touchscreen"
 *   H: Handlers=event4
 *   B: ABS=260800011000003 0
 */
object ProcInputDevicesParser {

    private val NAME = Regex("""N:\s*Name="(.*)"""")
    private val HANDLERS = Regex("""H:\s*Handlers=(.*)""")
    private val ABS = Regex("""B:\s*ABS=(.+)""")
    private val EVENT = Regex("""\bevent(\d+)\b""")

    private const val ABS_MT_POSITION_X = 0x35
    private const val ABS_MT_POSITION_Y = 0x36

    fun parse(text: String): List<InputDeviceNode> {
        // Devices are normally separated by blank lines, but splitting on the
        // "I: Bus=" header is robust to both layouts.
        val blocks = text.split(Regex("(?m)(?=^I:)"))
        return blocks.mapNotNull { block ->
            val name = NAME.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val handlers = HANDLERS.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            // Handlers may list several nodes ("event4 mouse0"); the event node is the one we write to.
            val event = EVENT.find(handlers) ?: return@mapNotNull null
            val absWords = ABS.find(block)
                ?.groupValues?.get(1)
                ?.trim()
                ?.split(Regex("""\s+"""))
                .orEmpty()
            InputDeviceNode(
                name = name,
                eventNode = "event${event.groupValues[1]}",
                hasMultiTouch = hasAbs(absWords, ABS_MT_POSITION_X) &&
                    hasAbs(absWords, ABS_MT_POSITION_Y)
            )
        }
    }

    /** Highest scoring multitouch device, or null when there is none. */
    fun pickBest(devices: List<InputDeviceNode>): InputDeviceNode? =
        devices.filter { it.hasMultiTouch && !isVirtual(it) }.maxByOrNull { score(it) }

    private fun isVirtual(device: InputDeviceNode): Boolean {
        val name = device.name.lowercase()
        return listOf(
            "uinput", "virtual", "virtio", "goldfish", "qemu",
            "keyboard", "mouse", "gpio-keys", "power button", "volume",
            "headset", "hdmi", "jack"
        ).any { it in name }
    }

    /**
     * Works across vendors: Samsung's sec_touchscreen, generic "touchscreen",
     * Goodix/FocalTech/Synaptics/Novatek panels, and plain "touch" nodes.
     */
    private fun score(device: InputDeviceNode): Int {
        val name = device.name.lowercase()
        var score = 10
        val bonuses = listOf(
            "touchscreen" to 40,
            "touch panel" to 35,
            "sec_touch" to 30,
            "touch" to 30,
            "digitizer" to 30,
            "synaptics" to 20,
            "goodix" to 20,
            "focaltech" to 20,
            "novatek" to 20,
            "atmel" to 20,
            "ilitek" to 20,
            "himax" to 20,
            "fts" to 15,
            "panel" to 10
        )
        for ((keyword, bonus) in bonuses) {
            if (keyword in name) score += bonus
        }
        val penalties = listOf(
            "gpio" to 40, "key" to 25, "button" to 25, "mouse" to 25,
            "uinput" to 100, "virtual" to 100, "virtio" to 100,
            "goldfish" to 100, "qemu" to 100
        )
        for ((keyword, penalty) in penalties) {
            if (keyword in name) score -= penalty
        }
        return score
    }

    private fun hasAbs(words: List<String>, code: Int): Boolean {
        val word = words.getOrNull(code / 64)?.toULongOrNull(16) ?: return false
        return (word shr (code % 64)) and 1uL == 1uL
    }
}
