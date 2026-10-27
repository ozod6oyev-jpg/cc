package uz.perfecttap.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSimTest {

    private fun sim(): GameSim = GameSim(
        screenWidth = 1080,
        screenHeight = 2400,
        blockWidth = 600f,
        blockHeight = 90f,
        perfectTolerancePx = 6f,
        topLimitY = 720f,
        marginX = 60f
    )

    @Test
    fun startsWithBaseBlockAndCentredMovingBlock() {
        val sim = sim()
        assertEquals(1, sim.blockCount)
        assertEquals(600f, sim.width, 0.01f)
        assertEquals(240f, sim.stackX, 0.01f)
        assertEquals(540f, sim.movingCenterX, 0.01f)
        assertEquals(0, sim.placed)
    }

    @Test
    fun perfectPlacementKeepsWidthAndRaisesStack() {
        val sim = sim()
        val topBefore = sim.topY
        val result = sim.place()

        assertTrue(result.perfect)
        assertEquals(600f, sim.width, 0.01f)
        assertEquals(1, sim.perfectCount)
        assertEquals(1, sim.placed)
        assertEquals(topBefore - 90f, sim.topY, 0.01f)
        assertEquals(2, sim.blockCount)
        assertEquals(topBefore - 90f, sim.blockY[0], 0.01f)
    }

    @Test
    fun blocksAreNumberedInPlacementOrder() {
        val sim = sim()
        assertEquals(0, sim.blockIndex[0]) // base block
        repeat(3) {
            sim.movingX = sim.stackX
            sim.place()
        }
        assertEquals(3, sim.blockIndex[0])
        assertEquals(2, sim.blockIndex[1])
        assertEquals(1, sim.blockIndex[2])
    }

    @Test
    fun cutOnTheRightKeepsLeftEdge() {
        val sim = sim()
        sim.step(0.6f) // move away from the centre
        val result = sim.place()

        assertFalse(result.perfect)
        assertTrue(result.offsetPx > 0f)
        // The stack's new left edge is the moving block's left edge.
        assertEquals(240f + result.offsetPx, sim.stackX, 0.01f)
        assertEquals(600f - result.offsetPx, sim.width, 0.5f)
    }

    @Test
    fun cutOnTheLeftKeepsTheStackLeftEdge() {
        val sim = sim()
        sim.direction = -1f
        sim.step(0.6f)
        val result = sim.place()

        assertFalse(result.perfect)
        assertTrue(result.offsetPx < 0f)
        // The overlap starts at the stack's left edge, so the stack does not move.
        assertEquals(240f, sim.stackX, 0.5f)
        assertEquals(600f + result.offsetPx, sim.width, 0.5f)
        assertEquals(240f, sim.blockX[0], 0.5f)
        assertEquals(sim.width, sim.blockW[0], 0.5f)
    }

    @Test
    fun cutOnTheRightUsesTheMovingBlocksLeftEdge() {
        val sim = sim()
        sim.step(0.6f)
        val movingBefore = sim.movingX
        val result = sim.place()

        assertFalse(result.perfect)
        assertTrue(result.offsetPx > 0f)
        assertEquals(movingBefore, sim.stackX, 0.5f)
        assertEquals(600f - result.offsetPx, sim.width, 0.5f)
        assertEquals(movingBefore, sim.blockX[0], 0.5f)
        assertEquals(sim.width, sim.blockW[0], 0.5f)
    }

    @Test
    fun everyNewBlockMovesFasterThanTheOneBefore() {
        val sim = sim()
        sim.baseSpeedPxPerSec = 500f
        sim.speedGrowthPerBlock = 1.1f
        assertEquals(500f, sim.speedPxPerSec, 0.01f)

        sim.movingX = sim.stackX
        sim.place()
        assertEquals(550f, sim.speedPxPerSec, 0.01f)

        sim.movingX = sim.stackX
        sim.place()
        assertEquals(605f, sim.speedPxPerSec, 0.01f)
    }

    @Test
    fun speedSettingAppliesImmediatelyAndResetRestoresTheBase() {
        val sim = sim()
        sim.baseSpeedPxPerSec = 800f
        assertEquals(800f, sim.speedPxPerSec, 0.01f)

        sim.speedGrowthPerBlock = 1.2f
        sim.movingX = sim.stackX
        sim.place()
        assertEquals(960f, sim.speedPxPerSec, 0.01f)

        sim.reset()
        assertEquals(800f, sim.speedPxPerSec, 0.01f)
    }

    @Test
    fun cameraShiftsWhenStackReachesTheTop() {
        val sim = sim()
        var shifts = 0
        var top = sim.topY
        repeat(50) {
            // keep the block centred so every placement is perfect
            sim.movingX = sim.stackX
            val result = sim.place()
            if (result.cameraShifted) shifts++
            top = sim.topY
            assertTrue("top=$top below limit", top >= 720f - 0.01f)
        }

        assertEquals(50, sim.perfectCount)
        assertTrue(sim.cameraShifts > 0)
        assertEquals(sim.cameraShifts, shifts)
        // A camera jump moves every stored block down by one block height.
        assertTrue(sim.blockY[0] >= 720f - 0.01f)
    }

    @Test
    fun movingBlockBouncesInsideMargins() {
        val sim = sim()
        repeat(200) { sim.step(0.05f) }

        assertTrue(sim.movingX >= 60f - 0.01f)
        assertTrue(sim.movingX <= 1080f - 60f - sim.width + 0.01f)
    }

    @Test
    fun blockWidthFollowsCuts() {
        val sim = sim()
        sim.step(0.8f)
        sim.place()
        assertTrue(sim.width < 600f)
        // The next moving block uses the new (narrower) width.
        assertEquals(sim.width, sim.movingWidth, 0.01f)
    }

    @Test
    fun blocksAreStoredNewestFirst() {
        val sim = sim()
        repeat(5) {
            sim.movingX = sim.stackX
            sim.place()
        }
        assertEquals(6, sim.blockCount)
        assertTrue(sim.blockY[0] < sim.blockY[1])
        assertTrue(sim.blockY[1] < sim.blockY[2])
    }
}
