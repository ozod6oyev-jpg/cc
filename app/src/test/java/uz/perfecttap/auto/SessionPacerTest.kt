package uz.perfecttap.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

class SessionPacerTest {

    private val s = 1_000_000_000L

    /**
     * Kutish tugagach tap blok perfect zonaga kelguncha kechikadi (0..0.8 s,
     * ba'zan bir o'tish o'tkazilib +0.8 s). 450 blok baribir 20 daqiqada.
     */
    @Test
    fun placesTheTargetInTheGivenTimeWithRandomGaps() {
        val world = Random(3)
        val pacer = SessionPacer(Random(5))
        var now = 1_000 * s
        pacer.start(now)
        val intervals = ArrayList<Double>()
        var last = now
        while (!pacer.done) {
            val hold = pacer.holdUntilNs
            var tap = maxOf(now, hold) + (world.nextDouble() * 0.8 * s).toLong()
            if (world.nextDouble() < 0.15) tap += 800_000_000L
            pacer.onTap(tap)
            if (pacer.placed > 1) intervals.add((tap - last) / 1e9)
            last = tap
            now = tap
        }
        val elapsedS = (now - pacer.startNs) / 1e9
        val mean = intervals.average()
        val sd = sqrt(intervals.sumOf { (it - mean) * (it - mean) } / intervals.size)
        println("pacer: blocks=${pacer.placed} elapsed=${"%.0f".format(elapsedS)} s mean=${"%.2f".format(mean)} sd=${"%.2f".format(sd)} min=${"%.2f".format(intervals.min())} max=${"%.2f".format(intervals.max())}")
        assertEquals(450, pacer.placed)
        assertTrue("elapsed=$elapsedS", abs(elapsedS - 1200.0) <= 20.0)
        assertTrue("sd=$sd", sd >= 0.6)
        assertTrue("min=${intervals.min()}", intervals.min() >= 1.0)
        assertTrue("max=${intervals.max()}", intervals.max() <= 7.0)
    }

    /** Kechikib qolsa (masalan, o'yin bir zum to'xtadi) qolgan bloklar tezlashadi. */
    @Test
    fun catchesUpAfterAStall() {
        val pacer = SessionPacer(Random(9))
        var now = 0L
        pacer.start(now)
        pacer.onTap(now)
        now += 10 * 60 * s // 10 daqiqa hech narsa qo'yilmadi
        while (!pacer.done) {
            now = maxOf(now, pacer.holdUntilNs) + 300_000_000L
            pacer.onTap(now)
        }
        val elapsedS = (now - pacer.startNs) / 1e9
        assertTrue("elapsed=$elapsedS", elapsedS <= 1260.0)
    }
}
