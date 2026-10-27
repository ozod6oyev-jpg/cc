package uz.perfecttap.lab

import android.view.Choreographer

/**
 * "Clicker" — mahsulot tomoni. O'yin haqida hech narsa bilmaydi: faqat kameradan
 * kelgan o'lchovlarni ko'radi va kerakli vaqtda ekranning belgilangan nuqtasiga
 * **bosish yuboradi**. Bosishni o'yin o'zi qabul qiladi va o'z qoidasi bilan hal
 * qiladi, shuning uchun laboratoriya bilan clicker bir-biriga moslashmaydi.
 *
 * Bosish kadr soati bilan yuboriladi va o'z vaqt belgisini olib yuradi —
 * xuddi haqiqiy sensorli ekran yuboradigan hodisa kabi. O'yin shu vaqtni
 * ishlatadi, ya'ni clicker "qo'yish" buyrug'ini bermaydi.
 */
class Clicker(
    /** Bosish yuboriladigan nuqta (ekran markazi); hali tayyor bo'lmasa null. */
    private val tapPoint: () -> FloatArray?,
    /** Ko'rinish eni (px) — oldindan aytish uchun o'yin maydoni chegaralari. */
    private val screenWidthPx: () -> Float,
    /** Bosishni qabul qiluvchi tomon (o'yin ko'rinishi) va uning vaqt belgisi. */
    private val deliverTap: (x: Float, y: Float, tapTimeNs: Long) -> Unit
) {

    private val placer = AutoPlacer()
    private val choreographer = Choreographer.getInstance()
    private var plannedAtNs = 0L

    /** Yuborilgan bosishlar soni. */
    var taps = 0
        private set

    /** Nega oxirgi kuzatuvda tap rejalashtirilmadi (`AutoPlacer.REASON_*`). */
    val lastReason: String get() = placer.lastReason

    /** Kamera o'lchagan stack markazi (px); o'lchov bo'lmasa NaN. */
    val stackCenterX: Float get() = placer.stackCenterX

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val at = plannedAtNs
            if (at == 0L) return
            if (frameTimeNanos >= at) {
                plannedAtNs = 0L
                val point = tapPoint()
                if (point != null) {
                    taps++
                    deliverTap(point[0], point[1], at)
                    // Blok stack markazida qayta tug'iladi: eski tezlik va namunalar
                    // endi yaroqsiz, ularni tashlab yubormasak keyingi bashorat xato
                    // bo'lib, blok kesiladi.
                    placer.onPlaced(System.nanoTime(), at)
                }
            } else {
                choreographer.postFrameCallback(this)
            }
        }
    }

    fun reset() {
        choreographer.removeFrameCallback(frameCallback)
        plannedAtNs = 0L
        taps = 0
        placer.reset()
    }

    fun stop() {
        choreographer.removeFrameCallback(frameCallback)
        plannedAtNs = 0L
    }

    /**
     * Bitta kamera o'lchovi. [contentTimestampNs] — kadrda ko'ringan holat qaysi
     * vaqtga tegishli ekani (oqim kechikishi ayirilgan), [delayUncertain] — shu
     * kadrning kechikishi ishonchsiz o'lchangan.
     */
    fun onObservation(
        centerX: Float,
        widthPx: Float,
        stackLeft: Float,
        stackRight: Float,
        contentTimestampNs: Long
    ) {
        if (tapPoint() == null) return
        val nowNs = System.nanoTime()
        val decision = placer.onObservation(
            centerX = centerX,
            widthPx = widthPx,
            stackLeft = stackLeft,
            stackRight = stackRight,
            contentTimestampNs = contentTimestampNs,
            arrivalNs = nowNs,
            nowNs = nowNs,
            screenWidthPx = screenWidthPx()
        )
        if (decision.scheduleInNs < 0L) return
        // Yangi qaror eskisini almashtiradi: eng so'nggi o'lchov aniqroq.
        plannedAtNs = nowNs + decision.scheduleInNs
        choreographer.removeFrameCallback(frameCallback)
        choreographer.postFrameCallback(frameCallback)
    }
}
