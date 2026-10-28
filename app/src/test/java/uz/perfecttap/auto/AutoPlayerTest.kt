package uz.perfecttap.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.perfecttap.lab.GameSim
import java.util.Random
import kotlin.math.abs

class AutoPlayerTest {

    private val ms = 1_000_000L

    /**
     * Yopiq sikl: o'yin 120 Hz da yuradi va bosishni **kadr chegarasida**
     * qabul qiladi (haqiqiy o'yinlar kabi), kamera [streamDelayMs] kechikadi,
     * tap esa yuborilgandan [inputLatencyMs] keyin yetib boradi.
     */
    private class Loop(
        val inputLatencyMs: Double,
        val streamDelayMs: Long = 27,
        val noisePx: Float = 0.7f,
        /** Bosish kechikishining tebranishi (±ms), Shizuku o'lchovidagidek. */
        val latencyJitterMs: Double = 1.0,
        /** Shu ehtimol bilan bosish ±[outlierMs] ga adashadi (haqiqiy o'yindagi sakrashlar). */
        val outlierChance: Double = 0.0,
        val outlierMs: Double = 0.0,
        baseSpeed: Float = 1500f,
        growth: Float = 1.04f,
        perfectTolerancePx: Float = 6f,
        config: AutoPlayer.Config = AutoPlayer.Config()
    ) {
        private val ms = 1_000_000L
        val frameNs = 8_333_333L
        val sim = GameSim(1080, 2340, blockWidth = 604.8f, blockHeight = 90.7f, perfectTolerancePx = perfectTolerancePx).apply {
            baseSpeedPxPerSec = baseSpeed
            speedGrowthPerBlock = growth
        }
        val player = AutoPlayer(config)
        private val random = Random(7)
        private val historyNs = ArrayList<Long>()
        private val historyX = ArrayList<Float>()
        private val historyStack = ArrayList<FloatArray>()
        var nowNs = 1_000_000_000L
        private var plannedFireNs = -1L
        private var pendingTouchNs = -1L
        var offsets = ArrayList<Float>()

        fun run(seconds: Double, onFrameStart: (Loop) -> Unit = {}) {
            val frames = (seconds * 120).toInt()
            repeat(frames) {
                nowNs += frameNs
                onFrameStart(this)
                // O'yin: kadr boshida yetib kelgan bosishni qo'llaydi.
                if (pendingTouchNs in 1..nowNs) {
                    pendingTouchNs = -1L
                    val placement = sim.place()
                    offsets.add(placement.offsetPx)
                }
                sim.step(frameNs / 1e9f)
                historyNs.add(nowNs)
                historyX.add(sim.movingX)
                historyStack.add(floatArrayOf(sim.stackX, sim.stackX + sim.width, sim.width))

                // Kamera: [streamDelayMs] oldingi kadr, har 8.3 ms da bitta.
                val contentNs = nowNs - streamDelayMs * ms
                val i = historyNs.indexOfLast { it <= contentNs }
                if (i >= 0) {
                    val left = historyX[i] + noise()
                    val stack = historyStack[i]
                    val width = stack[2]
                    val fire = player.onFrame(
                        hasBlock = true,
                        left = left,
                        right = left + width,
                        hasStack = true,
                        stackLeft = stack[0] + noise(),
                        stackRight = stack[1] + noise(),
                        imageTimestampNs = nowNs,
                        nowNs = nowNs,
                        screenWidthPx = 1080f,
                        vsyncNs = nowNs,
                        framePeriodNs = frameNs
                    )
                    if (fire >= 0L && plannedFireNs < 0L && pendingTouchNs < 0L) plannedFireNs = fire
                }
                // Rejalashtiruvchi: vaqti kelgan bosishni yuboradi.
                if (plannedFireNs in 1..(nowNs + frameNs)) {
                    val fireAt = maxOf(plannedFireNs, nowNs)
                    plannedFireNs = -1L
                    var jitter = (random.nextDouble() * 2 - 1) * latencyJitterMs
                    if (random.nextDouble() < outlierChance) {
                        jitter += if (random.nextBoolean()) outlierMs else -outlierMs
                    }
                    pendingTouchNs = fireAt + ((inputLatencyMs + jitter) * ms).toLong()
                    player.onTapFired(fireAt, fireAt)
                }
            }
        }

        private fun noise() = (random.nextFloat() * 2f - 1f) * noisePx
    }

    @Test
    fun scanFindsAStaticStackAndAMovingBlock() {
        val player = AutoPlayer()
        player.scan(0L)
        var t = 0L
        var x = 300f
        repeat(60) {
            t += 8 * ms
            x += 10f
            player.onFrame(true, x, x + 600f, true, 240f, 840f, t, t, 1080f)
        }
        assertEquals(AutoPlayer.State.READY, player.state)
    }

    @Test
    fun scanIgnoresTwoStaticBlocks() {
        val player = AutoPlayer()
        player.scan(0L)
        var t = 0L
        repeat(120) {
            t += 8 * ms
            player.onFrame(true, 240f, 840f, true, 240f, 840f, t, t, 1080f)
        }
        assertEquals(AutoPlayer.State.SCANNING, player.state)
    }

    /** Skanerlash maydon topilishi bilan tugaydi, qizib olish o'tmaguncha «Boshlash» yo'q. */
    @Test
    fun scanEndsOnTheFieldThenWarmsUpBeforeStartIsAllowed() {
        val loop = Loop(inputLatencyMs = 8.0)
        loop.player.scan(5_000 * ms)
        loop.run(1.0)
        assertEquals(AutoPlayer.State.WARMUP, loop.player.state)
        assertFalse("qizib olinmaguncha boshlab bo'lmaydi", loop.player.start(loop.nowNs))
        loop.run(3.5)
        assertEquals(AutoPlayer.State.WARMUP, loop.player.state)
        loop.run(2.0)
        assertEquals(AutoPlayer.State.READY, loop.player.state)
        assertEquals(0, loop.player.taps)
        assertTrue(loop.player.start(loop.nowNs))
        loop.run(3.0)
        assertEquals(AutoPlayer.State.RUNNING, loop.player.state)
        assertTrue(loop.player.taps > 0)
    }

    @Test
    fun startIsRefusedWhileScanning() {
        val player = AutoPlayer()
        player.scan(0L)
        assertFalse(player.start(0L))
        assertEquals(AutoPlayer.State.SCANNING, player.state)
    }

    @Test
    fun placesPerfectlyAsSoonAsTheBlockIsInTheZone() {
        val loop = Loop(inputLatencyMs = 8.0)
        loop.player.scan(0L)
        loop.run(1.0)
        loop.player.start(loop.nowNs)
        loop.run(90.0)

        // Lead/τ birinchi bloklarda o'rganiladi; baho o'rganilgandan keyingi qismda.
        val tail = loop.offsets.drop(20)
        val perfect = tail.count { abs(it) <= 6f }
        println("auto: errors at " + loop.offsets.withIndex().filter { abs(it.value) > 6f }.map { "${it.index}:${it.value.toInt()}" })
        println("auto: perfect=$perfect/${tail.size} of ${loop.offsets.size} lead=${loop.player.leadNs / 1e6} bias=${loop.player.timeBiasNs / 1e6}")
        // Qo'shimcha kutish yo'q: 90 s da ko'p blok qo'yiladi.
        assertTrue("placements=${loop.offsets.size}", loop.offsets.size >= 120)
        assertTrue("perfect=$perfect/${tail.size}", perfect >= tail.size * 90 / 100)
    }

    @Test
    fun learnedCalibrationIsPerfectFromTheFirstBlock() {
        // Birinchi sessiya o'rganadi, ikkinchisi o'sha qiymatlardan boshlaydi.
        val first = Loop(inputLatencyMs = 8.0)
        first.player.scan(0L)
        first.run(1.0)
        first.player.start(first.nowNs)
        first.run(60.0)

        val second = Loop(inputLatencyMs = 8.0)
        second.player.restoreCalibration(first.player.leadNs, first.player.timeBiasNs)
        second.player.scan(0L)
        second.run(1.0)
        second.player.start(second.nowNs)
        second.run(30.0)
        val perfect = second.offsets.count { abs(it) <= 6f }
        println("restored: perfect=$perfect/${second.offsets.size}")
        assertTrue("perfect=$perfect/${second.offsets.size}", perfect >= second.offsets.size * 85 / 100)
    }

    @Test
    fun leadAdaptsWhenTheRealInputLatencyIsLonger() {
        // Haqiqiy kechikish 20 ms, boshlang'ich taxmin 8 ms: natijalar bo'yicha
        // lead o'zini to'g'rilashi kerak.
        val loop = Loop(inputLatencyMs = 20.0, baseSpeed = 1200f, growth = 1.02f)
        loop.player.scan(0L)
        loop.run(1.0)
        loop.player.start(loop.nowNs)
        loop.run(120.0)

        val tail = loop.offsets.takeLast(40)
        val perfect = tail.count { abs(it) <= 6f }
        println("adapt: lead=${loop.player.leadNs / 1e6} bias=${loop.player.timeBiasNs / 1e6} tail perfect=$perfect/${tail.size}")
        assertTrue("tail perfect=$perfect/${tail.size}", perfect >= tail.size * 85 / 100)
    }

    /**
     * Haqiqiy o'yin (211356): tezlik ~1300 px/s da, perfect chegarasi ~20 px,
     * bosish esa vaqti-vaqti bilan ±2 kadrga adashadi (ishorasi tasodifiy).
     * Bunday yakka xatolar lead'ni kadrlab surmasligi kerak — eski usulda lead
     * 12 dan 37 ms gacha «sayr» qilib, erta tushishlarni o'zi keltirardi.
     */
    @Test
    fun sporadicFrameSlipsDoNotWalkTheLead() {
        val loop = Loop(
            inputLatencyMs = 12.0,
            latencyJitterMs = 2.0,
            outlierChance = 0.06,
            outlierMs = 17.0,
            baseSpeed = 1300f,
            growth = 1f,
            perfectTolerancePx = 16f
        )
        val startLead = loop.player.leadNs
        var minLead = startLead
        var maxLead = startLead
        loop.player.scan(0L)
        loop.run(1.0)
        loop.player.start(loop.nowNs)
        repeat(240) {
            loop.run(0.5)
            minLead = minOf(minLead, loop.player.leadNs)
            maxLead = maxOf(maxLead, loop.player.leadNs)
        }
        val imperfect = loop.offsets.count { abs(it) > 16f }
        println(
            "slips: placements=${loop.offsets.size} imperfect=$imperfect " +
                "lead=${minLead / 1e6}..${maxLead / 1e6} ms"
        )
        assertTrue("placements=${loop.offsets.size}", loop.offsets.size >= 150)
        assertTrue("lead=${minLead / 1e6}..${maxLead / 1e6}", maxLead - minLead <= 10 * ms)
        assertTrue("imperfect=$imperfect/${loop.offsets.size}", imperfect <= loop.offsets.size * 6 / 100)
    }

    /** O'yin tugadi: maydon yo'qoladi — qayta skanerlash, «Boshlash» o'chadi. */
    @Test
    fun rescansWhenTheFieldDisappears() {
        val player = AutoPlayer()
        player.scan(0L)
        var t = 0L
        var x = 300f
        repeat(60) {
            t += 8 * ms
            x += 10f
            player.onFrame(true, x, x + 600f, true, 240f, 840f, t, t, 1080f)
        }
        player.start(t)
        repeat(600) {
            t += 8 * ms
            player.onFrame(false, 0f, 0f, false, 0f, 0f, t, t, 1080f)
        }
        assertEquals(AutoPlayer.State.SCANNING, player.state)
        assertTrue(player.lostNotice)
        assertFalse(player.start(t))
    }

    /**
     * Haqiqiy sessiya (135243): buzilgan sessiyadan saqlangan τ = +13 ms
     * bloklarni muntazam erta tushirdi, xatolar esa «ishonchsiz» deb
     * o'rganilmadi. Endi bir necha natijada to'g'rilanishi kerak.
     */
    @Test
    fun recoversFromACorruptedSavedCalibration() {
        val loop = Loop(inputLatencyMs = 8.0)
        loop.player.restoreCalibration(8 * ms, 13_350_000L)
        loop.player.scan(0L)
        loop.run(1.0)
        loop.player.start(loop.nowNs)
        loop.run(60.0)
        val tail = loop.offsets.drop(15)
        val perfect = tail.count { abs(it) <= 6f }
        println("corrupted: lead=${loop.player.leadNs / 1e6} bias=${loop.player.timeBiasNs / 1e6} perfect=$perfect/${tail.size}")
        assertTrue("perfect=$perfect/${tail.size}", perfect >= tail.size * 85 / 100)
    }

    /** Kuzatuv joyida turgan narsaga (hisob raqami, yozuv) o'tdi — tap bekor. */
    @Test
    fun staticTargetCancelsThePlannedTap() {
        val player = AutoPlayer()
        player.scan(0L)
        var t = 0L
        var x = 300f
        repeat(60) {
            t += 8 * ms
            x += 10f
            player.onFrame(true, x, x + 600f, true, 240f, 840f, t, t, 1080f)
        }
        assertTrue(player.start(t))
        var last = 0L
        repeat(4) {
            t += 8 * ms
            last = player.onFrame(true, 584f, 696f, true, 396f, 507f, t, t, 1080f)
        }
        assertEquals(AutoPlayer.CANCEL, last)
        assertEquals(AutoPlayer.REASON_UNTRACKED, player.lastReason)
    }
}
