package uz.perfecttap.inject

import kotlin.math.roundToInt

/** Raw evdev axis range (from EVIOCGABS, via `getevent -p`). */
data class AbsAxis(val min: Int, val max: Int) {

    val range: Int get() = max - min

    /** Pixel coordinate -> raw evdev coordinate. */
    fun toRaw(pixel: Float, displayPixels: Int): Int {
        if (displayPixels <= 0 || range <= 0) return min
        val clamped = pixel.coerceIn(0f, displayPixels.toFloat())
        return (min + clamped / displayPixels * range).roundToInt().coerceIn(min, max)
    }
}
