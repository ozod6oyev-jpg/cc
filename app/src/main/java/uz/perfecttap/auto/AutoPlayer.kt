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
 * Qo'yish natijasidan bosish vaqti o'zini o'zi to'g'rilaydi (stack eni
 * qisqarsa, qaysi tomondan kesilganiga qarab blok erta yoki kech tushgani
 * aniqlanadi): [leadNs] — bosishni nishon kadridan qancha oldin yuborish —
 * kichik qadamlarda suriladi, xato bir tomonga takrorlansa qadam kattalashadi.
 * Lead chegaradan chiqsa, qoldiq [timeBiasNs] ga (nishon vaqti tuzatishi) o'tadi.
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
        /**
         * Tap yuborilgandan o'yin uni qabul qilguncha (Δ_input + kadr chegarasi)
         * va nishon tuzatishining boshlang'ich qiymati. Haqiqiy o'yinda (211356)
         * lead 12.17 / τ −3.69 ms bilan 70 ta blok ketma-ket perfect bo'lgan;
         * perfect oynasining markazi shu atrofda.
         */
        val initialLeadNs: Long = 12_170_000L,
        val initialTimeBiasNs: Long = -3_690_000L,
        val minLeadNs: Long = 0L,
        val maxLeadNs: Long = 60_000_000L,
        /**
         * Natijadan bosish vaqtini qanchalik to'g'rilash (0..1). Bir qadam
         * [calibrationStepNs] bilan cheklanadi; xato ketma-ket bir tomonga
         * bo'lsa chegara ikki barobardan [maxBiasStepNs] gacha o'sadi.
         *
         * Haqiqiy o'yinda (211356) xato kadrlarga bo'lingan (±16..29 ms) va
         * ishorasi tasodifiy: har xatoda lead'ni bir kadrga surish uni 12 dan
         * 37 ms gacha «sayr» qildirdi va erta tushishlarning yarmini o'zi keltirdi.
         */
        val biasGain: Float = 0.6f,
        val calibrationStepNs: Long = 2_000_000L,
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
        /**
         * Natijadagi xato shuncha kadrdan katta bo'lsa — o'lchov/kuzatuv adashgan,
         * lead va τ tegilmaydi.
         */
        val maxCalibrationErrorFrames: Long = 4L,
        /**
         * Blok shuncha ketma-ket kadr joyidan qimirlamasa, u harakatlanuvchi blok
         * emas (yozuv, raqam yoki qo'yilgan blok): tap rejalashtirilmaydi va
         * rejadagisi bekor qilinadi.
         */
        val staticCancelFrames: Int = 2,
        /**
         * Joriy oyoqda shuncha kamera namunasi bo'lmaguncha tap yo'q ([AutoPlacer]).
         * Real oqimda (194525) 4+ namunadan bashorat 10 ms ichida; o'tish ko'pincha
         * oyoqning 4–5-kadrida keladi, katta talab o'tishlarni behuda o'tkazadi.
         */
        val minLegSamples: Int = 5,
        /** Natija shuncha vaqtda o'lchanmasa, kutish to'xtatiladi. */
        val resultTimeoutNs: Long = 1_500_000_000L,
        /**
         * Eng yaqin displey kadrida blok stack markazidan shundan uzoq bo'lsa,
         * bu o'tish tashlab yuboriladi va blokning keyingi qaytishi kutiladi.
         * Tez blok kadrda ~22 px yuradi: aks holda ba'zi o'tishlarda perfect
         * zonaga birorta kadr ham tushmaydi. Haqiqiy o'yinda tezlik ~1300 px/s
         * da to'xtaydi (kadrda 10.8 px, xato ≤ 5.4 px) va perfect chegarasi
         * ~20 px: 3 px talab o'tishlarning yarmini behuda o'tkazib, har tapga
         * ~0.8 s qo'shardi (211356).
         */
        val maxFrameErrorPx: Float = 5f,
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
        val trusted: Boolean,
        /**
         * Vaqt xatosi (ms): musbat = kech (blok harakat yo'nalishida oldinga
         * o'tib ketdi), manfiy = erta. Perfect yoki tezlik noma'lum — NaN.
         */
        val errorMs: Float = Float.NaN,
        /** Tap paytidagi blok tezligi (px/s, ishora = yo'nalish). */
        val speedPxPerSec: Float = 0f
    )

    private val placer = AutoPlacer(
        cooldownNs = config.settleNs,
        learnPlayfield = true,
        minLegSamples = config.minLegSamples,
        speedPrior = true
    )

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
    var timeBiasNs = config.initialTimeBiasNs
        private set
    val speedPxPerSec: Float get() = placer.speedPxPerSec
    /** Nega hozir tap yo'q (`AutoPlacer.REASON_*` yoki holat sababi). */
    val lastReason: String
        get() = when {
            state != State.RUNNING -> state.name.lowercase()
            untrackedNotice -> REASON_UNTRACKED
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
    /** Oxirgi rejadagi tap paytidagi tezlik (px/s, ishora = yo'nalish). */
    val plannedSpeedPxPerSec: Float get() = plannedSpeed
    /** Oxirgi rejadagi tap: tanlangan kadrdagi kutilgan xato (px) va kutilgan vaqt. */
    var plannedFrameErrorPx = 0f
        private set
    var plannedWaitNs = 0L
        private set
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
    private var staticFrames = 0
    private var missedFrames = 0
    private var untrackedNotice = false
    /** Oxirgi xatolar ishorasi (+1 kech / -1 erta / 0) va ketma-ket soni. */
    private var lastErrorSign = 0
    private var errorStreak = 0

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
     * vaqtini (`System.nanoTime()`) qaytaradi, aks holda -1; [CANCEL] — kuzatuv
     * harakatlanuvchi blokdan ajraldi, rejadagi tap bekor qilinishi kerak.
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
        // Joyida turgan yoki ko'rinmay qolgan blok: kuzatuv harakatlanuvchi
        // blokda emas. Kamera kadri ba'zan takrorlanadi (bir xil L), shuning
        // uchun bir nechta ketma-ket kadr talab qilinadi.
        staticFrames = if (hasBlock && !prevCenter.isNaN() && abs(center - prevCenter) <= 0.5f) {
            staticFrames + 1
        } else if (hasBlock) 0 else staticFrames
        missedFrames = if (hasBlock) 0 else missedFrames + 1
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
        val untracked = staticFrames >= config.staticCancelFrames ||
            missedFrames >= config.staticCancelFrames + 1
        if (state == State.RUNNING && untracked) {
            // Eski bashorat bo'yicha rejalashtirilgan tap endi ishonchsiz.
            if (hasBlock && width > 1f) feedPlacer(left, right, width, hasStack, stackLeft, stackRight, imageTimestampNs, nowNs, screenWidthPx)
            frameWaitSinceNs = 0L
            untrackedNotice = true
            return CANCEL
        }
        untrackedNotice = false
        if (!hasBlock || width <= 1f) return -1L

        val decision = feedPlacer(left, right, width, hasStack, stackLeft, stackRight, imageTimestampNs, nowNs, screenWidthPx)
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
        plannedFrameErrorPx = frameErrorPx
        plannedWaitNs = waitedNs
        return maxOf(nowNs, targetNs - leadNs)
    }

    private fun feedPlacer(
        left: Float,
        right: Float,
        width: Float,
        hasStack: Boolean,
        stackLeft: Float,
        stackRight: Float,
        imageTimestampNs: Long,
        nowNs: Long,
        screenWidthPx: Float
    ) = placer.onObservation(
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
        /** Kuzatuv harakatlanuvchi blokda emas — rejadagi tap bekor qilinadi. */
        const val REASON_UNTRACKED = "kuzatuv_yoq"
        /** [onFrame] qiymati: rejalashtirilgan tapni bekor qilish kerak. */
        const val CANCEL = -2L
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
            lastErrorSign = 0
            errorStreak = 0
            perfectCount++
            result = Result(taps, 0f, true, stackWidth, leadNs, timeBiasNs, trusted, Float.NaN, pendingSpeed)
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
            // Juda katta xato — kalibrovka emas, kuzatuv/aniqlash xatosi (masalan,
            // yozuv blok deb olingan): lead va τ tegilmaydi. Noto'g'ri saqlangan τ 2-4
            // kadrlik muntazam xato beradi — u o'rganilishi shart, shuning uchun
            // chegara bir kadr emas, [maxCalibrationErrorFrames].
            if (period > 0L && abs(errorNs) > config.maxCalibrationErrorFrames * period + period / 2) {
                result = Result(taps, offset, false, stackWidth, leadNs, timeBiasNs, false, errorMs(offset, speed), speed)
                return
            }
            // Bitta xato tasodifiy bo'lishi mumkin — kichik qadam; bir tomonga
            // takrorlansa (kalibrovka haqiqatan siljigan) qadam 2x, 4x o'sadi.
            val sign = if (errorNs > 0L) 1 else -1
            errorStreak = if (sign == lastErrorSign) errorStreak + 1 else 1
            lastErrorSign = sign
            val capNs = (config.calibrationStepNs shl (errorStreak - 1).coerceAtMost(2))
                .coerceAtMost(config.maxBiasStepNs)
            val step = (errorNs * config.biasGain).toLong().coerceIn(-capNs, capNs)
            // Lead bosishning kadr panjarasidagi fazasini belgilaydi (nishon kadr
            // chegarada); chegaradan chiqqan qismi nishon tuzatishiga o'tadi, aks
            // holda lead 0 da turganda erta tushish hech qachon to'g'rilanmasdi.
            val lead = (leadNs + step).coerceIn(config.minLeadNs, config.maxLeadNs)
            timeBiasNs = (timeBiasNs + step - (lead - leadNs)).coerceIn(-config.maxBiasNs, config.maxBiasNs)
            leadNs = lead
        }
        result = Result(taps, offset, false, stackWidth, leadNs, timeBiasNs, trusted, errorMs(offset, speed), speed)
    }

    private fun errorMs(offset: Float, speed: Float): Float =
        if (abs(speed) > 1f) offset / speed * 1000f else Float.NaN
}
