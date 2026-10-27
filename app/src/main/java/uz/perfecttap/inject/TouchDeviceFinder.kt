package uz.perfecttap.inject

import uz.perfecttap.core.RootManager

/**
 * Stage 1 discovery: find the touchscreen node from /proc/bus/input/devices,
 * then read its raw ABS ranges with `getevent -p`.
 *
 * If the proc file is unavailable or lists no usable multitouch node (layout
 * differences across vendors/ROMs), the device list from `getevent -p` is used
 * as a fallback.
 */
class TouchDeviceFinder(private val root: RootManager) {

    data class Result(
        val device: TouchDevice?,
        val devices: List<InputDeviceNode>,
        val log: List<String>
    )

    fun discover(): Result {
        val log = mutableListOf<String>()

        val proc = root.exec("cat /proc/bus/input/devices")
        var devices = if (proc.success) ProcInputDevicesParser.parse(proc.stdout) else emptyList()
        if (!proc.success) {
            log += "⚠ /proc/bus/input/devices o'qilmadi (kod=${proc.code}) — getevent zaxira usuli"
        } else {
            log += "Topilgan input qurilmalar: ${devices.size}"
            devices.forEach { log += "  · ${it.name} → ${it.devPath} (MT=${it.hasMultiTouch})" }
        }

        var best = ProcInputDevicesParser.pickBest(devices)
        var ranges = emptyList<GetEventParser.Range>()

        if (best != null) {
            ranges = readAllRanges(log)
        } else {
            log += "Ro'yxatda sensorli ekran yo'q — getevent ro'yxati tekshiriladi"
            ranges = readAllRanges(log)
            val nodes = ranges.map {
                InputDeviceNode(
                    name = it.name.ifBlank { it.devPath },
                    eventNode = it.devPath.substringAfterLast('/'),
                    hasMultiTouch = true
                )
            }
            devices = nodes
            best = ProcInputDevicesParser.pickBest(nodes)
            if (best == null) {
                log += "✗ Multitouch sensorli ekran topilmadi"
                return Result(null, devices, log)
            }
        }

        log += "Tanlandi: ${best.name} (${best.devPath})"

        val range = ranges.firstOrNull { it.devPath == best.devPath }
            ?: ranges.firstOrNull { it.name == best.name }
            ?: ranges.singleOrNull()

        val xAxis = range?.let { AbsAxis(it.xMin, it.xMax) } ?: AbsAxis(0, 4095).also {
            log += "⚠ getevent o'qilmadi — X uchun 0..4095 taxmin qilindi"
        }
        val yAxis = range?.let { AbsAxis(it.yMin, it.yMax) } ?: AbsAxis(0, 4095).also {
            log += "⚠ getevent o'qilmadi — Y uchun 0..4095 taxmin qilindi"
        }

        log += "ABS X: ${xAxis.min}..${xAxis.max}, Y: ${yAxis.min}..${yAxis.max}"
        range?.let { log += "Pressure max: ${it.pressureMax ?: "yo'q"}" }

        return Result(
            TouchDevice(
                node = best,
                xAxis = xAxis,
                yAxis = yAxis,
                pressureMax = range?.pressureMax
            ),
            devices,
            log
        )
    }

    private fun readAllRanges(log: MutableList<String>): List<GetEventParser.Range> {
        // `timeout` guards against getevent versions that keep streaming.
        val result = root.exec("timeout 3 getevent -p")
        val ranges = GetEventParser.parse(result.stdout)
        if (ranges.isEmpty()) {
            log += "! getevent chiqishi tahlil qilinmadi (code=${result.code})"
        }
        return ranges
    }
}
