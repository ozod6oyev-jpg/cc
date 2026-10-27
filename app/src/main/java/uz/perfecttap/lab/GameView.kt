package uz.perfecttap.lab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * O'yin sahnasining lab nusxasi: raqamlangan bloklar, stack ustida chapga-o'ngga
 * harakatlanayotgan blok, stack tepaga o'sishi va tepaga yetganda maydonning
 * pastga siljishi (kamera sakrashi).
 *
 * Bu **o'yin tomoni**: u faqat teginish hodisalarini qabul qiladi. Qayerdan va
 * qachon bosilganini bilmaydi — clicker alohida ([Clicker]) va ular faqat
 * "bosish" hodisasi orqali gaplashadi.
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    val ring = FrameRing()

    /**
     * Δ_input uchun teginish **yetib kelgan payt**, nanosekundlarda
     * (`System.nanoTime()`). `MotionEvent` vaqti API 34 gacha ms aniqliqda
     * bo'lgani uchun ishlatilmaydi — µs darajadagi o'lchov buzilib ketardi.
     */
    var onTouchSample: ((dispatchNs: Long) -> Unit)? = null

    /** Har muvaffaqiyatli qo'yishda chaqiriladi. */
    var onPlacement: ((GameSim.Placement) -> Unit)? = null

    var simulation: GameSim? = null
        private set

    val isRunning: Boolean get() = running

    /** Tezlik sozlamasi: boshlang'ich tezlik va har blokdagi o'sish. */
    var motionBaseSpeedPxPerSec: Float = 400f
    var motionGrowthPerBlock: Float = 1f

    /**
     * O'yin maydonining pastki chegarasi (lab panelining tepasi), px.
     * Panel ortida blok ko'rinmaydi, shuning uchun stack shu chegaradan
     * yuqorida boshlanadi.
     */
    var playfieldBottomPx: Int = 0

    private val choreographer = Choreographer.getInstance()
    private var running = false
    private var lastFrameNs = 0L
    private var tapRadiusPx = 300f
    /** Sintetik (clicker) bosishni uzatish paytida haqiqiy teginishni ajratish. */
    private var deliveringTap = false
    // Oxirgi kadr boshlangan paytdagi holat: kechikkan bosishni o'z vaqtiga qaytarish uchun.
    private var frameStartNs = 0L
    private var frameStartX = 0f
    private var frameStartDir = 1f


    // Index 0 is the topmost placed block; dark there gives the white moving
    // block a high-contrast boundary for vertical tracking.
    private val blockPaints = arrayOf(
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(58, 58, 58) },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(232, 232, 232) },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(36, 36, 36) }
    )
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.rgb(120, 120, 120)
    }
    private val movingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
    }
    private val numberPaintOnLight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.rgb(20, 20, 20)
    }

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        onDisplayFrame(frameTimeNanos)
    }

    init {
        setBackgroundColor(Color.BLACK)
    }

    fun start() {
        if (running) return
        running = true
        lastFrameNs = 0L
        ring.clear()
        simulation?.apply {
            startTopY = playfieldStartY()
            reset()
            baseSpeedPxPerSec = motionBaseSpeedPxPerSec
            speedGrowthPerBlock = motionGrowthPerBlock
        }
        choreographer.postFrameCallback(frameCallback)
    }

    fun stop() {
        if (!running) return
        running = false
        choreographer.removeFrameCallback(frameCallback)
        invalidate()
    }

    /** Stack boshlanadigan Y: panel tepasidan bir yarim blok yuqorida. */
    private fun playfieldStartY(): Float {
        val blockHeight = simulation?.blockHeight ?: return height * 0.62f
        return if (playfieldBottomPx > height * 0.3f) {
            (playfieldBottomPx - blockHeight * 1.5f).coerceAtLeast(blockHeight * 2f)
        } else {
            height * 0.62f
        }
    }

    /** Tezlikni o'yin vaqtida o'zgartirish (slider). */
    fun setMotion(baseSpeedPxPerSec: Float, growthPerBlock: Float) {
        motionBaseSpeedPxPerSec = baseSpeedPxPerSec
        motionGrowthPerBlock = growthPerBlock
        simulation?.apply {
            this.baseSpeedPxPerSec = baseSpeedPxPerSec
            this.speedGrowthPerBlock = growthPerBlock
        }
    }

    /**
     * Clicker yuborgan bosish: oddiy teginish kabi uzatiladi, o'yin esa o'z
     * qoidasi bilan hal qiladi. Hodisa o'z vaqt belgisini olib keladi, o'yin
     * holatini shu vaqtga moslaydi (kechikkan kadrlar buni buzmaydi).
     */
    fun deliverTap(x: Float, y: Float, tapTimeNs: Long) {
        if (!running) return
        val sim = simulation ?: return
        applyStateAt(sim, tapTimeNs)
        val eventTimeMs = tapTimeNs / 1_000_000L
        val event = MotionEvent.obtain(eventTimeMs, eventTimeMs, MotionEvent.ACTION_DOWN, x, y, 0)
        deliveringTap = true
        try {
            dispatchTouchEvent(event)
        } finally {
            deliveringTap = false
            event.recycle()
        }
    }

    /**
     * O'yin holatini [timeNs] ga keltiradi. Hodisa vaqti oxirgi kadrdan oldin
     * bo'lsa, kadr boshidagi holat tiklanadi — sensor hodisasi ham shunday
     * ishlaydi: u o'z vaqtida yuz bergan, lekin keyin yetkaziladi.
     */
    private fun applyStateAt(sim: GameSim, timeNs: Long) {
        when {
            timeNs in (frameStartNs + 1) until lastFrameNs -> {
                sim.restoreMoving(frameStartX, frameStartDir)
                lastFrameNs = frameStartNs
                advanceTo(sim, timeNs)
            }
            timeNs > lastFrameNs -> advanceTo(sim, timeNs)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val blockWidth = w * 0.56f
        simulation = GameSim(
            screenWidth = w,
            screenHeight = h,
            blockWidth = blockWidth,
            blockHeight = blockWidth * 0.15f
        )
        simulation?.apply {
            baseSpeedPxPerSec = motionBaseSpeedPxPerSec
            speedGrowthPerBlock = motionGrowthPerBlock
        }
        val metrics = resources.displayMetrics
        val dpi = metrics.xdpi.takeIf { it > 1f } ?: metrics.densityDpi.toFloat()
        tapRadiusPx = 2f / 2.54f * dpi // 2 sm radius
    }


    /** Advances the simulation up to [timeNs] (both use the nanoTime clock). */
    private fun advanceTo(sim: GameSim, timeNs: Long) {
        if (lastFrameNs == 0L) {
            lastFrameNs = timeNs
            return
        }
        if (timeNs <= lastFrameNs) return
        val dt = ((timeNs - lastFrameNs) / 1_000_000_000f).coerceIn(0f, 0.35f)
        sim.step(dt)
        lastFrameNs = timeNs
    }

    private fun onDisplayFrame(frameTimeNanos: Long) {
        if (!running) return
        val sim = simulation
        if (sim == null || width <= 0) {
            choreographer.postFrameCallback(frameCallback)
            return
        }
        frameStartNs = lastFrameNs
        frameStartX = sim.movingX
        frameStartDir = sim.direction
        advanceTo(sim, frameTimeNanos)
        ring.record(frameTimeNanos, sim.movingCenterX)
        invalidate()
        choreographer.postFrameCallback(frameCallback)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val sim = simulation ?: return
        val blockHeight = sim.blockHeight
        numberPaint.textSize = blockHeight * 0.55f
        numberPaintOnLight.textSize = blockHeight * 0.55f

        for (i in 0 until sim.blockCount) {
            val top = sim.blockY[i]
            if (top > height || top + blockHeight < 0f) continue
            val left = sim.blockX[i]
            val paint = blockPaints[i % blockPaints.size]
            canvas.drawRect(left, top, left + sim.blockW[i], top + blockHeight, paint)
            canvas.drawRect(left, top, left + sim.blockW[i], top + blockHeight, edgePaint)

            val index = sim.blockIndex[i]
            if (index > 0) {
                val textPaint = if (i % blockPaints.size == 1) numberPaintOnLight else numberPaint
                val metrics = textPaint.fontMetrics
                val baseline = top + blockHeight * 0.5f - (metrics.ascent + metrics.descent) * 0.5f
                canvas.drawText(index.toString(), left + sim.blockW[i] * 0.5f, baseline, textPaint)
            }
        }

        // The moving block sits directly above the stack, as in the real game.
        val movingTop = sim.topY - blockHeight
        canvas.drawRect(
            sim.movingX,
            movingTop,
            sim.movingX + sim.movingWidth,
            sim.topY,
            movingPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // Δ_input faqat haqiqiy teginish uchun o'lchanadi, clicker bosishi uchun emas.
            if (!deliveringTap) onTouchSample?.invoke(System.nanoTime())
            val sim = simulation
            val distance = hypot(event.x - width * 0.5f, event.y - height * 0.5f)
            if (sim != null && running && distance <= tapRadiusPx) {
                // O'yin qoidasi: markazga bosish blokni qo'yadi.
                onPlacement?.invoke(sim.place())
                invalidate()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}
