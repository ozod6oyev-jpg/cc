package uz.perfecttap.auto

import java.util.Random
import kotlin.math.exp

/**
 * Anti-bot sinovi uchun ritm: [targetBlocks] blok [durationNs] ichida, bloklar
 * orasidagi vaqt tasodifiy (masalan 2 s, 3 s, 5 s), lekin jami belgilangan
 * muddatga tushadi.
 *
 * Har tapdan keyin qolgan vaqt / qolgan bloklar = o'rtacha oraliq; undan
 * log-normal tasodifiy ko'paytuvchi (o'rtachasi 1) bilan keyingi oraliq
 * olinadi. Oraliq har safar qolgan vaqtdan qayta hisoblanadi, shuning uchun
 * kechikish yoki shoshilish o'z-o'zidan to'g'rilanadi.
 *
 * Tap faqat blok perfect zonaga kelganda ketadi: kutish tugagach keyingi
 * o'tishgacha yana biroz vaqt o'tadi. Bu qo'shimcha vaqt o'lchanadi va
 * kutishdan ayiriladi.
 *
 * Android API yo'q — JVM testlarda tekshiriladi.
 */
class SessionPacer(
    private val random: Random = Random(),
    val targetBlocks: Int = 450,
    val durationNs: Long = 20 * 60 * 1_000_000_000L,
    /** Log-normal tarqalish (σ): 0.35 da oraliq taxminan 0.5x..2x o'rtacha. */
    private val spread: Double = 0.35,
    /** Odatiy eng qisqa va eng uzun oraliq (o'rtacha undan kichik bo'lsa — qisqaroq). */
    private val minIntervalNs: Long = 1_500_000_000L,
    private val floorIntervalNs: Long = 1_000_000_000L,
    private val maxIntervalNs: Long = 6_000_000_000L
) {

    var startNs = 0L
        private set
    val deadlineNs: Long get() = startNs + durationNs
    var placed = 0
        private set
    /** Keyingi tap shundan oldin ketmaydi. */
    var holdUntilNs = 0L
        private set
    /** Oxirgi chizilgan oraliq (tapdan tapgacha, ns). */
    var lastIntervalNs = 0L
        private set
    /** Kutish tugagandan tapgacha o'rtacha qo'shimcha vaqt (o'tishni kutish). */
    var overheadNs = INITIAL_OVERHEAD_NS
        private set

    private var started = false
    val done: Boolean get() = started && placed >= targetBlocks

    fun start(nowNs: Long) {
        started = true
        startNs = nowNs
        placed = 0
        holdUntilNs = 0L
        lastIntervalNs = 0L
        overheadNs = INITIAL_OVERHEAD_NS
    }

    fun remainingNs(nowNs: Long): Long = (deadlineNs - nowNs).coerceAtLeast(0L)

    /** Tap yuborildi ([tapNs]); keyingi tap uchun kutish tugash vaqtini qaytaradi. */
    fun onTap(tapNs: Long): Long {
        if (holdUntilNs > 0L && tapNs >= holdUntilNs) {
            val sample = (tapNs - holdUntilNs).coerceAtMost(MAX_OVERHEAD_NS)
            overheadNs += ((sample - overheadNs) * OVERHEAD_GAIN).toLong()
        }
        placed++
        val left = targetBlocks - placed
        if (left <= 0) {
            holdUntilNs = 0L
            return 0L
        }
        val meanNs = remainingNs(tapNs) / left
        // exp(N(0, σ)) ning o'rtachasi exp(σ²/2): bo'lib, o'rtacha 1 qilinadi.
        val factor = (exp(random.nextGaussian() * spread) / exp(spread * spread / 2))
            .coerceIn(MIN_FACTOR, MAX_FACTOR)
        // Odatda oraliq >= [minIntervalNs]; jadvaldan orqada qolganda qisqaroq,
        // lekin hech qachon [floorIntervalNs] dan emas (muddat bir necha soniya o'tishi mumkin).
        val lower = minOf(minIntervalNs, (meanNs * CATCH_UP_FACTOR).toLong()).coerceAtLeast(floorIntervalNs)
        val upper = maxOf(maxIntervalNs, lower)
        lastIntervalNs = (meanNs * factor).toLong().coerceIn(lower, upper)
        // Kutish qo'shimcha vaqtni hisobga oladi, lekin oraliq pastki chegaradan qisqarmaydi.
        holdUntilNs = tapNs + maxOf(lastIntervalNs - overheadNs, lower)
        return holdUntilNs
    }

    private companion object {
        const val INITIAL_OVERHEAD_NS = 300_000_000L
        const val MAX_OVERHEAD_NS = 2_000_000_000L
        const val OVERHEAD_GAIN = 0.2
        const val MIN_FACTOR = 0.4
        const val CATCH_UP_FACTOR = 0.6
        const val MAX_FACTOR = 2.2
    }
}
