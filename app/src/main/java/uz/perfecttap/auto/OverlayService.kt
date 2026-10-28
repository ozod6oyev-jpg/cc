package uz.perfecttap.auto

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.util.Log
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.WindowManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uz.perfecttap.BuildConfig
import uz.perfecttap.R
import uz.perfecttap.capture.CaptureBus
import uz.perfecttap.capture.CaptureService
import uz.perfecttap.capture.LabObservation
import uz.perfecttap.core.Displays
import uz.perfecttap.databinding.OverlayPanelBinding
import uz.perfecttap.inject.TapChannel
import uz.perfecttap.inject.TapChannels
import uz.perfecttap.lab.LabSessionRecorder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Qalqib chiquvchi avto-kliker paneli: Skanerlash → (Qizib olish) → Ishlash.
 *
 * Kadrlar [CaptureService] dan (lab rejimi, butun ekran) keladi, qaror
 * [AutoPlayer] da, bosish esa [TapScheduler] orqali root/Shizuku kanaliga
 * yuboriladi. Hamma [AutoPlayer] chaqiruvlari bitta `pt-auto` oqimida.
 *
 * Har sessiya `files/sessions/auto-*.csv` ga yoziladi (labdagi format), uni
 * bosh ekrandagi «Oxirgi sessiyani saqlash» bilan eksport qilish mumkin.
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "PerfectTap"
        private const val ACTION_CLOSE = "uz.perfecttap.action.CLOSE_OVERLAY"
        private val WARMUP_CHOICES_S = intArrayOf(5, 10, 0)
        /** Tap nuqtasi: ekran markazi (o'yin har joyga bosishni qabul qiladi). */
        private const val TAP_Y_FRACTION = 0.5f
        /**
         * O'rganilgan lead / nishon tuzatishi keyingi sessiyalar uchun saqlanadi.
         * v2: v0.6.7 gacha lead har xatoda bir kadrga «sayr» qilardi — o'sha
         * saqlangan qiymatlar o'qilmaydi, yangi boshlang'ich qiymatdan boshlanadi.
         */
        private const val PREFS = "auto_calibration"
        private const val KEY_LEAD = "lead_ns_v2"
        private const val KEY_BIAS = "bias_ns_v2"
        /** Diagnostika: skanerlash paytidagi kadrlar (PNG). */
        private const val MAX_SNAPSHOTS = 5
        private const val SNAPSHOT_EVERY_NS = 3_000_000_000L
        /**
         * Diagnostika: o'yin paytida kuzatuv sakraganda va xato natijada olinadigan
         * kadrlar (skanerlash kadrlaridan alohida hisob).
         */
        private const val MAX_RUN_SNAPSHOTS = 8
        /** Tasma Y shundan ko'p sakrasa — kuzatuv boshqa narsaga o'tgan bo'lishi mumkin. */
        private const val BAND_JUMP_PX = 150
        /**
         * Test rejimi (anti-bot sinovi): ekranning tepasidan va pastidan shuncha
         * sm ga tegilmaydi (Telegram sarlavhasi tugmalari, imo-ishora paneli).
         */
        private const val TEST_TOP_EXCLUDED_CM = 1.5f
        private const val TEST_BOTTOM_EXCLUDED_CM = 1.0f
        private const val CM_PER_INCH = 2.54f
        /**
         * Ishlash paytida har shuncha vaqtda ekran rasmi: `<sessiya>-5delta/5deltaN.png`
         * papkasiga va `<sessiya>-5delta.zip` ga. Rasm olinayotganda panel yopiladi.
         */
        private const val DELTA_SHOT_EVERY_MS = 5 * 60 * 1000L
        const val DELTA_PREFIX = "5delta"
        /** Panel yopilgandan keyin ekran yangilanishi uchun kutish. */
        private const val PANEL_HIDE_SETTLE_MS = 250L
        /** Kadr shuncha vaqtda olinmasa, panel baribir qaytariladi. */
        private const val PANEL_HIDE_MAX_MS = 1_500L
        /** Test rejimi: rasm tap rejalashtirilmagan paytda (kutishning shuncha qismi qolganda) olinadi. */
        private const val DELTA_SHOT_QUIET_NS = 1_000_000_000L
        private const val DELTA_SHOT_QUIET_WAIT_MS = 10_000L
        /** Test tugaganda oxirgi davriy rasmdan shuncha o'tgan bo'lsa, yakuniy rasm olinadi. */
        private const val DELTA_FINAL_MIN_GAP_MS = 60_000L

        fun startIntent(context: Context): Intent = Intent(context, OverlayService::class.java)

        /** Sessiyaning davriy rasmlari (`5delta1.png`, `5delta2.png`, …) raqam tartibida. */
        fun deltaShots(dir: File): List<File> =
            dir.listFiles { f -> f.name.startsWith(DELTA_PREFIX) && f.extension == "png" }
                .orEmpty()
                .sortedBy { it.nameWithoutExtension.removePrefix(DELTA_PREFIX).toIntOrNull() ?: Int.MAX_VALUE }

        fun closeIntent(context: Context): Intent =
            Intent(context, OverlayService::class.java).setAction(ACTION_CLOSE)
    }

    private val engine = AutoPlayer()
    private val engineExecutor = Executors.newSingleThreadExecutor { Thread(it, "pt-auto") }
    private val engineDispatcher = engineExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var binding: OverlayPanelBinding? = null
    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null

    private var channel: TapChannel? = null
    private var scheduler: TapScheduler? = null
    // UI oqimida almashtiriladi, `pt-auto` oqimida yoziladi.
    @Volatile
    private var recorder: LabSessionRecorder? = null
    private var warmupIndex = 0
    /** Joriy sessiya fayllari nomining boshi (`auto-<vaqt>`). */
    @Volatile
    private var recorderBase: String? = null
    private var snapshotsTaken = 0
    private var runSnapshotsTaken = 0
    private var snapshotIndex = 0
    private var lastBandY = -1
    private var lastSnapshotNs = 0L
    private var screenWidth = 0
    private var screenHeight = 0
    @Volatile
    private var message: String? = null

    // Test rejimi: tasodifiy joy va ritm. UI oqimida tanlanadi; [pacer] va
    // [pattern] faqat `pt-auto` oqimida ishlatiladi.
    private var testMode = false
    @Volatile
    private var pacingActive = false
    private val pacer = SessionPacer()
    private val pattern = TapPattern()
    /** Keyingi tap joyi (`pt-auto` tayyorlaydi, `pt-tap` o'qiydi). */
    @Volatile
    private var nextTap: TapPattern.Tap? = null
    /** Panelning ekrandagi o'rni — unga tap tushmasligi kerak. */
    @Volatile
    private var panelArea: TapPattern.Area? = null
    private var topExcludedPx = 0f
    private var bottomExcludedPx = 0f

    /** Davriy (5 daqiqalik) rasmlar: ish jarayoni, raqam va oxirgi rasm vaqti. UI oqimida. */
    private var deltaJob: Job? = null
    private var deltaIndex = 0
    private var lastDeltaShotMs = 0L
    private var panelHidden = false

    // Displey kadr panjarasi: o'yin bosishni kadr chegarasida qabul qiladi.
    private var vsyncThread: HandlerThread? = null
    @Volatile
    private var lastVsyncNs = 0L
    private var framePeriodNs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (binding == null) showPanel()
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        val size = Displays.size(this)
        screenWidth = size.width
        screenHeight = size.height
        val ydpi = resources.displayMetrics.ydpi
        topExcludedPx = TEST_TOP_EXCLUDED_CM / CM_PER_INCH * ydpi
        bottomExcludedPx = TEST_BOTTOM_EXCLUDED_CM / CM_PER_INCH * ydpi
        val display = getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val hz = display?.refreshRate ?: 60f
        framePeriodNs = (1_000_000_000.0 / hz.coerceIn(30f, 240f)).toLong()
        startVsyncClock()

        // Kamera kadrlari yo'qotilmasdan, bitta oqimda qayta ishlanadi.
        scope.launch(engineDispatcher) {
            for (observation in CaptureBus.labObservations) handleObservation(observation)
        }
        scope.launch {
            CaptureBus.stats.collect { stats -> stats?.let { recorder?.stats(it) } }
        }
        scope.launch {
            while (isActive) {
                render()
                delay(100)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        scheduler?.close()
        scheduler = null
        channel?.let { runCatching { it.close() } }
        channel = null
        closeRecorder("overlay closed")
        vsyncThread?.quitSafely()
        engineExecutor.shutdown()
        stopDeltaShots()
        binding?.let { b -> if (!panelHidden) runCatching { windowManager?.removeView(b.root) } }
        binding = null
        // Panel uchun ochilgan ekran oqimi ham yopiladi.
        if (CaptureBus.running.value) startService(CaptureService.stopIntent(this))
        super.onDestroy()
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun showPanel() {
        val wm = getSystemService(WindowManager::class.java) ?: return
        windowManager = wm
        val b = OverlayPanelBinding.inflate(LayoutInflater.from(this))
        binding = b
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // FLAG_SECURE: ekran oqimida panel qora ko'rinadi (o'yin foni kabi),
            // shuning uchun uning tugmalari «blok» deb aniqlanmaydi.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (12 * resources.displayMetrics.density).roundToInt()
            // Ekran tepasi: Y-tahlil hududi (balandlikning 22% dan pasti) bo'sh qoladi.
            y = (screenHeight * 0.03f).roundToInt()
        }
        params = lp
        wm.addView(b.root, lp)
        b.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePanelArea() }

        b.btnClose.setOnClickListener { stopSelf() }
        b.btnScan.setOnClickListener { onScan() }
        b.btnStart.setOnClickListener { onStart() }
        b.btnStop.setOnClickListener { onStop() }
        b.btnWarmup.setOnClickListener {
            warmupIndex = (warmupIndex + 1) % WARMUP_CHOICES_S.size
            render()
        }
        b.btnMode.setOnClickListener {
            testMode = !testMode
            render()
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        b.dragHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (event.rawX - downX).roundToInt()
                    lp.y = startY + (event.rawY - downY).roundToInt()
                    runCatching { wm.updateViewLayout(b.root, lp) }
                    b.root.post { updatePanelArea() }
                    true
                }
                else -> false
            }
        }
        render()
    }

    /** UI oqimida: «Boshlash»dan har [DELTA_SHOT_EVERY_MS] da ekran rasmi. */
    private fun startDeltaShots() {
        stopDeltaShots()
        deltaIndex = 0
        lastDeltaShotMs = System.currentTimeMillis()
        deltaJob = scope.launch {
            while (isActive) {
                delay(DELTA_SHOT_EVERY_MS)
                takeDeltaShot()
            }
        }
    }

    private fun stopDeltaShots() {
        deltaJob?.cancel()
        deltaJob = null
    }

    /**
     * UI oqimida: panel yopiladi, keyingi kadr `5deltaN.png` bo'lib saqlanadi,
     * panel qaytariladi, so'ng papkadagi hamma rasm ZIP ga qayta yoziladi.
     */
    private suspend fun takeDeltaShot() {
        val base = recorderBase ?: return
        val sessions = File(filesDir, "sessions")
        val dir = File(sessions, "$base-$DELTA_PREFIX").apply { mkdirs() }
        if (pacingActive) {
            // Test rejimida rasm ritm kutishi ichida olinadi: kadr nusxasi aniqlashni
            // bir zum to'xtatadi, tap esa bu paytda rejalashtirilmagan.
            withTimeoutOrNull(DELTA_SHOT_QUIET_WAIT_MS) {
                while (withContext(engineDispatcher) { pacer.holdUntilNs - System.nanoTime() } < DELTA_SHOT_QUIET_NS) delay(50)
            }
        }
        deltaIndex++
        lastDeltaShotMs = System.currentTimeMillis()
        val file = File(dir, "$DELTA_PREFIX$deltaIndex.png")
        val captured = CompletableDeferred<Unit>()
        val saved = CompletableDeferred<Boolean>()
        hidePanel()
        try {
            delay(PANEL_HIDE_SETTLE_MS)
            CaptureBus.requestSnapshot(
                file,
                onCaptured = { captured.complete(Unit) },
                onSaved = { saved.complete(it) }
            )
            withTimeoutOrNull(PANEL_HIDE_MAX_MS) { captured.await() }
        } finally {
            restorePanel()
        }
        val ok = withTimeoutOrNull(10_000L) { saved.await() } ?: false
        recorder?.event("auto", "snapshot ${dir.name}/${file.name} ok=$ok")
        if (ok) {
            withContext(Dispatchers.IO) {
                runCatching { zipDeltaShots(dir, File(sessions, "$base-$DELTA_PREFIX.zip")) }
                    .onFailure { Log.w(TAG, "delta zip failed", it) }
            }
        }
    }

    /** Papkadagi `5deltaN.png` lar (raqam tartibida) ZIP ichida shu papka nomi bilan. */
    private fun zipDeltaShots(dir: File, zipFile: File) {
        val shots = deltaShots(dir)
        val tmp = File(zipFile.parentFile, zipFile.name + ".tmp")
        ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
            for (shot in shots) {
                zip.putNextEntry(ZipEntry("${dir.name}/${shot.name}"))
                shot.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        if (!tmp.renameTo(zipFile)) {
            tmp.copyTo(zipFile, overwrite = true)
            tmp.delete()
        }
    }

    /** Rasmni panel ko'rinmagan holda olish uchun oyna vaqtincha olib tashlanadi. */
    private fun hidePanel() {
        val b = binding ?: return
        if (panelHidden) return
        runCatching { windowManager?.removeView(b.root) }.onSuccess { panelHidden = true }
    }

    private fun restorePanel() {
        val b = binding ?: return
        val lp = params ?: return
        if (!panelHidden) return
        runCatching { windowManager?.addView(b.root, lp) }
        panelHidden = false
    }

    /** Panelning haqiqiy ekran koordinatalari (sarlavha/status panel hisobga olingan). */
    private fun updatePanelArea() {
        val root = binding?.root ?: return
        if (panelHidden || !root.isAttachedToWindow || root.width <= 0 || root.height <= 0) return
        val location = IntArray(2)
        root.getLocationOnScreen(location)
        panelArea = TapPattern.Area(
            location[0].toFloat(),
            location[1].toFloat(),
            (location[0] + root.width).toFloat(),
            (location[1] + root.height).toFloat()
        )
    }

    /**
     * Skanerlash: maydon topilguncha kutadi, so'ng (tanlangan bo'lsa) qizib
     * oladi; «Boshlash» faqat shundan keyin faollashadi. Sessiya yozuvi va
     * diagnostika kadrlari shu yerdan boshlanadi.
     */
    private fun onScan() {
        if (!CaptureBus.running.value) {
            message = getString(R.string.overlay_no_capture)
            render()
            return
        }
        message = null
        // Butun ekran tahlil qilinadi (lab paneli yo'q).
        startService(CaptureService.labIntent(this, true, 0))
        val warmupS = WARMUP_CHOICES_S[warmupIndex]
        startRecorder(warmupS)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        scope.launch(engineDispatcher) {
            scheduler?.cancel()
            if (prefs.contains(KEY_LEAD)) {
                engine.restoreCalibration(prefs.getLong(KEY_LEAD, 0L), prefs.getLong(KEY_BIAS, 0L))
            }
            engine.scan(warmupS * 1_000_000_000L)
            snapshotsTaken = 0
            runSnapshotsTaken = 0
            snapshotIndex = 0
            lastBandY = -1
            lastSnapshotNs = 0L
            recorder?.event(
                "auto",
                "scan warmup_s=$warmupS lead_ms=${engine.leadNs / 1e6} bias_ms=${engine.timeBiasNs / 1e6}"
            )
        }
        // Bosish kanali skanerlash/qizish paytida tayyorlanadi: «Boshlash» kutmaydi.
        scope.launch { ensureTapChannel() }
        render()
    }

    private suspend fun ensureTapChannel(): Boolean {
        if (channel != null) return true
        message = getString(R.string.overlay_opening_channel)
        render()
        val opened = withContext(Dispatchers.IO) {
            runCatching { TapChannels.open(this@OverlayService) }.getOrNull()
        }
        channel = opened?.channel
        message = if (channel == null) getString(R.string.overlay_no_channel) else null
        render()
        return channel != null
    }

    private fun onStart() {
        scope.launch {
            if (!ensureTapChannel()) return@launch
            if (scheduler == null) {
                scheduler = TapScheduler(
                    onFire = ::fireTap,
                    threadSetup = {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                    }
                )
            }
            val test = testMode
            val started = withContext(engineDispatcher) {
                scheduler?.rearm()
                val now = System.nanoTime()
                val ok = engine.start(now)
                pacingActive = ok && test
                if (pacingActive) {
                    pacer.start(now)
                    pattern.reset(screenWidth.toFloat(), screenHeight.toFloat(), topExcludedPx, bottomExcludedPx)
                    nextTap = pattern.next(panelArea)
                }
                ok
            }
            if (started) {
                recorder?.event("auto", "start")
                startDeltaShots()
                if (test) {
                    message = null
                    recorder?.event(
                        "auto",
                        String.format(
                            Locale.US,
                            "test start target=%d duration_s=%d top_excluded_px=%.0f bottom_excluded_px=%.0f panel=%s",
                            pacer.targetBlocks,
                            pacer.durationNs / 1_000_000_000L,
                            topExcludedPx,
                            bottomExcludedPx,
                            panelArea
                        )
                    )
                }
            }
            render()
        }
    }

    private fun onStop() {
        scope.launch {
            withContext(engineDispatcher) {
                scheduler?.cancel()
                engine.stop()
                pacingActive = false
            }
            recorder?.event("auto", "stop")
            stopDeltaShots()
            render()
        }
    }

    /** `pt-tap` oqimida, rejalashtirilgan paytda. */
    private fun fireTap(fireAtNs: Long) {
        val ch = channel ?: return
        val planned = if (pacingActive) nextTap else null
        val x = planned?.x ?: (screenWidth * 0.5f)
        val y = planned?.y ?: (screenHeight * TAP_Y_FRACTION)
        val sentNs = System.nanoTime()
        ch.tap(x, y)
        scope.launch(engineDispatcher) {
            engine.onTapFired(fireAtNs, sentNs)
            recorder?.tap(sentNs, x, y, fireAtNs, engine.taps)
            recorder?.event(
                "auto",
                String.format(
                    Locale.US,
                    "fire tap=%d late_ms=%.2f speed=%.0f frame_err_px=%.1f wait_ms=%.0f lead_ms=%.2f bias_ms=%.2f",
                    engine.taps,
                    (sentNs - fireAtNs) / 1e6,
                    engine.plannedSpeedPxPerSec,
                    engine.plannedFrameErrorPx,
                    engine.plannedWaitNs / 1e6,
                    engine.leadNs / 1e6,
                    engine.timeBiasNs / 1e6
                )
            )
            if (pacingActive && planned != null) {
                onTestTap(planned, sentNs)
                // Test tugadi: rejalashtiruvchi qurolsiz qoladi.
                if (!pacingActive) return@launch
            }
            scheduler?.rearm()
        }
    }

    /**
     * `pt-auto` oqimida: test rejimida tap qayd etiladi, keyingi kutish va joy
     * tanlanadi; [SessionPacer.targetBlocks] ga yetilsa sessiya to'xtaydi.
     */
    private fun onTestTap(tap: TapPattern.Tap, sentNs: Long) {
        val holdUntil = pacer.onTap(sentNs)
        engine.holdUntil(holdUntil)
        recorder?.event(
            "auto",
            String.format(
                Locale.US,
                "pace tap=%d x=%.1f y=%.1f cycle=%d i=%d/%d step_px=%.1f interval_ms=%.0f hold_ms=%.0f overhead_ms=%.0f left_s=%.1f",
                pacer.placed,
                tap.x,
                tap.y,
                tap.cycle,
                tap.indexInCycle + 1,
                tap.cycleSize,
                tap.stepPx,
                pacer.lastIntervalNs / 1e6,
                if (holdUntil > 0L) (holdUntil - sentNs) / 1e6 else 0.0,
                pacer.overheadNs / 1e6,
                pacer.remainingNs(sentNs) / 1e9
            )
        )
        if (!pacer.done) {
            nextTap = pattern.next(panelArea)
            return
        }
        // Belgilangan bloklar qo'yildi: test sessiyasi tugadi.
        scheduler?.cancel()
        engine.stop()
        pacingActive = false
        val elapsedS = ((sentNs - pacer.startNs) / 1_000_000_000L).toInt()
        message = getString(R.string.overlay_test_done, pacer.placed, elapsedS / 60, elapsedS % 60)
        recorder?.event("auto", "test done blocks=${pacer.placed} elapsed_s=$elapsedS perfect=${engine.perfectCount}")
        scope.launch {
            stopDeltaShots()
            // 20 daqiqalik testda 5, 10, 15 daqiqadagi rasmlarga yakuniy holat qo'shiladi.
            if (System.currentTimeMillis() - lastDeltaShotMs >= DELTA_FINAL_MIN_GAP_MS) takeDeltaShot()
            render()
        }
    }

    /** `pt-auto` oqimida. */
    private fun handleObservation(observation: LabObservation) {
        val nowNs = System.nanoTime()
        val before = engine.state
        val fireAt = engine.onFrame(
            hasBlock = observation.hasBlock,
            left = observation.left,
            right = observation.right,
            hasStack = observation.hasStack,
            stackLeft = observation.stackLeft,
            stackRight = observation.stackRight,
            imageTimestampNs = observation.imageTimestampNs,
            nowNs = nowNs,
            screenWidthPx = screenWidth.toFloat(),
            vsyncNs = lastVsyncNs,
            framePeriodNs = framePeriodNs
        )
        if (fireAt >= 0L) {
            scheduler?.plan(fireAt)
        } else if (fireAt == AutoPlayer.CANCEL && scheduler?.cancel() == true) {
            recorder?.event("auto", "cancel reason=${engine.lastReason} band_y=${observation.bandCenterY}")
        }
        if (engine.state == AutoPlayer.State.RUNNING && lastBandY >= 0 &&
            abs(observation.bandCenterY - lastBandY) > BAND_JUMP_PX
        ) {
            recorder?.event("auto", "band_jump from=$lastBandY to=${observation.bandCenterY}")
            requestRunSnapshot("band_jump")
        }
        lastBandY = observation.bandCenterY
        if (engine.state != before) {
            recorder?.event("auto", "state ${before.name} -> ${engine.state.name}")
            if (engine.state == AutoPlayer.State.SCANNING) scheduler?.cancel()
            // Maydon topilgan kadr ham saqlanadi: aniqlash nimani ko'rgani ma'lum bo'lsin.
            if (before == AutoPlayer.State.SCANNING) requestSnapshot(nowNs, force = true)
        }
        if (engine.state == AutoPlayer.State.SCANNING) requestSnapshot(nowNs, force = false)
        engine.consumeResult()?.let { result ->
            if (result.trusted) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putLong(KEY_LEAD, result.leadNs)
                    .putLong(KEY_BIAS, result.timeBiasNs)
                    .apply()
            }
            if (!result.perfect) requestRunSnapshot("imperfect tap=${result.tapIndex}")
            recorder?.placement(
                tNs = nowNs,
                offsetPx = result.offsetPx,
                perfect = result.perfect,
                cameraShifted = false,
                widthPx = result.widthPx,
                tapIndex = result.tapIndex
            )
            recorder?.event(
                "auto",
                String.format(
                    Locale.US,
                    "result tap=%d offset_px=%.1f perfect=%b err_ms=%.1f speed=%.0f lead_ms=%.2f bias_ms=%.2f trusted=%b",
                    result.tapIndex,
                    result.offsetPx,
                    result.perfect,
                    result.errorMs,
                    result.speedPxPerSec,
                    result.leadNs / 1e6,
                    result.timeBiasNs / 1e6,
                    result.trusted
                )
            )
        }
        recorder?.frame(
            tNs = nowNs,
            imageTimestampNs = observation.imageTimestampNs,
            arrivalNs = observation.arrivalNs,
            vsyncNs = 0L,
            matched = false,
            hasBlock = observation.hasBlock,
            leftPx = if (observation.hasBlock) observation.left else Float.NaN,
            rightPx = if (observation.hasBlock) observation.right else Float.NaN,
            bandY = observation.bandCenterY,
            stackLeftPx = if (observation.hasStack) observation.stackLeft else Float.NaN,
            stackRightPx = if (observation.hasStack) observation.stackRight else Float.NaN,
            detectMs = observation.detectMs,
            peaks = observation.peakCount,
            contentTsNs = observation.imageTimestampNs,
            tapCount = engine.taps,
            note = engine.lastReason
        )
    }

    /** Skanerlash paytida har [SNAPSHOT_EVERY_NS] da bitta kadr (ko'pi bilan [MAX_SNAPSHOTS]). */
    private fun requestSnapshot(nowNs: Long, force: Boolean) {
        if (snapshotsTaken >= MAX_SNAPSHOTS) return
        if (!force && lastSnapshotNs != 0L && nowNs - lastSnapshotNs < SNAPSHOT_EVERY_NS) return
        lastSnapshotNs = nowNs
        snapshotsTaken++
        saveSnapshot("scan")
    }

    /** O'yin paytidagi diagnostika kadri ([why] CSV'ga yoziladi). */
    private fun requestRunSnapshot(why: String) {
        if (runSnapshotsTaken >= MAX_RUN_SNAPSHOTS) return
        runSnapshotsTaken++
        saveSnapshot(why)
    }

    private fun saveSnapshot(why: String) {
        val base = recorderBase ?: return
        snapshotIndex++
        CaptureBus.requestSnapshot(File(File(filesDir, "sessions"), "$base-frame-$snapshotIndex.png"))
        recorder?.event("auto", "snapshot $base-frame-$snapshotIndex.png $why")
    }

    private fun render() {
        val b = binding ?: return
        scope.launch {
            val nowNs = System.nanoTime()
            val snapshot = withContext(engineDispatcher) {
                Snapshot(
                    state = engine.state,
                    reason = engine.lastReason,
                    warmupLeftNs = engine.warmupLeftNs(nowNs),
                    taps = engine.taps,
                    perfect = engine.perfectCount,
                    imperfect = engine.imperfectCount,
                    speed = engine.speedPxPerSec,
                    leadNs = engine.leadNs,
                    lostNotice = engine.lostNotice,
                    testPlaced = if (pacingActive) pacer.placed else -1,
                    testRemainingNs = pacer.remainingNs(nowNs),
                    testHoldLeftNs = (pacer.holdUntilNs - nowNs).coerceAtLeast(0L)
                )
            }
            val capture = CaptureBus.running.value
            b.overlayStatus.text = message ?: when {
                !capture -> getString(R.string.overlay_no_capture)
                else -> when (snapshot.state) {
                    AutoPlayer.State.IDLE -> getString(R.string.overlay_state_idle)
                    AutoPlayer.State.SCANNING -> getString(
                        if (snapshot.lostNotice) R.string.overlay_state_lost else R.string.overlay_state_scanning
                    )
                    AutoPlayer.State.READY -> getString(R.string.overlay_state_ready)
                    AutoPlayer.State.WARMUP ->
                        getString(R.string.overlay_state_warmup, snapshot.warmupLeftNs / 1e9)
                    AutoPlayer.State.RUNNING -> getString(R.string.overlay_state_running, snapshot.reason)
                }
            }
            val counters = getString(
                R.string.overlay_counters,
                snapshot.taps,
                snapshot.perfect,
                snapshot.imperfect,
                kotlin.math.abs(snapshot.speed),
                snapshot.leadNs / 1e6
            )
            val remainingS = (snapshot.testRemainingNs / 1_000_000_000L).toInt()
            b.overlayCounters.text = if (snapshot.testPlaced >= 0) {
                counters + getString(
                    R.string.overlay_test_progress,
                    snapshot.testPlaced,
                    pacer.targetBlocks,
                    remainingS / 60,
                    remainingS % 60,
                    snapshot.testHoldLeftNs / 1e9
                )
            } else {
                counters
            }
            val state = snapshot.state
            val busy = state == AutoPlayer.State.SCANNING ||
                state == AutoPlayer.State.WARMUP ||
                state == AutoPlayer.State.RUNNING
            // Skanerlash tugaydi va qizib olinmaguncha «Boshlash» o'chiq.
            b.btnScan.isEnabled = capture && !busy
            b.btnStart.isEnabled = capture && state == AutoPlayer.State.READY
            b.btnStop.isEnabled = busy
            b.btnWarmup.isEnabled = !busy
            b.btnMode.isEnabled = !busy
            b.btnMode.text = if (testMode) {
                getString(R.string.overlay_mode_test, pacer.targetBlocks, (pacer.durationNs / 60_000_000_000L).toInt())
            } else {
                getString(R.string.overlay_mode_normal)
            }
            val warmupS = WARMUP_CHOICES_S[warmupIndex]
            b.btnWarmup.text = if (warmupS > 0) {
                getString(R.string.overlay_warmup_format, warmupS)
            } else {
                getString(R.string.overlay_warmup_off)
            }
        }
    }

    private class Snapshot(
        val state: AutoPlayer.State,
        val reason: String,
        val warmupLeftNs: Long,
        val taps: Int,
        val perfect: Int,
        val imperfect: Int,
        val speed: Float,
        val leadNs: Long,
        val lostNotice: Boolean,
        /** Test rejimida qo'yilgan bloklar (-1 = test yo'q), qolgan vaqt, kutish. */
        val testPlaced: Int,
        val testRemainingNs: Long,
        val testHoldLeftNs: Long
    )

    private fun startVsyncClock() {
        val thread = HandlerThread("pt-vsync").also { it.start() }
        vsyncThread = thread
        Handler(thread.looper).post {
            val choreographer = Choreographer.getInstance()
            choreographer.postFrameCallback(object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    lastVsyncNs = frameTimeNanos
                    choreographer.postFrameCallback(this)
                }
            })
        }
    }

    private fun startRecorder(warmupS: Int) {
        closeRecorder("restart")
        runCatching {
            val dir = File(filesDir, "sessions").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            recorderBase = "auto-$stamp"
            recorder = LabSessionRecorder(
                File(dir, "auto-$stamp.csv"),
                listOf(
                    "app=PerfectTap v${BuildConfig.VERSION_NAME} mode=auto",
                    "screen_px=${screenWidth}x$screenHeight frame_ns=$framePeriodNs warmup_s=$warmupS",
                    "frame rows: note = clicker reason; content_ts_ns = image_ts_ns"
                )
            )
        }.onFailure { Log.w(TAG, "auto recorder failed", it) }
    }

    private fun closeRecorder(reason: String) {
        val active = recorder ?: return
        recorder = null
        active.event(
            "session",
            "end reason=$reason taps=${engine.taps} perfect=${engine.perfectCount} " +
                "imperfect=${engine.imperfectCount} lead_ms=${engine.leadNs / 1e6}"
        )
        active.close()
    }
}
