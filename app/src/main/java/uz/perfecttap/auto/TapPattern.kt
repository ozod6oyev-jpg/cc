package uz.perfecttap.auto

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Anti-bot sinovi uchun tap joylari: bir xil nuqta o'rniga **sikllar**.
 *
 * Har sikl ekranning tasodifiy joyida (oldingi sikldan [minAnchorDistancePx]
 * uzoqda) boshlanadi va [clusterSizes] dan tasodifiy sondagi blokni o'sha
 * atrofda bosadi: har keyingi tap oldingisidan tasodifiy yo'nalishda sikl
 * oralig'idagi ([stepRangesPx], navbat bilan, masalan 4–5 px, keyin 1–10 px)
 * masofaga suriladi.
 *
 * Tegish mumkin bo'lmagan joylar: ekran tepasidan [Area.top] gacha, pastdan
 * [Area.bottom] dan keyin va [next] ga berilgan to'rtburchak (panelning o'zi —
 * unga tap tushsa «To'xtatish» yoki «✕» bosilib ketadi).
 *
 * Android API yo'q — JVM testlarda tekshiriladi.
 */
class TapPattern(
    private val random: Random = Random(),
    private val clusterSizes: IntRange = 5..8,
    private val stepRangesPx: List<ClosedFloatingPointRange<Float>> = listOf(4f..5f, 1f..10f),
    private val minAnchorDistancePx: Float = 150f
) {

    /** Ruxsat etilgan hudud (ekran px). */
    data class Area(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
    }

    data class Tap(
        val x: Float,
        val y: Float,
        /** Sikl raqami (0 dan) va tapning sikldagi o'rni (0 dan). */
        val cycle: Int,
        val indexInCycle: Int,
        val cycleSize: Int,
        /** Oldingi tapdan surilish (px); sikl boshida 0. */
        val stepPx: Float
    )

    private var area = Area(0f, 0f, 0f, 0f)
    private var cycle = -1
    private var cycleSize = 0
    private var index = 0
    private var stepRange = stepRangesPx.first()
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var anchorX = Float.NaN
    private var anchorY = Float.NaN

    /**
     * Ekran o'lchami va tegilmaydigan chetlar: tepadan [topExcludedPx],
     * pastdan [bottomExcludedPx]. Yangi sessiya birinchi sikldan boshlanadi.
     */
    fun reset(screenWidthPx: Float, screenHeightPx: Float, topExcludedPx: Float, bottomExcludedPx: Float) {
        area = Area(
            EDGE_PX,
            topExcludedPx,
            screenWidthPx - EDGE_PX,
            screenHeightPx - bottomExcludedPx
        )
        cycle = -1
        index = 0
        cycleSize = 0
        lastX = Float.NaN
        lastY = Float.NaN
        anchorX = Float.NaN
        anchorY = Float.NaN
    }

    /** Keyingi tap joyi; [excluded] — tegilmaydigan to'rtburchak (null = yo'q). */
    fun next(excluded: Area? = null): Tap {
        val blocked = excluded?.let {
            Area(it.left - EXCLUDE_MARGIN_PX, it.top - EXCLUDE_MARGIN_PX, it.right + EXCLUDE_MARGIN_PX, it.bottom + EXCLUDE_MARGIN_PX)
        }
        if (cycle < 0 || index >= cycleSize) return startCycle(blocked)
        repeat(STEP_TRIES) {
            val step = stepRange.start + random.nextFloat() * (stepRange.endInclusive - stepRange.start)
            val angle = random.nextDouble() * 2.0 * PI
            val x = lastX + step * cos(angle).toFloat()
            val y = lastY + step * sin(angle).toFloat()
            if (allowed(x, y, blocked)) return emit(x, y, step)
        }
        // Chetga yoki panelga taqalib qoldi: sikl markaziga qarab bir qadam.
        val toAnchor = hypot(anchorX - lastX, anchorY - lastY)
        val step = stepRange.endInclusive.coerceAtMost(toAnchor)
        val x = if (toAnchor > 0f) lastX + (anchorX - lastX) / toAnchor * step else lastX
        val y = if (toAnchor > 0f) lastY + (anchorY - lastY) / toAnchor * step else lastY
        return emit(x, y, step)
    }

    private fun startCycle(blocked: Area?): Tap {
        cycle++
        index = 0
        cycleSize = clusterSizes.first + random.nextInt(clusterSizes.last - clusterSizes.first + 1)
        stepRange = stepRangesPx[cycle % stepRangesPx.size]
        var x = (area.left + area.right) * 0.5f
        var y = (area.top + area.bottom) * 0.5f
        for (attempt in 0 until ANCHOR_TRIES) {
            val cx = area.left + random.nextFloat() * (area.right - area.left)
            val cy = area.top + random.nextFloat() * (area.bottom - area.top)
            if (!allowed(cx, cy, blocked)) continue
            x = cx
            y = cy
            // Boshqa rakurs: oldingi sikl joyidan uzoqroq (iloji bo'lsa).
            if (anchorX.isNaN() || hypot(cx - anchorX, cy - anchorY) >= minAnchorDistancePx) break
        }
        anchorX = x
        anchorY = y
        return emit(x, y, 0f)
    }

    private fun emit(x: Float, y: Float, stepPx: Float): Tap {
        lastX = x
        lastY = y
        val tap = Tap(x, y, cycle, index, cycleSize, stepPx)
        index++
        return tap
    }

    private fun allowed(x: Float, y: Float, blocked: Area?): Boolean =
        area.contains(x, y) && (blocked == null || !blocked.contains(x, y))

    private companion object {
        /** Ekranning o'ng/chap chetidagi zaxira (px) — sensor chegarasi. */
        const val EDGE_PX = 2f
        /** Panel atrofidagi zaxira (px): barmoq izi panel chetiga tegmasin. */
        const val EXCLUDE_MARGIN_PX = 24f
        const val STEP_TRIES = 12
        const val ANCHOR_TRIES = 40
    }
}
