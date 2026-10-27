package uz.perfecttap.inject

import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Rootsiz bosish yo'li.
 *
 * Telefon root qilinmagan bo'lsa, foydalanuvchi [Shizuku] xizmatini ishga
 * tushiradi; u shell huquqi bilan ishlaydi va biz `InputManager` orqali
 * `injectInputEvent` chaqira olamiz. Bu evdev node yozishdan farq qiladi —
 * koordinatalar ekran pikselida, o'zgartirish kerak emas — va odatda 1-5 ms
 * ketadi (AccessibilityService `dispatchGesture` esa o'nlab ms).
 *
 * Shizuku o'rnatilmagan yoki ruxsat berilmagan bo'lsa, sinf hech narsa
 * qilmaydi va [available] false qaytaradi.
 */
object ShizukuInjector {

    data class State(
        val running: Boolean,
        val permissionGranted: Boolean,
        val version: String?
    ) {
        val canInject: Boolean get() = running && permissionGranted
    }

    fun state(): State {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!running) return State(running = false, permissionGranted = false, version = null)
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val version = runCatching { Shizuku.getVersion().toString() }.getOrNull()
        return State(running = true, permissionGranted = granted, version = version)
    }

    /** Shizuku ruxsat so'rovi; natija `Shizuku.OnRequestPermissionResultListener` ga keladi. */
    fun requestPermission(requestCode: Int): Boolean = runCatching {
        if (!Shizuku.pingBinder()) return false
        if (Shizuku.isPreV11()) {
            // Eski Shizuku'da ruxsat so'rovi yo'q — foydalanuvchi qo'lda beradi.
            false
        } else {
            Shizuku.requestPermission(requestCode)
            true
        }
    }.getOrDefault(false)

    fun openTapChannel(): TapChannel? = runCatching {
        if (!state().canInject) return null
        val manager = inputManager() ?: return null
        ShizukuTapChannel(manager)
    }.getOrNull()

    /** `IInputManager` proxy: Shizuku binder wrapper orqali shell nomidan. */
    private fun inputManager(): Any? = runCatching {
        val service = SystemServiceHelper.getSystemService("input")
        val binder: IBinder = ShizukuBinderWrapper(service)
        Class.forName("android.hardware.input.IInputManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
    }.getOrNull()
}

/**
 * Bosishni `InputManager.injectInputEvent` bilan yuboradi.
 *
 * Koordinatalar ekran pikselida (0..kenglik, 0..balandlik) — evdev kanallari kabi
 * qayta hisoblash kerak emas.
 */
class ShizukuTapChannel(private val inputManager: Any) : TapChannel {

    override val description: String = "Shizuku (shell → injectInputEvent)"

    /** Binder uzilib qolsa (Shizuku to'xtadi) bosishlar shu yerda sanaladi. */
    var failedTaps = 0
        private set

    private val injectMethod = inputManager.javaClass
        .getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        .apply { isAccessible = true }

    override fun tap(x: Float, y: Float, holdMs: Long) {
        val downTime = SystemClock.uptimeMillis()
        val down = event(downTime, downTime, MotionEvent.ACTION_DOWN, x, y)
        var up: MotionEvent? = null
        try {
            inject(down)
            Thread.sleep(holdMs)
            // UP o'sha gestga tegishli (bir xil downTime) va ushlab turishdan
            // keyingi vaqt bilan yuboriladi.
            up = event(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
            inject(up)
        } catch (_: Throwable) {
            // Shizuku to'xtab qolgan bo'lishi mumkin: ilova yiqilmaydi, holat
            // keyingi tekshiruvda yangilanadi.
            failedTaps++
        } finally {
            down.recycle()
            up?.recycle()
        }
    }

    private fun event(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
            setSource(InputDevice.SOURCE_TOUCHSCREEN)
        }

    private fun inject(event: MotionEvent) {
        // mode 0 = INJECT_INPUT_EVENT_MODE_ASYNC: qaytishni kutmasdan yuboradi,
        // shuning uchun o'lchangan Δ_input faqat tizim navbatini o'z ichiga oladi.
        injectMethod.invoke(inputManager, event, 0)
    }

    override fun close() = Unit
}
