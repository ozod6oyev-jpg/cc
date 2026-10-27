package uz.perfecttap.auto

import java.io.Closeable
import java.util.concurrent.locks.LockSupport

/**
 * Bosishni aniq vaqtda yuboradigan alohida oqim.
 *
 * Har yangi reja eskisini almashtiradi (eng so'nggi kadr aniqroq). Reja
 * vaqtidan [SPIN_NS] oldingacha oqim uxlaydi, qolganini aylanib kutadi —
 * `Handler.postAtTime` ning millisekund xatosi 2700 px/s da ~3 px bo'lardi.
 *
 * Bosish yuborilgach rejalashtiruvchi «qurolsizlanadi»: [rearm] chaqirilguncha
 * yangi rejalar qabul qilinmaydi, shuning uchun bir blok uchun ikki tap ketmaydi.
 */
class TapScheduler(
    private val onFire: (fireAtNs: Long) -> Unit,
    private val threadSetup: () -> Unit = {}
) : Closeable {

    private val lock = Object()
    private var plannedAtNs = 0L
    @Volatile
    private var planId = 0L
    private var armed = true
    @Volatile
    private var running = true

    private val thread = Thread({ loop() }, "pt-tap").apply {
        isDaemon = true
        start()
    }

    /** Rejani almashtiradi; qurolsiz bo'lsa false. */
    fun plan(fireAtNs: Long): Boolean = synchronized(lock) {
        if (!armed || !running) return false
        plannedAtNs = fireAtNs
        planId++
        lock.notifyAll()
        true
    }

    fun cancel() = synchronized(lock) {
        plannedAtNs = 0L
        planId++
        lock.notifyAll()
    }

    /** Oldingi tap natijasi qayd etildi — keyingi rejalar qabul qilinadi. */
    fun rearm() = synchronized(lock) { armed = true }

    override fun close() {
        running = false
        cancel()
        thread.interrupt()
    }

    private fun loop() {
        threadSetup()
        while (running) {
            var id: Long
            var at: Long
            synchronized(lock) {
                while (running && plannedAtNs == 0L) {
                    try {
                        lock.wait()
                    } catch (_: InterruptedException) {
                        if (!running) return
                    }
                }
                id = planId
                at = plannedAtNs
            }
            if (!running) return
            if (!waitUntil(at, id)) continue
            val fire = synchronized(lock) {
                if (planId != id || plannedAtNs == 0L) {
                    false
                } else {
                    plannedAtNs = 0L
                    armed = false
                    true
                }
            }
            if (fire) runCatching { onFire(at) }
        }
    }

    /** Reja vaqtigacha kutadi; reja almashsa yoki bekor qilinsa false. */
    private fun waitUntil(atNs: Long, id: Long): Boolean {
        while (running) {
            val remain = atNs - System.nanoTime()
            if (remain <= 0L) return true
            if (remain > SPIN_NS) {
                synchronized(lock) {
                    if (planId != id) return false
                    val sleep = remain - SPIN_NS
                    try {
                        lock.wait(sleep / 1_000_000L, (sleep % 1_000_000L).toInt())
                    } catch (_: InterruptedException) {
                        if (!running) return false
                    }
                    if (planId != id) return false
                }
            } else {
                if (planId != id) return false
                LockSupport.parkNanos(50_000L)
            }
        }
        return false
    }

    private companion object {
        const val SPIN_NS = 1_500_000L
    }
}
