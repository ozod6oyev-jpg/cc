package uz.perfecttap.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPlacerTest {

    private val ms = 1_000_000L

    private fun AutoPlacer.observe(
        centerX: Float,
        tMs: Long,
        stackCenter: Float = 540f,
        widthPx: Float = 600f,
        latencyMs: Long = 0L,
        hasStack: Boolean = true
    ): AutoPlacer.Decision = onObservation(
        centerX = centerX,
        widthPx = widthPx,
        stackLeft = if (hasStack) stackCenter - 300f else 0f,
        stackRight = if (hasStack) stackCenter + 300f else 0f,
        contentTimestampNs = tMs * ms,
        arrivalNs = tMs * ms,
        nowNs = tMs * ms + latencyMs * ms,
        screenWidthPx = 1080f
    )

    @Test
    fun stackCentreComesFromTheMeasurementWithoutPlacing() {
        val placer = AutoPlacer()
        val decision = placer.observe(600f, 0, stackCenter = 540f)

        assertTrue(decision.scheduleInNs < 0)
        assertEquals(540f, placer.stackCenterX, 0.01f)
        assertEquals(600f, decision.predictedCenterX, 0.01f)
    }

    /** The first placement is held back until the tracker has seen a full sweep. */
    private fun AutoPlacer.warmUp(): AutoPlacer {
        repeat(AutoPlacer.WARMUP_OBSERVATIONS) { observe(540f, it * 5L - 100L) }
        return this
    }

    @Test
    fun schedulesWhenTheBlockReachesTheStackCentre() {
        val placer = AutoPlacer().warmUp()
        placer.observe(540f, 0)
        // Approaching from the left with a positive velocity.
        placer.observe(530f, 50)
        placer.observe(535f, 75)
        val decision = placer.observe(540f, 100)

        assertEquals(540f, placer.stackCenterX, 0.01f)
        assertEquals(0L, decision.scheduleInNs)
    }

    @Test
    fun schedulesAheadOfTimeWhenTheCrossingIsAFewMsAway() {
        val placer = AutoPlacer().warmUp()
        // -320 px/s across a full speed window; the last frame is 0.4 px short.
        val last = AutoPlacer.SAMPLES - 1
        for (k in 0 until last) placer.observe(540.4f + 8f * (last - k), k * 25L)
        val decision = placer.observe(540.4f, last * 25L)

        assertTrue("schedule=${decision.scheduleInNs}", decision.scheduleInNs in 1..20 * ms)
    }

    @Test
    fun predictionAccountsForTheStreamDelay() {
        val placer = AutoPlacer()
        placer.observe(540f, 0)
        // 600 px/s and a frame that is 100 ms old by the time we decide.
        val decision = placer.observe(600f, 100, latencyMs = 100)

        assertEquals(660f, decision.predictedCenterX, 1f)
    }

    @Test
    fun predictionReflectsOffThePlayfieldEdge() {
        val placer = AutoPlacer()
        placer.observe(540f, 0)
        // 1600 px/s for 100 ms would end at 860, past the right edge (715.2),
        // so the prediction folds back to 570.4.
        val decision = placer.observe(700f, 100, latencyMs = 100)

        assertEquals(570.4f, decision.predictedCenterX, 1f)
    }

    @Test
    fun noDecisionWhileCoolingDownAfterAPlacement() {
        val placer = AutoPlacer()
        placer.observe(540f, 0)
        placer.onPlaced(100 * ms)
        val decision = placer.observe(530f, 120)

        assertTrue(decision.scheduleInNs < 0)
    }

    @Test
    fun noDecisionDuringTheFirstSweep() {
        val placer = AutoPlacer()
        // A crossing right away is ignored: the tracker is still settling.
        var last = placer.observe(500f, 0)
        last = placer.observe(535f, 50)
        last = placer.observe(540f, 75)

        assertTrue(last.scheduleInNs < 0)
    }

    @Test
    fun noDecisionBeforeTheSpeedWindowIsFull() {
        val placer = AutoPlacer()
        placer.observe(540f, 0)
        val second = placer.observe(560f, 50)
        // One frame of movement can be a bounce, so the placer waits for a third
        // sample before it schedules anything.
        assertTrue(second.scheduleInNs < 0)
    }

    @Test
    fun noDecisionWithoutAStackMeasurement() {
        val placer = AutoPlacer()
        placer.observe(540f, 0, hasStack = false)
        val decision = placer.observe(545f, 50, hasStack = false)

        assertTrue(decision.stackCenterX.isNaN())
        assertTrue(decision.scheduleInNs < 0)
    }

    @Test
    fun stackCentreFollowsTheCut() {
        val placer = AutoPlacer()
        placer.observe(540f, 0, stackCenter = 540f)
        val decision = placer.observe(548f, 50, stackCenter = 500f)

        assertEquals(500f, placer.stackCenterX, 0.01f)
        assertEquals(500f, decision.stackCenterX, 0.01f)
    }

    @Test
    fun noDecisionWhileTheBlockIsBarelyMoving() {
        val placer = AutoPlacer()
        placer.observe(540f, 0)
        val decision = placer.observe(540.5f, 100)

        assertTrue(decision.scheduleInNs < 0)
    }

    /** Tap nega yo'qligi ekranda va CSV'da shu so'z bilan ko'rinadi. */
    @Test
    fun reportsWhyNoPlacementWasScheduled() {
        val noStack = AutoPlacer()
        noStack.observe(540f, 0, hasStack = false)
        assertEquals(AutoPlacer.REASON_NO_STACK, noStack.lastReason)

        val fewSamples = AutoPlacer()
        fewSamples.observe(540f, 0)
        assertEquals(AutoPlacer.REASON_SAMPLES, fewSamples.lastReason)

        // Namunalar to'ldi, lekin birinchi supurish hali tugamagan.
        val firstSweep = AutoPlacer()
        repeat(AutoPlacer.SAMPLES) { firstSweep.observe(530f + it * 2f, it * 25L) }
        assertEquals(AutoPlacer.REASON_FIRST_SWEEP, firstSweep.lastReason)
    }

    @Test
    fun reportsScheduledWhenATapIsPlanned() {
        val placer = AutoPlacer().warmUp()
        placer.observe(540f, 0)
        placer.observe(530f, 50)
        placer.observe(535f, 75)
        val decision = placer.observe(540f, 100)

        assertTrue(decision.scheduleInNs >= 0L)
        assertEquals(AutoPlacer.REASON_SCHEDULED, placer.lastReason)
    }

    @Test
    fun resetClearsLearnedState() {
        val placer = AutoPlacer()
        placer.observe(540f, 0)
        placer.reset()

        assertTrue(placer.stackCenterX.isNaN())
        assertEquals(0f, placer.speedPxPerSec, 0.01f)
    }
}
