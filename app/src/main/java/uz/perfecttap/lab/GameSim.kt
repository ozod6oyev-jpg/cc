package uz.perfecttap.lab

import kotlin.math.abs

/**
 * O'yin geometriyasi (lab uchun soddalashtirilgan nusxa):
 *
 * - har bir blok 1x1 qo'yiladi: eni o'zgarmaydi, faqat to'liq ustma-ust tushsa;
 * - bir marta ekran markazidan 2 sm radiusda bosish yangi blokni qo'yadi;
 * - yangi blok oldingi blokning tepasidan, xuddi shu eni bilan paydo bo'ladi;
 * - stack tepaga o'sadi, ekran tepasiga yetganda maydon pastga siljiydi
 *   (kamera sakrashi), shuning uchun kuzatuvchi tasma Y ni ham surish kerak.
 *
 * Sof mantiq: Android API yo'q, shuning uchun JVM testlarda tekshiriladi.
 */
class GameSim(
    private val screenWidth: Int,
    private val screenHeight: Int,
    val blockWidth: Float,
    val blockHeight: Float,
    private val perfectTolerancePx: Float = 6f,
    private val topLimitY: Float = screenHeight * 0.30f,
    private val marginX: Float = screenWidth * 0.06f
) {

    data class Placement(
        val offsetPx: Float,
        val perfect: Boolean,
        val cameraShifted: Boolean,
        val widthPx: Float,
        /** Blok stackka umuman tegmadi: u tushib ketadi, stack o'zgarmaydi. */
        val missed: Boolean = false
    )

    /** Placed block geometry, newest first. */
    val blockX = FloatArray(MAX_BLOCKS)
    val blockY = FloatArray(MAX_BLOCKS)
    val blockW = FloatArray(MAX_BLOCKS)
    /** 1-based placement number; 0 for the base block. */
    val blockIndex = IntArray(MAX_BLOCKS)
    var blockCount = 0
        private set

    var stackX = (screenWidth - blockWidth) * 0.5f
        private set
    var width = blockWidth
        private set
    /**
     * Stack tepasining boshlang'ich Y i. Labda panel tepasidan yuqoriga
     * o'rnatiladi, aks holda birinchi blok panel ortida qolib, kamera uni
     * ko'rmaydi.
     */
    var startTopY: Float = screenHeight * 0.62f
    /** Top edge of the stack (the newest placed block). */
    var topY = startTopY
        private set
    var movingX = stackX
    var direction = 1f

    var placed = 0
        private set
    var perfectCount = 0
        private set
    var cameraShifts = 0
        private set
    /** Stackka umuman tegmagan bosishlar soni. */
    var misses = 0
        private set

    /** Boshlang'ich tezlik (px/s); slider shu qiymatni o'zgartiradi. */
    var baseSpeedPxPerSec = screenWidth * 0.55f
        set(value) {
            field = value.coerceIn(40f, MAX_SPEED_FACTOR * screenWidth)
            applySpeed()
        }

    /** Har bir yangi blok oldingisidan shu koeffitsiyentga tezroq harakatlanadi. */
    var speedGrowthPerBlock = 1f
        set(value) {
            field = value.coerceIn(1f, 1.5f)
            applySpeed()
        }

    /** Joriy blokning tezligi. */
    var speedPxPerSec = baseSpeedPxPerSec
        private set

    private fun applySpeed() {
        speedPxPerSec = (baseSpeedPxPerSec * Math.pow(
            speedGrowthPerBlock.toDouble(),
            placed.toDouble()
        ).toFloat()).coerceAtMost(MAX_SPEED_FACTOR * screenWidth)
    }

    val movingWidth: Float get() = width
    val movingCenterX: Float get() = movingX + width * 0.5f
    val movingCenterY: Float get() = topY - blockHeight * 0.5f
    val stackCenterX: Float get() = stackX + width * 0.5f

    init {
        addBlock(stackX, topY, width, 0)
    }

    fun reset() {
        blockCount = 0
        stackX = (screenWidth - blockWidth) * 0.5f
        width = blockWidth
        topY = startTopY
        movingX = stackX
        direction = 1f
        placed = 0
        perfectCount = 0
        cameraShifts = 0
        misses = 0
        applySpeed()
        addBlock(stackX, topY, width, 0)
    }

    /** Advances the moving block; dt is clamped so a long pause cannot teleport it. */
    fun step(dtSeconds: Float) {
        val dt = dtSeconds.coerceIn(0f, 0.5f)
        val maxX = screenWidth - marginX - width
        movingX += direction * speedPxPerSec * dt
        if (movingX <= marginX) {
            movingX = marginX
            direction = 1f
        } else if (movingX >= maxX) {
            movingX = maxX
            direction = -1f
        }
    }

    /**
     * Puts the moving block back to a remembered state so a tap that arrived late
     * can still be applied at the moment it happened.
     */
    fun restoreMoving(x: Float, dir: Float) {
        movingX = x
        direction = dir
    }

    /** One tap near the screen centre places the moving block where it is now. */
    fun place(): Placement {
        val offset = movingX - stackX
        val perfect = abs(offset) <= perfectTolerancePx
        var shifted = false

        if (abs(offset) >= width) {
            // Ustma-ust tushadigan qism yo'q: blok tushib ketadi. Eni 0 bo'lgan
            // blok qo'shilmaydi, keyingi blok shu stack ustida qayta chiqadi.
            misses++
            movingX = stackX
            return Placement(offset, perfect = false, cameraShifted = false, widthPx = width, missed = true)
        }

        if (!perfect) {
            width -= abs(offset)
        } else {
            perfectCount++
        }

        placed++
        // The surviving part is the overlap of the moving block and the stack, so the
        // new block starts at the rightmost left edge. That edge is the stack's new
        // reference: the next block respawns centred on it.
        // Perfect qo'yishda blok stackka «yopishadi»: eni kesilmagani uchun
        // siljigan holda qoldirilsa, u stackdan offset px osilib qolardi.
        val left = if (perfect) stackX else maxOf(movingX, stackX)
        stackX = left
        addBlock(left, topY - blockHeight, width, placed)

        // The new block sits above the old top; the stack grew upward.
        topY -= blockHeight
        // Camera jumps down once the stack reaches the top of the playfield.
        while (topY < topLimitY) {
            topY += blockHeight
            for (i in 0 until blockCount) blockY[i] += blockHeight
            cameraShifts++
            shifted = true
        }

        // Har yangi blok avvalgisidan biroz tezroq aylanadi.
        applySpeed()
        movingX = stackX
        return Placement(offset, perfect, shifted, width)
    }

    private fun addBlock(x: Float, y: Float, w: Float, index: Int) {
        if (blockCount == MAX_BLOCKS) {
            // Shift older entries down so the newest is always index 0.
            for (i in MAX_BLOCKS - 1 downTo 1) {
                blockX[i] = blockX[i - 1]
                blockY[i] = blockY[i - 1]
                blockW[i] = blockW[i - 1]
                blockIndex[i] = blockIndex[i - 1]
            }
            blockCount--
        }
        for (i in blockCount downTo 1) {
            blockX[i] = blockX[i - 1]
            blockY[i] = blockY[i - 1]
            blockW[i] = blockW[i - 1]
            blockIndex[i] = blockIndex[i - 1]
        }
        blockX[0] = x
        blockY[0] = y
        blockW[0] = w
        blockIndex[0] = index
        blockCount++
    }

    companion object {
        const val MAX_BLOCKS = 64
        /** Tezlik ekran kengligidan shuncha barobardan oshmaydi. */
        const val MAX_SPEED_FACTOR = 2.5f
    }
}
