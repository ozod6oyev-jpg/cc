package uz.perfecttap.inject

import android.content.Context
import uz.perfecttap.core.Displays
import uz.perfecttap.core.RootManager

/** Bosish kanalini ochish: avval root (evdev), keyin Shizuku. Bloklaydi — IO oqimida chaqiring. */
object TapChannels {

    enum class Failure { NONE, SHIZUKU_NOT_RUNNING, SHIZUKU_DENIED }

    data class Opened(val channel: TapChannel?, val failure: Failure)

    fun open(context: Context, root: RootManager = RootManager()): Opened {
        if (root.isGranted()) {
            val device = TouchDeviceFinder(root).discover().device
            if (device != null) {
                val size = Displays.size(context)
                TapChannelFactory(root).open(device, size.width, size.height).channel?.let {
                    return Opened(it, Failure.NONE)
                }
            }
        }
        val state = ShizukuInjector.state()
        if (!state.canInject) {
            return Opened(
                null,
                if (state.running) Failure.SHIZUKU_DENIED else Failure.SHIZUKU_NOT_RUNNING
            )
        }
        return Opened(ShizukuInjector.openTapChannel(), Failure.NONE)
    }
}
