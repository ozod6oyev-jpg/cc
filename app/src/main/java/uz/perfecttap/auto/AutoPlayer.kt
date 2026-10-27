package uz.perfecttap.auto

import uz.perfecttap.lab.AutoPlacer
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Haqiqiy o'yin uchun avto-kliker «miyasi». Android API yo'q — JVM testlarda
 * tekshiriladi; xizmat ([OverlayService]) faqat kadr va vaqtni beradi.
 *
 * Holatlar (tartib bilan):
 *  · [State.SCANNING] — o'yin maydonini kutadi: bitta qo'zg'almas blok (stack)
 *    va uning ustida borib-kelayotgan, eni stack bilan bir xil blok. Topilishi
 *    bilan skanerlash tugaydi;
 *  · [State.WARMUP]   — qizib olish (tanlangan bo'lsa): tezlik o'rganiladi,
 *    tap yuborilmaydi, «Boshlash» hali faol emas;
 *  · [State.READY]    — maydon topildi va qizildi: faqat shunda «Boshlash» faol;
 *  · [State.RUNNING]  — yangi blok perfect zonaga kelishi bilan tap
 *    rejalashtiradi (qo'yishlar orasida qo'shimcha kutish yo'q).
 *
 * Maydon [Config.lostTimeoutNs] ko'rinmasa (o'yin tugadi, menyu ochildi),
 * qayta skanerlashga o'tiladi ([lostNotice]) — «Boshlash» yana o'chadi.
 *
 * Har bir blok avvalgisidan biroz tezroq yuradi: tezlik har qo'yishdan keyin
 * qaytadan o'lchanadi ([AutoPlacer.onPlaced]).
 *
 * Qo'yish natijasidan ikki narsa o'zini o'zi to'g'rilaydi (stack eni qisqarsa,
 * qaysi tomondan kesilganiga qarab blok erta yoki kech tushgani aniqlanadi):
 *  · [timeBiasNs] — nishon vaqti tuzatishi: o'yin kadrining haqiqiy yoshi va
 *    bosish qaysi kadrda qabul qilinishi (labdagi 27 ms dan farq qiladi);
 *  · [leadNs] — bosishni nishon kadridan qancha oldin yuborish. Blok butun
 *    kadr(lar)ga adashsa, lead yarim kadr qadamda suriladi.
 */
class AutoPlayer(private val config: Config = Config()) {

    data class Config(
        /**
         * Qo'yishdan keyin keyingi tapgacha eng kam oraliq — faqat bir blokka
         * ikki tap ketmasligi uchun. Qo'shimcha kutish yo'q: yangi blok perfect
         * zonaga kelishi bilan bosiladi.
         */
        val settleNs: Long = 120_000_000L,
        /** Kadr kontenti kadr vaqtidan shuncha oldingi holat (labda o'lchangan Δ_stream). */
        val streamDelayNs: Long = 27_000_000L,
        /** Tap yuborilgandan o'yin uni qabul qilguncha (Δ_input + kadr chegarasi). */
        val initialLeadNs: Long = 8_000_000L,
        val minLeadNs: Long = 0L,
        val maxLeadNs: Long = 60_000_000L,
        /** Natijadan lead'ni qanchalik to'g'rilash (0..1) va bir qadamdagi chegara. */
        val biasGain: Float = 0.6f,
        val maxBiasStepNs: Long = 12_000_000L,
        val maxBiasNs: Long = 80_000_000L,
        /** Stack eni shundan kam qisqarsa — perfect. */
        val perfectWidthTolerancePx: Float = 3f,
        /** Skanerlash: shuncha ketma-ket kadr maydon ko'rinishi kerak. */
        val scanFrames: Int = 45,
        /** Skanerlashda ketma-ket shuncha kadr ko'rinmasa, oyna qaytadan to'planadi. */
        val scanMaxMisses: Int = 10,
        /** Skanerlashda harakatlanuvchi blok kamida shuncha yurishi kerak. */
        val scanMinTravelPx: Float = 60f,
        /** Skanerlashda stack markazi shundan ortiq tebranmasligi kerak. */
        val scanStackJitterPx: Float = 4f,
        /** Harakatlanuvchi blok va stack enlari farqi (nisbat). */
        val widthMatchTolerance: Float = 0.15f,
        /** Blok yoki stack shuncha vaqt ko'rinmasa — qayta skanerlash. */
        val lostTimeoutNs: Long = 4_000_000_000L,
        /**
         * Natija (yangi stack eni) tapdan kamida shuncha keyin o'lchanadi —
         * faqat yangi blok harakatlanayotgan va stack eni barqaror kadrlarda.
         */
        val resultDelayNs: Long = 150_000_000L,
        /** Natija uchun ketma-ket shuncha barqaror kadr kerak. */
        val resultStableFrames: Int = 3,
        /** Natija shuncha vaqtda o'lchanmasa, kutish to'xtatiladi. */
        val resultTimeoutNs: Long = 1_500_000_000L,
        /**
         * Eng yaqin displey kadrida blok stack markazidan shundan uzoq bo'lsa,
         * bu o'tish tashlab yuboriladi va blokning keyingi qaytishi kutiladi.
         * Tez blok kadrda ~22 px yuradi: aks holda ba'zi o'tishlarda perfect
         * zonaga birorta kadr ham tushmaydi.
         */
        val maxFrameErrorPx: Float = 3f,
        /**
         * Shuncha vaqt mos kadr topilmasa, eng yaqin kadr qabul qilinadi: chetda
         * to'xtab qaytadigan blokda kadr fazasi har o'tishda takrorlanishi mumkin.
         */
        val maxFrameWaitNs: Long = 1_500_000_000L,
        /**
         * Shuncha kutgandan keyin shu paytgacha ko'rilgan eng yaxshi kadr xatosiga
         * (+1 px) teng imkoniyat qabul qilinadi: faza takrorlansa, kutish behuda.
         */
        val bestFrameAfterNs: Long = 300_000_000L
    )

    enum class State { IDLE, SCANNING, WARMUP, READY, RUNNING }

    /** Bitta qo'yish natijasi — kamera bo'yicha o'lchangan. */
    data class Result(
        val tapIndex: Int,
        val offsetPx: Float,
        val perfect: Boolean,
        val widthPx: Float,
        val leadNs: Long,
        val timeBiasNs: Long,
        /** Natija o'lchovi ishonchsiz bo'lsa (enlar mos emas) — lead o'zgarmaydi. */
        val trusted: Boolean
    )

    private val placer = AutoPlacer(cooldownNs = config.settleNs, learnPlayfield = true)

    var state = State.IDLE
        private set
    var taps = 0
        private set
    var perfectCount = 0
        private set
    var imperfectCount = 0
        private set
    var leadNs = config.initialLeadNs
        private set
    /** Nishon vaqti tuzatishi (musbat = blok kech tushardi → ertaroq nishon). */
    var timeBiasNs = 0L
        private set
    val speedPxPerSec: Float get() = placer.speedPxPerSec
    /** Nega hozir tap yo'q (`AutoPlacer.REASON_*` yoki holat sababi). */
    val lastReason: String
        get() = when {
            state != State.RUNNING -> state.name.lowercase()
            skippedForFrame -> REASON_FRAME_MISALIGNED
            skippedForBounce -> REASON_THROUGH_BOUNCE
            else -> placer.lastReason
        }
    private var skippedForFrame = false
    private var skippedForBounce = false
    private var lastFramePeriodNs = 0L
    /** Mos kadr kutila boshlagan payt (0 = kutilmayapti) va eng yaxshi xato. */
    private var frameWaitSinceNs = 0L
    private var bestFrameErrorPx = Float.MAX_VALUE

    private var warmupEndNs = 0L
    private var warmupNs = 0L
    private var lastSeenNs = 0L

    /** Maydon yo'qolgani uchun qayta skanerlanyapti (topilguncha true). */
    var lostNotice = false
        private set

    // Skanerlash oynasi.
    private val scanCenters = FloatArray(config.scanFrames)
    private val scanStacks = FloatArray(config.scanFrames)
    private var scanCount = 0
    private var scanMisses = 0
    private var scanIndex = 0

    // Tapdan oldingi stack (natijani solishtirish uchun) va kutilayotgan natija.
    private var refStackLeft = Float.NaN
    private var refStackWidth = Float.NaN
    private var plannedSpeed = 0f
    private var pendingResult = false
    private var pendingTapNs = 0L
    private var pendingRefLeft = Float.NaN
    private var pendingRefWidth = Float.NaN
    private var pendingSpeed = 0f
    private var result: Result? = null
    // Natija o'lchovi: ketma-ket barqaror kadrlar va oldingi kadrdagi blok markazi.
    private var resultStable = 0
    private var resultCandidateWidth = Float.NaN
    private var prevCenter = Float.NaN

    /** Oldingi sessiyada o'rganilgan qiymatlar (xizmat saqlab qo'yadi). */
    fun restoreCalibration(leadNs: Long, timeBiasNs: Long) {
        this.leadNs = leadNs.coerceIn(config.minLeadNs, config.maxLeadNs)
        this.timeBiasNs = timeBiasNs.coerceIn(-config.maxBiasNs, config.maxBiasNs)
    }

    fun warmupLeftNs(nowNs: Long): Long =
        if (state == State.WARMUP) (warmupEndNs - nowNs).coerceAtLeast(0L) else 0L

    /**
     * Skanerlashni boshlaydi; maydon topilgach [warmupNs] qizib olinadi
     * (0 = darhol tayyor).
     */
    fun scan(warmupNs: Long) {
        this.warmupNs = warmupNs.coerceAtLeast(0L)
        state = State.SCANNING
        // Yangi sessiya: sanoq noldan (oldingi sessiya hisoblagichi qolmasin).
        taps = 0
        perfectCount = 0
        imperfectCount = 0
        lostNotice = false
        resetScan()
        placer.reset()
        pendingResult = false
    }

    /** «Boshlash»: faqat maydon topilib, qizib olingandan keyin. */
    fun start(nowNs: Long): Boolean {
        if (state != State.READY) return false
        taps = 0
        perfectCount = 0
        imperfectCount = 0
        pendingResult = false
        result = null
        lastSeenNs = nowNs
        state = State.RUNNING
        return true
    }

    /**
     * «To'xtatish»: ishlash to'xtaydi, maydon kuzatilishda qoladi (qayta
     * boshlash mumkin); skanerlash yoki qizib olish bekor qilinadi.
     */
    fun stop() {
        state = when (state) {
            State.RUNNING, State.READY -> State.READY
            else -> State.IDLE
        }
        lostNotice = false
        pendingResult = false
    }

    private fun resetScan() {
        scanCount = 0
        scanIndex = 0
        scanMisses = 0
    }

    /** Oxirgi o'lchangan natija (bir marta qaytariladi). */
    fun consumeResult(): Result? = result.also { result = null }

    /**
     * Bitta kamera kadri (ekran pikselida). Tap kerak bo'lsa, uni yuborish
     * vaqtini (`System.nanoTime()`) qaytaradi, aks holda -1.
     *
     * [vsyncNs] / [framePeriodNs] — displey kadr panjarasi (`<= 0` = noma'lum):
     * o'yin bosishni kadr chegarasida qabul qiladi, shuning uchun blok stack
     * markaziga eng yaqin turgan kadr nishonga olinadi.
     */
    fun onFrame(
        hasBlock: Boolean,
        left: Float,
        right: Float,
        hasStack: Boolean,
        stackLeft: Float,
        stackRight: Float,
        imageTimestampNs: Long,
        nowNs: Long,
        screenWidthPx: Float,
        vsyncNs: Long = 0L,
        framePeriodNs: Long = 0L
    ): Long {
        val width = right - left
        val stackWidth = stackRight - stackLeft
        val seen = hasBlock && hasStack && width > 1f && stackWidth > 1f &&
            abs(width - stackWidth) <= stackWidth * config.widthMatchTolerance

        when (state) {
            State.IDLE -> return -1L
            State.SCANNING -> {
                onScanFrame(seen, (left + right) * 0.5f, (stackLeft + stackRight) * 0.5f, nowNs)
                return -1L
            }
            State.WARMUP, State.READY, State.RUNNING -> Unit
        }

        if (seen) lastSeenNs = nowNs
        if (nowNs - lastSeenNs > config.lostTimeoutNs) {
            // O'yin tugadi yoki maydon yopildi: qaytadan qidiramiz.
            state = State.SCANNING
            lostNotice = true
            resetScan()
            placer.reset()
            pendingResult = false
            return -1L
        }
        if (state == State.WARMUP && nowNs >= warmupEndNs) state = State.READY

        // Yangi blok harakatda (qo'yilgan blok emas) va eni stack bilan teng —
        // shundagina stack o'lchovi haqiqiy: «PERFECT!»/«+2» yozuvlari va
        // kamera surilishi paytidagi kadrlar natijani buzmaydi.
        val center = (left + right) * 0.5f
        val moving = seen && !prevCenter.isNaN() && abs(center - prevCenter) > 0.5f
        prevCenter = if (hasBlock) center else Float.NaN
        if (pendingResult && nowNs - pendingTapNs >= config.resultDelayNs && moving) {
            if (!resultCandidateWidth.isNaN() && abs(stackWidth - resultCandidateWidth) <= 2f) {
                resultStable++
            } else {
                resultStable = 1
            }
            resultCandidateWidth = stackWidth
            if (resultStable >= config.resultStableFrames) measureResult(stackLeft, stackWidth)
        } else if (pendingResult && nowNs - pendingTapNs > config.resultTimeoutNs) {
            // Stack ko'rinmadi: natijasiz davom etamiz, aks holda tap to'xtab qoladi.
            pendingResult = false
        }
        // Tapdan oldingi stack: natija shu bilan solishtiriladi.
        if (!pendingResult && seen) {
            refStackLeft = stackLeft
            refStackWidth = stackWidth
        }
        if (!hasBlock || width <= 1f) return -1L

        val decision = placer.onObservation(
            centerX = (left + right) * 0.5f,
            widthPx = width,
            stackLeft = if (hasStack) stackLeft else 0f,
            stackRight = if (hasStack) stackRight else 0f,
            contentTimestampNs = imageTimestampNs - config.streamDelayNs,
            arrivalNs = nowNs,
            // Pozitsiya tap o'yinga yetib boradigan paytga bashorat qilinadi.
            nowNs = nowNs + leadNs,
            screenWidthPx = screenWidthPx
        )
        skippedForFrame = false
        skippedForBounce = false
        if (framePeriodNs > 0L) lastFramePeriodNs = framePeriodNs
        if (state != State.RUNNING || decision.scheduleInNs < 0L) return -1L
        if (decision.throughBounce) {
            // Keyingi kadrlar qaytishdan keyingi holatni ko'rsatadi — o'shani kutamiz.
            skippedForBounce = true
            return -1L
        }

        val crossingNs = nowNs + leadNs + decision.scheduleInNs - timeBiasNs
        val targetNs = if (vsyncNs > 0L && framePeriodNs > 0L) {
            val frames = ((crossingNs - vsyncNs).toDouble() / framePeriodNs).roundToLong()
            vsyncNs + frames * framePeriodNs
        } else {
            crossingNs
        }
        val frameErrorPx = abs(placer.speedPxPerSec) * abs(targetNs - crossingNs) / 1_000_000_000f
        if (frameWaitSinceNs == 0L) {
            frameWaitSinceNs = nowNs
            bestFrameErrorPx = Float.MAX_VALUE
        }
        val waitedNs = nowNs - frameWaitSinceNs
        val acceptable = frameErrorPx <= config.maxFrameErrorPx ||
            (waitedNs >= config.bestFrameAfterNs && frameErrorPx <= bestFrameErrorPx + 1f) ||
            waitedNs >= config.maxFrameWaitNs
        bestFrameErrorPx = minOf(bestFrameErrorPx, frameErrorPx)
        if (!acceptable) {
            skippedForFrame = true
            return -1L
        }
        plannedSpeed = placer.speedPxPerSec
        return maxOf(nowNs, targetNs - leadNs)
    }

    /** Tap haqiqatan yuborildi ([fireAtNs] — rejalashtirilgan payt). */
    fun onTapFired(fireAtNs: Long, nowNs: Long) {
        taps++
        frameWaitSinceNs = 0L
        placer.onPlaced(nowNs, placedAtNs = fireAtNs + leadNs)
        // Oldingi natija hali o'lchanmagan bo'lsa (bloklar tez ketma-ket), uning
        // tapdan oldingi stacki ham eskirgan: bu tap natijasi o'lchanmaydi.
        val refValid = !pendingResult
        pendingResult = true
        pendingTapNs = nowNs
        pendingRefLeft = if (refValid) refStackLeft else Float.NaN
        pendingRefWidth = if (refValid) refStackWidth else Float.NaN
        pendingSpeed = plannedSpeed
        resultStable = 0
        resultCandidateWidth = Float.NaN
    }

    companion object {
        /** Eng yaqin kadr perfect zonaga tushmaydi — keyingi qaytish kutiladi. */
        const val REASON_FRAME_MISALIGNED = "kadr_mos_emas"
        /** Bashorat chetdan qaytish orqali — qaytishdan keyingi kadr kutiladi. */
        const val REASON_THROUGH_BOUNCE = "qaytish_ichida"
    }

    private fun onScanFrame(seen: Boolean, center: Float, stackCenter: Float, nowNs: Long) {
        if (!seen) {
            // Bir necha kadrlik uzilish (detektor bir zum adashdi) skanerlashni
            // boshidan boshlatmaydi; uzoq uzilish — boshlatadi.
            if (++scanMisses > config.scanMaxMisses) resetScan()
            return
        }
        scanMisses = 0
        scanCenters[scanIndex] = center
        scanStacks[scanIndex] = stackCenter
        scanIndex = (scanIndex + 1) % config.scanFrames
        if (scanCount < config.scanFrames) scanCount++
        if (scanCount < config.scanFrames) return

        var minC = Float.MAX_VALUE
        var maxC = -Float.MAX_VALUE
        var minS = Float.MAX_VALUE
        var maxS = -Float.MAX_VALUE
        for (i in 0 until scanCount) {
            minC = minOf(minC, scanCenters[i])
            maxC = maxOf(maxC, scanCenters[i])
            minS = minOf(minS, scanStacks[i])
            maxS = maxOf(maxS, scanStacks[i])
        }
        if (maxC - minC >= config.scanMinTravelPx && maxS - minS <= config.scanStackJitterPx) {
            // Maydon topildi — skanerlash tugaydi; qizib olish shu yerdan boshlanadi.
            lostNotice = false
            lastSeenNs = nowNs
            warmupEndNs = nowNs + warmupNs
            state = if (warmupNs > 0L) State.WARMUP else State.READY
        }
    }

    private fun measureResult(stackLeft: Float, stackWidth: Float) {
        pendingResult = false
        val refWidth = pendingRefWidth
        val refLeft = pendingRefLeft
        if (refWidth.isNaN() || refLeft.isNaN()) return
        val shrink = refWidth - stackWidth
        // Yangi stack eskisidan keng yoki yarmidan ko'proq qisqargan bo'lsa,
        // kamera boshqa narsani o'lchagan: natija sanaladi, lekin lead tegilmaydi.
        val trusted = shrink > -config.perfectWidthTolerancePx && shrink < refWidth * 0.5f
        if (shrink <= config.perfectWidthTolerancePx) {
            perfectCount++
            result = Result(taps, 0f, true, stackWidth, leadNs, timeBiasNs, trusted)
            return
        }
        imperfectCount++
        // Kesilgan tomon: chap qirra o'ngga siljigan bo'lsa, blok o'ngga tushgan.
        val offset = if (stackLeft > refLeft + shrink * 0.5f) shrink else -shrink
        val speed = pendingSpeed
        if (trusted && abs(speed) > 1f) {
            // Harakat yo'nalishida oldinga tushdi = tap kech ketdi → lead oshadi.
            val errorNs = (offset / speed * 1_000_000_000f).toLong()
            val period = lastFramePeriodNs
            // Kadr panjarasida xato = butun kadrlar (bosish boshqa kadrga tushdi)
            // + kadr ichidagi qoldiq (bashorat xatosi). Butun qismi lead'ni yarim
            // kadr qadamda suradi — bosish qabul oynasining o'rtasiga keladi va
            // chegarada tebranmaydi; qoldiq nishon vaqtini to'g'rilaydi.
            // Yarim kadr atrofidagi xato ikki ma'noli: u kadr xatosi deb faqat
            // aniq bir kadrga yaqin bo'lsa (>= 0.75 kadr) hisoblanadi.
            val frames = if (period > 0L && abs(errorNs) >= period * 3 / 4) {
                Math.round(errorNs.toDouble() / period)
            } else {
                0L
            }
            // Bir kadrdan ko'p xato — kalibrovka emas, kuzatuv/aniqlash xatosi
            // (masalan, yozuv blok deb olingan): lead va τ tegilmaydi.
            if (abs(frames) > 1L) {
                result = Result(taps, offset, false, stackWidth, leadNs, timeBiasNs, false)
                return
            }
            val residualNs = errorNs - frames * period
            val step = (residualNs * config.biasGain).toLong()
                .coerceIn(-config.maxBiasStepNs, config.maxBiasStepNs)
            timeBiasNs = (timeBiasNs + step).coerceIn(-config.maxBiasNs, config.maxBiasNs)
            if (frames != 0L) {
                var lead = leadNs + frames * period / 2
                while (lead > config.maxLeadNs) lead -= period
                while (lead < config.minLeadNs) lead += period
                leadNs = lead
            }
        }
        result = Result(taps, offset, false, stackWidth, leadNs, timeBiasNs, trusted)
    }
}
