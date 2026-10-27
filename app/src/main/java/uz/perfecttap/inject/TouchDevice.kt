package uz.perfecttap.inject

/** Touchscreen node plus its raw axis ranges. */
data class TouchDevice(
    val node: InputDeviceNode,
    val xAxis: AbsAxis,
    val yAxis: AbsAxis,
    val pressureMax: Int? = null
)
