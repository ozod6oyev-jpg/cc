package uz.perfecttap.lab

/**
 * Turns a detector result into a latency sample.
 *
 * The lab view records every drawn square position with the frame's vsync
 * timestamp. The detector reports where the square was in a captured frame, so
 * Δ_stream = image.timestamp - vsync of the draw that produced it.
 */
class FrameCorrelator(
    private val ring: FrameRing,
    private val tolerancePx: Float = 4f,
    /** Upper bound for a plausible capture latency; slower frames are mismatches. */
    private val maxLatencyNs: Long = DEFAULT_MAX_LATENCY_NS,
    private val maxAgeFrames: Int = 60
) {

    companion object {
        /** Software-rendered emulators reach ~200 ms; real devices stay far below. */
        const val DEFAULT_MAX_LATENCY_NS = 500_000_000L
        /** Frames whose measured delay the prediction window looks back over. */
        private const val WINDOW = 5
        /** Kutilgan kechikish shuncha o'lchovdan keyin tanlovga ta'sir qiladi. */
        private const val MIN_FOR_HINT = 3
        /** Yo'nalish faqat yaqin kadrlar orasida va aniq siljishda ishonchli. */
        private const val DIRECTION_MAX_GAP_NS = 50_000_000L
        private const val DIRECTION_MIN_PX = 1f
    }

    var lastVsyncNs = 0L
        private set
    var lastSlot = -1
        private set

    /**
     * Median Δ_stream of the recent frames.
     *
     * A single frame can be matched to the draw before or after the right one
     * (the compositor may latch an older buffer), which moves the measured delay
     * by a whole display frame. The median rejects those one-frame outliers while
     * still following a genuine change in the pipeline delay.
     */
    var stableDeltaNs = 0L
        private set

    /**
     * Nega oxirgi kadr chizilgan kadrga mos kelmadi — CSV'dagi `note` ustuniga
     * yoziladi, shunda «mos kelmagan kadr» sababsiz qolmaydi.
     */
    var lastMiss = "none"
        private set

    /** Oldingi kuzatuv: kadrdagi harakat yo'nalishini aniqlash uchun. */
    private var prevCenterX = Float.NaN
    private var prevImageNs = 0L

    private val recent = LongArray(WINDOW)
    private val sorted = LongArray(WINDOW)
    private var recentCount = 0
    private var recentIndex = 0

    /** Δ_stream in nanoseconds, or -1 when this frame cannot be matched. */
    fun streamDeltaNs(centerX: Float, imageTimestampNs: Long): Long {
        // Chetdan qaytayotgan blok bir xil X dan ikki marta o'tadi: yo'nalish va
        // kutilgan kechikish bo'lmasa, kadr oldingi supurishga bog'lanib, Δ_stream
        // ~100+ ms ga sakrardi va bashorat (hamda qo'yish) buzilardi.
        val direction = observedDirection(centerX, imageTimestampNs)
        val expectedVsyncNs = if (recentCount >= MIN_FOR_HINT && stableDeltaNs > 0L) {
            imageTimestampNs - stableDeltaNs
        } else {
            0L
        }
        val slot = ring.matchCenter(
            centerX = centerX,
            tolerancePx = tolerancePx,
            notOlderThanNs = imageTimestampNs - maxLatencyNs,
            maxFrames = maxAgeFrames,
            direction = direction,
            expectedVsyncNs = expectedVsyncNs
        )
        if (slot < 0) {
            lastMiss = "no_draw_match"
            return -1L
        }
        val vsync = ring.vsyncAt(slot)
        val delta = imageTimestampNs - vsync
        if (delta <= 0L) {
            lastMiss = "delta_not_positive"
            return -1L
        }
        if (delta > maxLatencyNs) {
            lastMiss = "delta_over_limit"
            return -1L
        }
        lastSlot = slot
        lastVsyncNs = vsync
        lastMiss = "none"
        push(delta)
        return delta
    }

    /** Kadrdagi harakat yo'nalishi: oldingi kuzatuvga nisbatan (+1 / -1 / 0). */
    private fun observedDirection(centerX: Float, imageTimestampNs: Long): Int {
        val prevX = prevCenterX
        val prevNs = prevImageNs
        prevCenterX = centerX
        prevImageNs = imageTimestampNs
        if (prevX.isNaN()) return 0
        val dt = imageTimestampNs - prevNs
        if (dt <= 0L || dt > DIRECTION_MAX_GAP_NS) return 0
        val dx = centerX - prevX
        return when {
            dx >= DIRECTION_MIN_PX -> 1
            dx <= -DIRECTION_MIN_PX -> -1
            else -> 0
        }
    }

    fun reset() {
        prevCenterX = Float.NaN
        prevImageNs = 0L
        lastMiss = "none"
        lastVsyncNs = 0L
        lastSlot = -1
        recentCount = 0
        recentIndex = 0
        stableDeltaNs = 0L
    }

    private fun push(deltaNs: Long) {
        recent[recentIndex] = deltaNs
        recentIndex = (recentIndex + 1) % WINDOW
        if (recentCount < WINDOW) recentCount++
        for (i in 0 until recentCount) sorted[i] = recent[i]
        for (i in 1 until recentCount) {
            val value = sorted[i]
            var j = i - 1
            while (j >= 0 && sorted[j] > value) {
                sorted[j + 1] = sorted[j]
                j--
            }
            sorted[j + 1] = value
        }
        stableDeltaNs = sorted[recentCount / 2]
    }

}
