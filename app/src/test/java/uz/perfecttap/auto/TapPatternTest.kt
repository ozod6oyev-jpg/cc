package uz.perfecttap.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.hypot

class TapPatternTest {

    /** 1080×2340, 1.5 sm / 1 sm @ 403 dpi, panel ekran tepasida. */
    private val width = 1080f
    private val height = 2340f
    private val top = 238f
    private val bottom = 159f
    private val panel = TapPattern.Area(31f, 70f, 731f, 520f)

    private fun taps(count: Int, seed: Long = 1): List<TapPattern.Tap> {
        val pattern = TapPattern(Random(seed))
        pattern.reset(width, height, top, bottom)
        return List(count) { pattern.next(panel) }
    }

    @Test
    fun neverTouchesTheExcludedEdgesOrThePanel() {
        for (tap in taps(5000)) {
            assertTrue("tepa: $tap", tap.y >= top)
            assertTrue("past: $tap", tap.y <= height - bottom)
            assertTrue("chet: $tap", tap.x in 0f..width)
            assertFalse("panel: $tap", tap.x in panel.left - 24f..panel.right + 24f && tap.y in panel.top - 24f..panel.bottom + 24f)
        }
    }

    @Test
    fun cyclesStayInOnePlaceWithSmallSteps() {
        val all = taps(3000)
        val cycles = all.groupBy { it.cycle }
        var inRange = 0
        var steps = 0
        for ((cycle, list) in cycles) {
            if (cycle == cycles.keys.max()) continue // oxirgisi chala bo'lishi mumkin
            assertTrue("sikl $cycle: ${list.size}", list.size in 5..8)
            assertEquals(list.size, list.first().cycleSize)
            assertEquals(0f, list.first().stepPx, 0f)
            val range = if (cycle % 2 == 0) 4f..5f else 1f..10f
            for (i in 1 until list.size) {
                val step = hypot(list[i].x - list[i - 1].x, list[i].y - list[i - 1].y)
                assertEquals(list[i].stepPx, step, 0.01f)
                assertTrue("qadam $step", step <= 10.01f)
                steps++
                if (step in range.start - 0.01f..range.endInclusive + 0.01f) inRange++
            }
        }
        // Chetga taqalganda sikl markaziga qisqaroq qadam bo'lishi mumkin — kamdan-kam.
        assertTrue("oraliqda: $inRange/$steps", inRange >= steps * 97 / 100)
    }

    @Test
    fun everyCycleStartsSomewhereElse() {
        val starts = taps(2000).filter { it.indexInCycle == 0 }
        var far = 0
        for (i in 1 until starts.size) {
            if (hypot(starts[i].x - starts[i - 1].x, starts[i].y - starts[i - 1].y) >= 150f) far++
        }
        assertEquals(starts.size - 1, far)
    }
}
