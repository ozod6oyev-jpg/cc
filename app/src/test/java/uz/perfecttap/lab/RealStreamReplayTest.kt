package uz.perfecttap.lab

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Haqiqiy o'yin kadrlari (194525 sessiyasi, test resursi) [AutoPlacer] orqali
 * qayta o'ynatiladi. Bu oqimda kadr kontenti vaqt belgisidan 25 ms gacha orqada
 * qoladi (kadrlar tashlanadi va takrorlanadi), shuning uchun bitta kadrdan
 * olingan tezlik 600..3500 px/s orasida sakraydi.
 *
 * «Haqiqat» har oyoq uchun keyin, oyoqning **barcha** namunalaridan olinadi
 * (Theil–Sen); placer esa faqat o'tgan kadrlarni ko'radi. Rejalashtirilgan har
 * tapning bashorat qilingan o'tish vaqti haqiqat bilan solishtiriladi.
 */
class RealStreamReplayTest {

    private class Frame(
        val tNs: Long,
        val center: Float,
        val width: Float,
        val bandY: Int,
        val stackLeft: Float,
        val stackRight: Float
    ) {
        var leg = -1
    }

    private class Leg(val crossingNs: Double)

    @Test
    fun scheduledCrossingsMatchTheWholeLegFit() {
        val stream = javaClass.classLoader!!.getResourceAsStream("replay-194525.csv")
        val frames = ArrayList<Frame>()
        val taps = ArrayList<Long>()
        stream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.startsWith("#")) continue
                val p = line.split(',')
                when (p[0]) {
                    "f" -> {
                        val left = p[2].toFloat()
                        val right = p[3].toFloat()
                        frames.add(
                            Frame(p[1].toLong(), (left + right) * 0.5f, right - left, p[4].toInt(), p[5].toFloat(), p[6].toFloat())
                        )
                    }
                    "t" -> taps.add(p[1].toLong())
                }
            }
        }
        val legs = buildTruth(frames, taps)

        val placer = AutoPlacer(cooldownNs = 120_000_000L, learnPlayfield = true, minLegSamples = 12)
        val errorsMs = ArrayList<Double>()
        var tapIndex = 0
        for (f in frames) {
            while (tapIndex < taps.size && taps[tapIndex] <= f.tNs) {
                placer.onPlaced(taps[tapIndex], taps[tapIndex])
                tapIndex++
            }
            val d = placer.onObservation(
                centerX = f.center,
                widthPx = f.width,
                stackLeft = f.stackLeft,
                stackRight = f.stackRight,
                contentTimestampNs = f.tNs,
                arrivalNs = f.tNs,
                nowNs = f.tNs,
                screenWidthPx = 1080f
            )
            if (d.scheduleInNs < 0L || d.throughBounce || f.leg < 0) continue
            errorsMs.add((f.tNs + d.scheduleInNs - legs[f.leg].crossingNs) / 1e6)
        }

        val big = errorsMs.count { abs(it) > 10.0 }
        val sorted = errorsMs.map { abs(it) }.sorted()
        val p90 = sorted[(sorted.size * 9) / 10]
        println("replay: scheduled=${errorsMs.size} |err|>10ms=$big p90=${"%.2f".format(p90)} ms")
        assertTrue("juda kam reja: ${errorsMs.size}", errorsMs.size >= 50)
        // Eski usul (6 namuna, oxirgi kadrdan) shu oqimda ~16% ni 10 ms dan ortiq adashardi.
        assertTrue("|err|>10ms: $big/${errorsMs.size}", big <= errorsMs.size * 3 / 100)
        assertTrue("p90=$p90", p90 <= 4.0)
    }

    /**
     * Oyoqlar: tapdan tapgacha (bir blok), bir xil tasma Y da, yo'nalish
     * almashguncha. Har oyoqda stack markazidan o'tish vaqti barcha namunalar
     * bo'yicha baholanadi; o'tish oyoq ichida bo'lmasa, oyoq hisobga olinmaydi.
     */
    private fun buildTruth(frames: List<Frame>, taps: List<Long>): List<Leg> {
        val legs = ArrayList<Leg>()
        var start = 0
        var tapIndex = 0
        while (start < frames.size) {
            while (tapIndex < taps.size && taps[tapIndex] <= frames[start].tNs) tapIndex++
            val blockEnd = if (tapIndex < taps.size) taps[tapIndex] else Long.MAX_VALUE
            var end = start
            while (end < frames.size && frames[end].tNs < blockEnd) end++
            splitLegs(frames.subList(start, end), legs)
            start = if (end == start) start + 1 else end
        }
        return legs
    }

    private fun splitLegs(block: List<Frame>, legs: MutableList<Leg>) {
        if (block.size < 16) return
        val bandY = block.map { it.bandY }.sorted()[block.size / 2]
        val onBand = block.filter { abs(it.bandY - bandY) < 25 }
        var leg = ArrayList<Frame>()
        var direction = 0
        var prev: Frame? = null
        for (f in onBand) {
            val p = prev
            if (p != null) {
                val dx = f.center - p.center
                if (abs(dx) >= 2f) {
                    val d = if (dx > 0f) 1 else -1
                    if (direction != 0 && d != direction) {
                        closeLeg(leg, legs)
                        leg = ArrayList()
                    }
                    direction = d
                }
            }
            leg.add(f)
            prev = f
        }
        closeLeg(leg, legs)
    }

    private fun closeLeg(leg: List<Frame>, legs: MutableList<Leg>) {
        if (leg.size < 16) return
        val slopes = ArrayList<Double>()
        for (i in leg.indices) for (j in i + 1 until leg.size) {
            val dt = leg[j].tNs - leg[i].tNs
            if (dt >= 40_000_000L) slopes.add((leg[j].center - leg[i].center) / (dt / 1e9))
        }
        if (slopes.isEmpty()) return
        val v = median(slopes)
        if (abs(v) < 50.0) return
        val t0 = leg[0].tNs
        val a = median(leg.map { it.center - v * ((it.tNs - t0) / 1e9) })
        val stack = median(leg.map { ((it.stackLeft + it.stackRight) * 0.5f).toDouble() })
        val crossingNs = t0 + (stack - a) / v * 1e9
        if (crossingNs < leg.first().tNs || crossingNs > leg.last().tNs) return
        legs.add(Leg(crossingNs))
        for (f in leg) f.leg = legs.size - 1
    }

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) * 0.5
    }
}
