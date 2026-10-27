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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        /** O'rganilgan lead / nishon tuzatishi keyingi sessiyalar uchun saqlanadi. */
        private const val PREFS = "auto_calibration"
        private const val KEY_LEAD = "lead_ns"
        private const val KEY_BIAS = "bias_ns"
        /** Diagnostika: skanerlash paytidagi kadrlar (PNG). */
        private const val MAX_SNAPSHOTS = 5
        private const val SNAPSHOT_EVERY_NS = 3_000_000_000L

        fun startIntent(context: Context): Intent = Intent(context, OverlayService::class.java)

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
    private var lastSnapshotNs = 0L
    private var screenWidth = 0
    private var screenHeight = 0
    private var message: String? = null

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
        binding?.let { b -> runCatching { windowManager?.removeView(b.root) } }
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

        b.btnClose.setOnClickListener { stopSelf() }
        b.btnScan.setOnClickListener { onScan() }
        b.btnStart.setOnClickListener { onStart() }
        b.btnStop.setOnClickListener { onStop() }
        b.btnWarmup.setOnClickListener {
            warmupIndex = (warmupIndex + 1) % WARMUP_CHOICES_S.size
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
                    true
                }
                else -> false
            }
        }
        render()
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
            val started = withContext(engineDispatcher) {
                scheduler?.rearm()
                engine.start(System.nanoTime())
            }
            if (started) recorder?.event("auto", "start")
            render()
        }
    }

    private fun onStop() {
        scope.launch {
            withContext(engineDispatcher) {
                scheduler?.cancel()
                engine.stop()
            }
            recorder?.event("auto", "stop")
            render()
        }
    }

    /** `pt-tap` oqimida, rejalashtirilgan paytda. */
    private fun fireTap(fireAtNs: Long) {
        val ch = channel ?: return
        val x = screenWidth * 0.5f
        val y = screenHeight * TAP_Y_FRACTION
        val sentNs = System.nanoTime()
        ch.tap(x, y)
        scope.launch(engineDispatcher) {
            engine.onTapFired(fireAtNs, sentNs)
            recorder?.tap(sentNs, x, y, fireAtNs, engine.taps)
            scheduler?.rearm()
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
        if (fireAt >= 0L) scheduler?.plan(fireAt)
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
                    "result tap=%d offset_px=%.1f perfect=%b lead_ms=%.2f bias_ms=%.2f trusted=%b",
                    result.tapIndex,
                    result.offsetPx,
                    result.perfect,
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
        val base = recorderBase ?: return
        if (snapshotsTaken >= MAX_SNAPSHOTS) return
        if (!force && lastSnapshotNs != 0L && nowNs - lastSnapshotNs < SNAPSHOT_EVERY_NS) return
        lastSnapshotNs = nowNs
        snapshotsTaken++
        CaptureBus.requestSnapshot(File(File(filesDir, "sessions"), "$base-frame-$snapshotsTaken.png"))
        recorder?.event("auto", "snapshot $base-frame-$snapshotsTaken.png")
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
                    lostNotice = engine.lostNotice
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
            b.overlayCounters.text = getString(
                R.string.overlay_counters,
                snapshot.taps,
                snapshot.perfect,
                snapshot.imperfect,
                kotlin.math.abs(snapshot.speed),
                snapshot.leadNs / 1e6
            )
            val state = snapshot.state
            val busy = state == AutoPlayer.State.SCANNING ||
                state == AutoPlayer.State.WARMUP ||
                state == AutoPlayer.State.RUNNING
            // Skanerlash tugaydi va qizib olinmaguncha «Boshlash» o'chiq.
            b.btnScan.isEnabled = capture && !busy
            b.btnStart.isEnabled = capture && state == AutoPlayer.State.READY
            b.btnStop.isEnabled = busy
            b.btnWarmup.isEnabled = !busy
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
        val lostNotice: Boolean
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
