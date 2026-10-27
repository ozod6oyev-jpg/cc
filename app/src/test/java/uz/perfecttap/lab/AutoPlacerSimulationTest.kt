package uz.perfecttap.lab

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Closed-loop simulation of the lab without a device: the game runs at 120 fps,
 * the "camera" lags by [DELAY_MS], and [AutoPlacer] only ever sees the delayed
 * position and the stack. Placements are executed on the game at the scheduled
 * time, so the resulting offsets are what the real loop would produce.
 */
class AutoPlacerSimulationTest {

    private val ms = 1_000_000L

    private class History(private val capacity: Int = 4096) {
        private val times = LongArray(capacity)
        private val values = FloatArray(capacity)
        private var size = 0
        private var next = 0

        fun add(timeNs: Long, value: Float) {
            times[next] = timeNs
            values[next] = value
            next = (next + 1) % capacity
            if (size < capacity) size++
        }

        fun at(timeNs: Long): Float {
            var i = (next - 1 + capacity) % capacity
            repeat(size) {
                if (times[i] <= timeNs) return values[i]
                i = (i - 1 + capacity) % capacity
            }
            return values[(next - 1 + capacity) % capacity]
        }
    }

    private class Report(
        val placements: Int,
        val perfect: Int,
        val worstOffset: Float
    )

    /**
     * Runs the closed loop: the game, the delayed camera and the placer.
     *
     * [growthPerBlock] > 1 makes every new block faster, like the lab's setting.
     */
    private fun run(
        delayMs: Long,
        baseSpeedPxPerSec: Float = 0f,
        growthPerBlock: Float = 1f,
        steps: Int = 30000,
        noisePx: Float = 0f
    ): Report {
        // Detektor shovqini: deterministik, testlar takrorlanuvchan bo'lsin.
        val random = java.util.Random(42)
        val sim = GameSim(
            screenWidth = 1080,
            screenHeight = 2400,
            blockWidth = 604.8f,
            blockHeight = 90.72f
        )
        if (baseSpeedPxPerSec > 0f) sim.baseSpeedPxPerSec = baseSpeedPxPerSec
        sim.speedGrowthPerBlock = growthPerBlock

        val placer = AutoPlacer()
        val history = History()

        var nowNs = 0L
        var placements = 0
        var perfect = 0
        var worstOffset = 0f

        repeat(steps) {
            nowNs += FRAME_MS * ms
            sim.step(FRAME_MS / 1000f)
            history.add(nowNs, sim.movingCenterX)

            if (nowNs < 250 * ms) return@repeat
            val contentNs = nowNs - delayMs * ms
            val contentX = history.at(contentNs) +
                (random.nextFloat() * 2f - 1f) * noisePx
            val width = sim.width
            val stackCenter = sim.stackX + sim.width * 0.5f

            val decision = placer.onObservation(
                centerX = contentX,
                widthPx = width,
                stackLeft = stackCenter - width * 0.5f,
                stackRight = stackCenter + width * 0.5f,
                contentTimestampNs = contentNs,
                arrivalNs = contentNs + 2 * ms,
                nowNs = nowNs,
                screenWidthPx = 1080f
            )
            if (decision.scheduleInNs < 0L) return@repeat

            val placeAt = nowNs + decision.scheduleInNs
            sim.step((placeAt - nowNs) / 1_000_000_000f)
            val placement = sim.place()
            placer.onPlaced(placeAt)
            placements++
            if (placement.perfect) perfect++
            worstOffset = maxOf(worstOffset, kotlin.math.abs(placement.offsetPx))
            nowNs = placeAt
        }
        return Report(placements, perfect, worstOffset)
    }

    @Test
    fun placesPerfectlyWithARespectiveStreamDelay() {
        val report = run(delayMs = DELAY_MS)

        assertTrue("placements=${report.placements}", report.placements >= 40)
        assertTrue(
            "perfect=${report.perfect}/${report.placements} worst=${report.worstOffset.toInt()}px",
            report.perfect >= report.placements * 95 / 100
        )
    }

    @Test
    fun keepsUpWhenEveryBlockMovesFaster() {
        // 700 px/s base and +6% per block: by the last blocks the square sweeps
        // the playfield at well over 2000 px/s.
        val report = run(delayMs = DELAY_MS, baseSpeedPxPerSec = 700f, growthPerBlock = 1.06f)

        assertTrue("placements=${report.placements}", report.placements >= 40)
        assertTrue(
            "perfect=${report.perfect}/${report.placements} worst=${report.worstOffset.toInt()}px",
            report.perfect >= report.placements * 95 / 100
        )
    }

    @Test
    fun staysPerfectAtHighSpeedWithDetectorNoise() {
        // Lab sessiyasidagi sozlama: 1800 px/s, +5% har blokda, ±0.7 px shovqin.
        val report = run(
            delayMs = DELAY_MS,
            baseSpeedPxPerSec = 1800f,
            growthPerBlock = 1.05f,
            noisePx = 0.7f
        )
        println("high-speed: perfect=${report.perfect}/${report.placements} worst=${report.worstOffset}")
        assertTrue("placements=${report.placements}", report.placements >= 40)
        assertTrue(
            "perfect=${report.perfect}/${report.placements} worst=${report.worstOffset.toInt()}px",
            report.perfect >= report.placements * 90 / 100
        )
    }

    private companion object {
        const val FRAME_MS = 8L
        const val DELAY_MS = 40L
    }
}
