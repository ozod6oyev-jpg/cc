package uz.perfecttap.inject

/** One block of /proc/bus/input/devices. */
data class InputDeviceNode(
    val name: String,
    val eventNode: String,
    val hasMultiTouch: Boolean
) {
    val devPath: String get() = "/dev/input/$eventNode"
}
