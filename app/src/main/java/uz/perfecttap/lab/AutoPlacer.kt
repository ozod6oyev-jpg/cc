package uz.perfecttap.lab

import kotlin.math.abs

/**
 * Avtomatik "1x1" qo'yish qarori — **faqat kameradan ko'ringan** ma'lumot bilan.
 *
 * Har kadrda ikkita o'lchov keladi: harakatlanayotgan blokning markazi va eni,
 * hamda uning ostidagi (stack) blokning chap/o'ng qirralari. Shu ikkisidan
 * tezlik hisoblanadi, kadr kontentining yoshi (hozirgi vaqt - kadrda ko'ringan
 * holat vaqti) uchun pozitsiya oldindan aytiladi va blok stack markazidan
 * o'tadigan **aniq vaqt** qaytariladi. Qo'yish shu vaqtda bajarilsa, ofset
 * nolga yaqin bo'ladi — ya'ni perfect.
 *
 * Hech qanday o'yin ichki holati ishlatilmaydi, shuning uchun laboratoriya
 * natijalarni "aldab" ko'rsatmaydi.
 */
class AutoPlacer(
    private val cooldownNs: Long = 150_000_000L,
    private val maxScheduleNs: Long = 60_000_000L,
    private val maxPredictionNs: Long = 600_000_000L,
    private val minSpeedPxPerSec: Float = 25f,
    /**
     * Haqiqiy o'yinda maydon chegaralari noma'lum: true bo'lsa, ular blok
     * qaytgan joylardan o'rganiladi (labda 6% chekka ma'lum, shuning uchun false).
     */
    private val learnPlayfield: Boolean = false
) {

    /** [scheduleInNs] — qo'yishni shu vaqtdan keyin bajarish kerak (-1 = yo'q). */
    data class Decision(
        val scheduleInNs: Long,
        val predictedCenterX: Float,
        val stackCenterX: Float,
        val speedPxPerSec: Float,
        /**
         * Bashorat chetdan qaytishni o'z ichiga oladi (kadrdan beri blok devorga
         * urilgan). Qaytish modeli o'yinga mos kelmasa (chetda to'xtash), bu
         * bashorat bir kadrgacha adashadi.
         */
        val throughBounce: Boolean = false
    )

    /** Kamera o'lchagan stack markazi (px); o'lchov bo'lmasa NaN. */
    var stackCenterX = Float.NaN
        private set
    var speedPxPerSec = 0f
        private set

    /**
     * Oxirgi kuzatuvda qo'yish nega rejalashtirilmadi (yoki `scheduled`).
     * Matnlar doimiy — o'lchov yo'lida xotira ajratilmaydi.
     */
    var lastReason: String = REASON_IDLE
        private set

    private var prevX = Float.NaN
    private var prevContentNs = 0L
    private val sampleX = FloatArray(SAMPLES)
    private val sampleNs = LongArray(SAMPLES)
    private var sampleCount = 0
    /** Tezlik kattaligi shu blok uchun to'liq oyna bo'yicha o'lchangan. */
    private var speedKnown = false
    /** Joriy harakat yo'nalishi (+1 / -1; 0 = hali noma'lum). */
    private var legDirection = 0
    private var observations = 0
    private var lastPlaceArrivalNs = Long.MIN_VALUE / 2
    private var placedOnce = false
    /**
     * Qo'yishdan oldingi holatni ko'rsatuvchi kadrlar (oqim kechikishi tufayli
     * qo'yishdan keyin ham kelib turadi) e'tiborga olinmaydi.
     */
    private var ignoreContentBeforeNs = Long.MIN_VALUE

    /** Kuzatilgan eng chap / eng o'ng blok qirrasi va qaysi devordan qaytgani. */
    private var edgeMin = Float.MAX_VALUE
    private var edgeMax = -Float.MAX_VALUE
    private var leftWallSeen = false
    private var rightWallSeen = false

    fun reset() {
        edgeMin = Float.MAX_VALUE
        edgeMax = -Float.MAX_VALUE
        leftWallSeen = false
        rightWallSeen = false
        lastReason = REASON_IDLE
        stackCenterX = Float.NaN
        speedPxPerSec = 0f
        prevX = Float.NaN
        prevContentNs = 0L
        sampleCount = 0
        speedKnown = false
        legDirection = 0
        observations = 0
        lastPlaceArrivalNs = Long.MIN_VALUE / 2
        placedOnce = false
        ignoreContentBeforeNs = Long.MIN_VALUE
    }

    /**
     * Feeds one detector result; the caller places when [Decision.scheduleInNs] >= 0.
     *
     * [contentTimestampNs] is when the scene looked like this frame, i.e. the image
     * timestamp corrected by the measured stream delay; [arrivalNs] is when the
     * frame was processed and [nowNs] is the moment of the decision.
     */
    fun onObservation(
        centerX: Float,
        widthPx: Float,
        stackLeft: Float,
        stackRight: Float,
        contentTimestampNs: Long,
        arrivalNs: Long,
        nowNs: Long,
        screenWidthPx: Float
    ): Decision {
        // Kadr hali qo'yishdan oldingi sahnani ko'rsatyapti: eski blok va eski
        // stack yangi tezlik namunalariga aralashsa, keyingi bashorat buziladi.
        if (contentTimestampNs < ignoreContentBeforeNs) {
            lastReason = REASON_STALE
            return Decision(-1L, centerX, stackCenterX, speedPxPerSec)
        }
        if (stackRight - stackLeft > 1f) {
            stackCenterX = (stackLeft + stackRight) * 0.5f
        }
        if (widthPx <= 1f) {
            lastReason = REASON_NO_WIDTH
            return Decision(-1L, centerX, stackCenterX, speedPxPerSec)
        }
        if (stackCenterX.isNaN()) {
            lastReason = REASON_NO_STACK
            return Decision(-1L, centerX, stackCenterX, speedPxPerSec)
        }

        updateSpeed(contentTimestampNs, centerX)
        observations++
        if (learnPlayfield) {
            edgeMin = minOf(edgeMin, centerX - widthPx * 0.5f)
            edgeMax = maxOf(edgeMax, centerX + widthPx * 0.5f)
        }

        // The frame shows the block where it was at contentTimestampNs; the stream
        // can be hundreds of ms old, so predict forward to the current moment.
        val ageNs = (nowNs - contentTimestampNs).coerceIn(0L, maxPredictionNs)
        // The block reflects off the playfield edges, and the captured frame can be
        // hundreds of ms old, so the straight-line prediction is folded back into
        // the playfield: this survives bounces that happened inside the delay.
        val lo = if (learnPlayfield && leftWallSeen) {
            edgeMin + widthPx * 0.5f
        } else {
            screenWidthPx * PLAYFIELD_MARGIN + widthPx * 0.5f
        }
        val hi = if (learnPlayfield && rightWallSeen) {
            edgeMax - widthPx * 0.5f
        } else {
            screenWidthPx * (1f - PLAYFIELD_MARGIN) - widthPx * 0.5f
        }
        val raw = centerX + speedPxPerSec * (ageNs / 1_000_000_000f)
        val folded = fold(raw, lo, hi)
        val predicted = folded.first
        val direction = folded.second * if (speedPxPerSec < 0f) -1f else 1f

        // Right after a placement (and at session start) there is no usable speed
        // yet: one frame of movement can be a bounce. Waiting for the full window
        // costs a couple of frames but removes the first-cut mistakes.
        if (!speedKnown) {
            lastReason = REASON_SAMPLES
            return Decision(-1L, predicted, stackCenterX, speedPxPerSec)
        }
        // The very first placement waits for a whole sweep of samples: during the
        // first frames the tracker is still settling, and a wrong first block would
        // skew the stack that everything after it is measured against.
        if (!placedOnce && observations < WARMUP_OBSERVATIONS) {
            lastReason = REASON_FIRST_SWEEP
            return Decision(-1L, predicted, stackCenterX, speedPxPerSec)
        }
        if (placedOnce && arrivalNs - lastPlaceArrivalNs < cooldownNs) {
            lastReason = REASON_COOLDOWN
            return Decision(-1L, predicted, stackCenterX, speedPxPerSec)
        }
        if (abs(speedPxPerSec) < minSpeedPxPerSec) {
            lastReason = REASON_TOO_SLOW
            return Decision(-1L, predicted, stackCenterX, speedPxPerSec)
        }

        // Time until the block's centre reaches the measured stack centre.
        val directionSpeed = direction * abs(speedPxPerSec)
        if (directionSpeed == 0f) {
            lastReason = REASON_TOO_SLOW
            return Decision(-1L, predicted, stackCenterX, speedPxPerSec)
        }
        val crossingNs = ((stackCenterX - predicted) / directionSpeed * 1_000_000_000f).toLong()
        if (crossingNs !in 0L..maxScheduleNs) {
            // Musbat: qo'yish joyi hali uzoq; manfiy: blok allaqachon o'tib ketgan.
            lastReason = if (crossingNs < 0L) REASON_MISSED else REASON_FAR
            return Decision(-1L, predicted, stackCenterX, speedPxPerSec)
        }
        lastReason = REASON_SCHEDULED
        return Decision(crossingNs, predicted, stackCenterX, speedPxPerSec, throughBounce = raw != predicted)
    }

    /**
     * Velocity from the frame's own timestamps (the pipeline delivers frames in
     * bursts, so arrival deltas would be noise).
     *
     * A block keeps the same speed until it is placed; at the playfield edge only
     * the direction flips. So the magnitude is fitted over samples of one leg,
     * and a bounce keeps it, flips the sign and starts a new leg from the newest
     * sample. A fit across a bounce (or across the frame where the block is
     * clamped to the edge) would mix the two legs and give a wrong, jumping
     * speed — which in the lab moved the tap by a whole frame.
     */
    private fun updateSpeed(contentTimestampNs: Long, centerX: Float) {
        if (!prevX.isNaN()) {
            val dt = (contentTimestampNs - prevContentNs) / 1_000_000_000f
            val dx = centerX - prevX
            if (dt > 0.25f) {
                // Long gap: the motion may have changed in any way.
                sampleCount = 0
                speedKnown = false
                speedPxPerSec = 0f
                legDirection = 0
            } else if (dt > 0f && abs(dx) >= BOUNCE_MIN_PX) {
                val pairDirection = if (dx > 0f) 1 else -1
                if (legDirection != 0 && pairDirection != legDirection) {
                    // Bounce: same speed, other direction, new leg.
                    if (legDirection > 0) rightWallSeen = true else leftWallSeen = true
                    if (speedKnown) speedPxPerSec = -speedPxPerSec
                    sampleCount = 0
                }
                legDirection = pairDirection
                if (!speedKnown && dt >= 0.004f) speedPxPerSec = dx / dt
            }
        }
        prevX = centerX
        prevContentNs = contentTimestampNs

        if (sampleCount < SAMPLES) {
            sampleX[sampleCount] = centerX
            sampleNs[sampleCount] = contentTimestampNs
            sampleCount++
        } else {
            for (i in 0 until SAMPLES - 1) {
                sampleX[i] = sampleX[i + 1]
                sampleNs[i] = sampleNs[i + 1]
            }
            sampleX[SAMPLES - 1] = centerX
            sampleNs[SAMPLES - 1] = contentTimestampNs
        }

        if (sampleCount < MIN_FIT_SAMPLES) return
        val fit = fitVelocity()
        if (fit == 0f) return
        // Every sample must sit on the fitted line: an edge-clamped frame or a
        // mismatched timestamp shows up as a large residual and is not trusted.
        var worst = 0f
        for (i in 0 until sampleCount) {
            val onLine = fitMeanX + fit * ((sampleNs[i] - fitMeanNs) / 1_000_000_000.0).toFloat()
            worst = maxOf(worst, abs(onLine - sampleX[i]))
        }
        if (worst > FIT_TOLERANCE_PX) return
        if (speedKnown || sampleCount >= SAMPLES) {
            speedPxPerSec = fit
            speedKnown = true
        }
    }

    private var fitMeanNs = 0.0
    private var fitMeanX = 0f

    private fun fitVelocity(): Float {
        var meanT = 0.0
        var meanX = 0.0
        val count = sampleCount
        for (i in 0 until count) {
            meanT += sampleNs[i]
            meanX += sampleX[i]
        }
        meanT /= count
        meanX /= count
        fitMeanNs = meanT
        fitMeanX = meanX.toFloat()
        var num = 0.0
        var den = 0.0
        for (i in 0 until count) {
            val dt = sampleNs[i] - meanT
            num += dt * (sampleX[i] - meanX)
            den += dt * dt
        }
        if (den <= 1.0) return 0f
        return (num / den * 1_000_000_000.0).toFloat()
    }

    /**
     * Folds [x] into [lo, hi] with reflection and returns the folded position and
     * the direction (+1 / -1) the block is moving there.
     */
    private fun fold(x: Float, lo: Float, hi: Float): Pair<Float, Float> {
        val span = hi - lo
        if (span <= 1f) return Pair(x, 1f)
        val period = 2f * span
        var t = (x - lo) % period
        if (t < 0f) t += period
        return if (t <= span) Pair(lo + t, 1f) else Pair(hi - (t - span), -1f)
    }

    /**
     * Called right after the block was actually placed. [placedAtNs] is the tap's
     * own time (content clock); frames showing the scene before it are ignored.
     */
    fun onPlaced(arrivalNs: Long, placedAtNs: Long = arrivalNs) {
        placedOnce = true
        lastPlaceArrivalNs = arrivalNs
        ignoreContentBeforeNs = placedAtNs
        // The top of the stack changed (a cut moves its centre): re-measure it.
        stackCenterX = Float.NaN
        // The block respawns at the stack centre, so the previous velocity is stale.
        prevX = Float.NaN
        prevContentNs = 0L
        sampleCount = 0
        speedKnown = false
        legDirection = 0
        speedPxPerSec = 0f
    }

    companion object {
        /** Nega tap yo'q — qisqa, doimiy matnlar (ekranda va CSV'da ko'rinadi). */
        const val REASON_IDLE = "kutilyapti"
        const val REASON_SCHEDULED = "scheduled"
        const val REASON_NO_WIDTH = "blok_eni_yoq"
        const val REASON_NO_STACK = "stack_yoq"
        const val REASON_SAMPLES = "namuna_yetmayapti"
        const val REASON_FIRST_SWEEP = "birinchi_supurish"
        const val REASON_COOLDOWN = "cooldown"
        const val REASON_TOO_SLOW = "sekin"
        const val REASON_MISSED = "o_tib_ketdi"
        const val REASON_FAR = "uzoq"
        const val REASON_STALE = "eski_kadr"

        /** Lab playfield margin, the same 6% the game geometry uses. */
        private const val PLAYFIELD_MARGIN = 0.06f
        const val SAMPLES = 6
        /** Observations the tracker wants before its first placement. */
        const val WARMUP_OBSERVATIONS = 10
        const val FIT_TOLERANCE_PX = 3f
        /** Shuncha namuna bo'lsa, ma'lum tezlik yangi oyoqda aniqlashtiriladi. */
        private const val MIN_FIT_SAMPLES = 3
        /** Yo'nalish almashinuvi shovqin emas, deb hisoblanadigan siljish. */
        private const val BOUNCE_MIN_PX = 2f
    }
}
