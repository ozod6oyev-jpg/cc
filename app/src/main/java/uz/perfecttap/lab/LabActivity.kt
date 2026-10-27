package uz.perfecttap.lab

import android.Manifest
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uz.perfecttap.BuildConfig
import uz.perfecttap.R
import uz.perfecttap.capture.CaptureBus
import uz.perfecttap.capture.CaptureService
import uz.perfecttap.capture.CaptureStats
import uz.perfecttap.capture.LabObservation
import uz.perfecttap.core.Displays
import uz.perfecttap.core.RootManager
import uz.perfecttap.databinding.ActivityLabBinding
import uz.perfecttap.inject.TapChannel
import uz.perfecttap.inject.TapChannels
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 4-bosqich: Timing & Latency Lab.
 *
 * Runs a synthetic square that moves a fixed number of pixels per display
 * frame. The capture service detects it, the square's recent positions are
 * matched back to their vsync timestamps, and
 *   Δ_stream = image.timestamp − vsync of the matched draw.
 * Δ_input is measured by injecting taps and timing their delivery to this
 * activity (root required). Every matched frame is written to CSV.
 */
class LabActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLabBinding
    private lateinit var correlator: FrameCorrelator

    private val root = RootManager()
    private val streamStats = LatencyStats()
    private val inputStats = LatencyStats()

    private var recorder: LabSessionRecorder? = null
    private var lastSessionFile: File? = null
    private var lastSessionRows = 0L
    private var missedFrames = 0L
    private var lastRenderNs = 0L
    private var sessionActive = false
    private var captureStartedHere = false
    private var sawCaptureRunning = false
    private var inputBusy = false
    /** Bosish yuborilgan payt (`System.nanoTime()`) — Δ_input shu paytdan hisoblanadi. */
    private var pendingTapNs = 0L
    /** Δ_input o'lchovi ketayotganda avto-qo'yish to'xtatiladi. */
    private var measureActive = false
    /** Isinish: birinchi kuzatuvdan boshlab 3 s tap yuborilmaydi. */
    private var warmupUntilNs = 0L
    private var warmupArmed = false
    /** Tahlil ROI'ning pastki chegarasi (panel tepasi), px. */
    private var roiBottomY = 0
    /** Δ_input o‘lchanmaganda ko‘rsatiladigan sabab. */
    private var inputMessage = R.string.lab_input_empty
    private var lastStreamDeltaNs = 0L
    /** Bir kadr davomiyligi (ms) — jitter mezoni shu chegaraga nisbatan. */
    private var oneFrameMs = 16.7

    /**
     * Clicker o'yindan butunlay ajratilgan: u faqat kamerani ko'radi va
     * ekran markaziga bosish yuboradi. O'yin esa o'sha bosishni o'zi qabul qiladi.
     */
    private val clicker: Clicker by lazy {
        Clicker(
            tapPoint = {
                val view = binding.labView
                if (view.width <= 0 || view.height <= 0) null
                else floatArrayOf(view.width * 0.5f, view.height * 0.5f)
            },
            screenWidthPx = { binding.labView.width.toFloat() },
            deliverTap = { x, y, tapTimeNs ->
                recorder?.tap(System.nanoTime(), x, y, tapTimeNs, clicker.taps)
                binding.labView.deliverTap(x, y, tapTimeNs)
            }
        )
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            binding.statusText.setText(R.string.lab_status_no_consent)
            endSessionUi("consent denied")
            return@registerForActivityResult
        }
        val intent = CaptureService.startIntent(
            this,
            result.resultCode,
            data,
            lab = true,
            roiBottomY = roiBottomY
        )
        ContextCompat.startForegroundService(this, intent)
        captureStartedHere = true
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { requestConsent() }

    /**
     * Sessiya faylini saqlash: foydalanuvchi nom va joyni o'zi tanlaydi.
     * SAF orqali, shuning uchun xotiraga ruxsat so'rash shart emas.
     */
    private val saveLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> if (uri != null) exportSession(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLabBinding.inflate(layoutInflater)
        setContentView(binding.root)
        correlator = FrameCorrelator(binding.labView.ring)

        binding.labView.onTouchSample = { eventTimeNs ->
            val write = pendingTapNs
            if (write > 0L && eventTimeNs > write) {
                val deltaNs = eventTimeNs - write
                inputStats.add(deltaNs)
                recorder?.inputSample(System.nanoTime(), deltaNs)
                pendingTapNs = 0L
                renderInput()
            }
        }

        binding.labView.onPlacement = { placement ->
            recorder?.placement(
                tNs = System.nanoTime(),
                offsetPx = placement.offsetPx,
                perfect = placement.perfect,
                cameraShifted = placement.cameraShifted,
                widthPx = placement.widthPx,
                tapIndex = clicker.taps,
                missed = placement.missed
            )
            renderGame()
        }
        binding.speedSeek.max = SPEED_STEPS
        binding.accelSeek.max = ACCEL_STEPS
        binding.speedSeek.progress = speedProgress(BASE_SPEED_PX_PER_SEC)
        binding.accelSeek.progress = accelProgress(BASE_GROWTH)
        applyMotionSettings()
        binding.speedSeek.setOnSeekBarChangeListener(SliderListener { applyMotionSettings() })
        binding.accelSeek.setOnSeekBarChangeListener(SliderListener { applyMotionSettings() })
        binding.btnLabStart.setOnClickListener { startSession() }
        binding.btnLabStop.setOnClickListener { stopSession(stopCapture = captureStartedHere) }
        binding.btnMeasureInput.setOnClickListener { measureInputLatency() }
        binding.btnSaveSession.setOnClickListener { askSessionLocation() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    CaptureBus.running.collect { running ->
                        if (running) {
                            sawCaptureRunning = true
                        } else if (sawCaptureRunning && sessionActive) {
                            sawCaptureRunning = false
                            endSessionUi("capture stopped")
                        }
                    }
                }
                // Kanaldan o'qish: oraliq kadrlar yo'qolmaydi (StateFlow yutib yuborardi).
                launch { for (observation in CaptureBus.labObservations) handleObservation(observation) }
                launch { CaptureBus.stats.collect { renderTrack(it) } }
            }
        }
    }

    @Volatile
    private var destroyed = false

    override fun onDestroy() {
        destroyed = true
        binding.labView.stop()
        if (sessionActive) {
            closeRecorder("activity destroyed")
            if (captureStartedHere) {
                startService(CaptureService.stopIntent(this))
            } else if (CaptureBus.running.value) {
                startService(CaptureService.labIntent(this, false))
            }
        }
        super.onDestroy()
    }

    private fun startSession() {
        if (sessionActive) return
        sessionActive = true
        sawCaptureRunning = CaptureBus.running.value
        clicker.reset()
        // ROI panel tepasigacha; o'yin maydoni ham shu chegaradan yuqorida boshlanadi
        // (aks holda birinchi blok panel ortida qolib, kamera uni ko'rmaydi).
        // ROI ekran oqimida — ekran koordinatasida; o'yin maydoni esa view
        // koordinatasida. Status bar ularni ~100 px ga siljitadi: aralashtirilsa,
        // Y-tasma harakatlanayotgan blokning pastki qirrasiga yetmay qoladi.
        roiBottomY = panelTopOnScreenPx()
        binding.labView.playfieldBottomPx = panelTopInViewPx()
        binding.labView.start()
        streamStats.reset()
        inputStats.reset()
        correlator.reset()
        lastStreamDeltaNs = 0L
        missedFrames = 0
        lastRenderNs = 0L
        // Isinish birinchi kuzatuvdan boshlanadi.
        warmupArmed = false
        warmupUntilNs = 0L
        renderStream()
        renderInput()
        renderGame()
        renderTrack(null)
        binding.btnLabStart.isEnabled = false
        binding.btnLabStop.isEnabled = true
        binding.statusText.setText(R.string.lab_status_warmup)
        startRecorder()

        if (sawCaptureRunning) {
            captureStartedHere = false
            startService(CaptureService.labIntent(this, true, roiBottomY))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestConsent()
        }
    }

    private fun requestConsent() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            binding.statusText.setText(R.string.lab_status_no_consent)
            endSessionUi("no projection manager")
            return
        }
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun stopSession(stopCapture: Boolean) {
        val startedHere = captureStartedHere
        val hadSession = sessionActive
        endSessionUi("user")
        if (CaptureBus.running.value) {
            startService(CaptureService.labIntent(this, false))
        }
        if (stopCapture || startedHere) {
            startService(CaptureService.stopIntent(this))
        }
        // To'xtatish bosilganda: faylga nom berish va saqlash joyini tanlash.
        if (hadSession) askSessionLocation()
    }

    private fun endSessionUi(reason: String) {
        Log.i("PerfectTap", "lab session end: $reason (samples=${streamStats.samples})")
        sessionActive = false
        captureStartedHere = false
        binding.labView.stop()
        closeRecorder(reason)
        binding.btnLabStart.isEnabled = true
        binding.btnLabStop.isEnabled = false
        binding.statusText.setText(R.string.lab_status_idle)
    }

    private fun handleObservation(observation: LabObservation?) {
        if (observation == null) return
        // Isinish birinchi kadr tahlilidan boshlanadi: konsent oynasi ochiq
        // turganda o'tib ketmasligi kerak.
        val nowNs = System.nanoTime()
        if (!warmupArmed) {
            warmupArmed = true
            warmupUntilNs = nowNs + WARMUP_NS
            binding.statusText.setText(R.string.lab_status_warmup)
            recorder?.event(
                "session",
                "warmup_from_first_frame ${WARMUP_NS / 1_000_000} ms — auto taps disabled"
            )
        } else if (warmupUntilNs != 0L && nowNs >= warmupUntilNs) {
            warmupUntilNs = 0L
            if (sessionActive && !inputBusy) {
                binding.statusText.setText(R.string.lab_status_running)
            }
        }
        val center = (observation.left + observation.right) * 0.5f
        val delta = if (observation.hasBlock) {
            correlator.streamDeltaNs(center, observation.imageTimestampNs)
        } else {
            -1L
        }
        val matched = delta >= 0L
        // A frame can be matched one draw off, which always reports a *longer*
        // delay than the pipeline has. Such a reading is clamped to the recent
        // median instead of piloting the next placement on its own.
        if (matched) {
            lastStreamDeltaNs = if (delta - correlator.stableDeltaNs > UNCERTAIN_DELAY_NS) {
                correlator.stableDeltaNs
            } else {
                delta
            }
        }
        // The drawn square was matched, so we know when the frame's content existed;
        // otherwise the last measured delay is the best estimate we have.
        val contentTimestampNs = observation.imageTimestampNs - lastStreamDeltaNs
        // Har bir tahlil yoziladi: blok topilmagan kadr ham, mos kelmagan kadr ham.
        recorder?.frame(
            tNs = System.nanoTime(),
            imageTimestampNs = observation.imageTimestampNs,
            arrivalNs = observation.arrivalNs,
            vsyncNs = if (matched) correlator.lastVsyncNs else 0L,
            matched = matched,
            hasBlock = observation.hasBlock,
            // Blok topilmagan kadrda eski o'lchamlar yozilmaydi (bo'sh qoladi).
            leftPx = if (observation.hasBlock) observation.left else Float.NaN,
            rightPx = if (observation.hasBlock) observation.right else Float.NaN,
            bandY = observation.bandCenterY,
            stackLeftPx = if (observation.hasStack) observation.stackLeft else Float.NaN,
            stackRightPx = if (observation.hasStack) observation.stackRight else Float.NaN,
            detectMs = observation.detectMs,
            peaks = observation.peakCount,
            contentTsNs = contentTimestampNs,
            tapCount = clicker.taps,
            // Nega kadr hisobga olinmadi — sabab CSV'da qoladi.
            note = when {
                !observation.hasBlock -> "no_block"
                !matched -> correlator.lastMiss
                else -> null
            }
        )
        if (!observation.hasBlock) {
            if (sessionActive) {
                missedFrames++
                maybeRender()
            }
            return
        }
        // Clicker faqat kamera o'lchovini oladi; o'yin holati unga berilmaydi.
        // Isinish (3 s) va Δ_input o'lchovi paytida avto-qo'yish ishlamaydi:
        // o'lchov tap'lari o'yin hisobiga tushmasligi kerak.
        if (!measureActive && warmupUntilNs == 0L) {
            clicker.onObservation(
                centerX = center,
                widthPx = observation.right - observation.left,
                stackLeft = observation.stackLeft,
                stackRight = observation.stackRight,
                contentTimestampNs = contentTimestampNs
            )
        }
        if (!sessionActive) return
        if (!matched) {
            missedFrames++
            maybeRender()
            return
        }
        streamStats.add(delta)
        maybeRender()
    }

    private fun maybeRender() {
        val now = System.nanoTime()
        if (now - lastRenderNs >= 250_000_000L) {
            lastRenderNs = now
            renderStream()
            // Tap hisoblagichi va sabab faqat live yangilanadi: qo'yish
            // bo'lmasa ham, "nega tap yo'q" ekranda ko'rinib turadi.
            renderGame()
        }
    }

    private fun renderStream() {
        binding.streamText.text = if (streamStats.samples == 0) {
            getString(R.string.lab_stream_empty)
        } else {
            getString(
                R.string.lab_stream_format,
                streamStats.samples,
                ms(streamStats.meanNs()),
                ms(streamStats.p95Ns()),
                ms(streamStats.jitterNs())
            )
        }
        binding.criterionText.text = when {
            streamStats.samples < 2 -> getString(R.string.lab_criterion_waiting, oneFrameMs)
            streamStats.meetsJitterCriterion(oneFrameMs) ->
                getString(R.string.lab_criterion_pass, oneFrameMs)
            else -> getString(R.string.lab_criterion_fail, oneFrameMs)
        }
        binding.missedText.text = getString(R.string.lab_missed_format, missedFrames)
        if (sessionActive) {
            val rowsNow = recorder?.rowsWritten ?: 0L
            val name = lastSessionFile?.name ?: ""
            binding.csvText.text = getString(R.string.lab_session_recording, rowsNow, name)
        }
    }

    private fun renderInput() {
        binding.inputText.text = if (inputStats.samples == 0) {
            getString(inputMessage)
        } else {
            getString(
                R.string.lab_input_format,
                inputStats.samples,
                INPUT_TAPS,
                micros(inputStats.meanNs()),
                micros(inputStats.p95Ns()),
                micros(inputStats.jitterNs())
            )
        }
    }

    private fun renderGame() {
        val sim = binding.labView.simulation
        binding.gameText.text = if (sim == null) {
            getString(R.string.lab_game_empty)
        } else {
            getString(
                R.string.lab_game_line,
                sim.placed,
                sim.perfectCount,
                sim.placed - sim.perfectCount,
                sim.cameraShifts,
                clicker.taps,
                sim.speedPxPerSec,
                clicker.lastReason
            )
        }
    }

    /** Slider qiymatlarini o'yin tezligiga qo'llaydi. */
    private fun applyMotionSettings() {
        val base = MIN_SPEED_PX_PER_SEC + binding.speedSeek.progress * SPEED_STEP_PX_PER_SEC
        val growth = 1f + binding.accelSeek.progress * ACCEL_STEP
        binding.speedLabel.text = getString(R.string.lab_speed_format, base)
        binding.accelLabel.text = getString(
            R.string.lab_accel_format,
            (growth - 1f) * 100f
        )
        binding.labView.motionBaseSpeedPxPerSec = base
        binding.labView.motionGrowthPerBlock = growth
        binding.labView.setMotion(base, growth)
        if (sessionActive) {
            recorder?.event(
                "setting",
                String.format(
                    Locale.US,
                    "speed_px_s=%.0f growth=%.2f",
                    base,
                    growth
                )
            )
        }
    }

    private fun speedProgress(base: Float): Int =
        kotlin.math.round((base - MIN_SPEED_PX_PER_SEC) / SPEED_STEP_PX_PER_SEC).toInt()
            .coerceIn(0, SPEED_STEPS)

    private fun accelProgress(growth: Float): Int =
        kotlin.math.round((growth - 1f) / ACCEL_STEP).toInt().coerceIn(0, ACCEL_STEPS)

    /** SeekBar listener: faqat qo'yilganda emas, sudralganda ham qo'llanadi. */
    private inner class SliderListener(
        private val onChanged: () -> Unit
    ) : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
            onChanged()
        }

        override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit

        override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
    }

    /**
     * Bir ekran kadri (ms) — jitter mezonining haqiqiy birligi.
     *
     * Bu **displey yangilanish davri** (masalan 116 Hz → 8,6 ms), oqim kadrlari
     * orasi emas: oqim sekinroq bo'lishi mumkin (MediaProjection 25 fps), lekin
     * kontent baribir har bir displey kadrida yangilanadi.
     */
    private fun refreshHz(): Float = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.refreshRate ?: 60f
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.refreshRate
        }
    }.getOrDefault(60f)

    private fun displayFrameMs(): Double {
        val hz = refreshHz()
        if (hz < 1f) return 16.7
        return (1000.0 / hz).coerceIn(MIN_FRAME_MS, MAX_FRAME_MS)
    }

    private fun renderTrack(stats: CaptureStats?) {
        oneFrameMs = displayFrameMs()
        binding.trackText.text = if (stats == null) {
            getString(R.string.lab_track_empty)
        } else {
            getString(
                R.string.lab_track_line,
                stats.bandCenterY,
                stats.bandHeight,
                stats.bandLuminance,
                stats.stripMaxLuminance
            )
        }
        if (stats != null) {
            recorder?.stats(stats)
            // Clicker holati ham CSV'ga tushadi: tap bo'lmasa sababi shu qatorda.
            val stack = clicker.stackCenterX
            recorder?.event(
                "clicker",
                "taps=${clicker.taps} reason=${clicker.lastReason} " +
                    "stack=${if (stack.isNaN()) "NaN" else "%.1f".format(Locale.US, stack)}"
            )
        }
    }

    /** Δ_input uchun bosish kanali: avval root (evdev), keyin Shizuku. */
    private suspend fun openTapChannel(): TapChannel? {
        val opened = withContext(Dispatchers.IO) { TapChannels.open(this@LabActivity, root) }
        when (opened.failure) {
            TapChannels.Failure.SHIZUKU_DENIED -> inputMessage = R.string.shizuku_denied
            TapChannels.Failure.SHIZUKU_NOT_RUNNING -> inputMessage = R.string.shizuku_not_running
            TapChannels.Failure.NONE -> Unit
        }
        return opened.channel
    }

    private fun ms(ns: Double): Double = ns / 1_000_000.0

    /** Δ_input mikrosekundlarda ko'rsatiladi (ms juda qo'pol). */
    private fun micros(ns: Double): Long = (ns / 1_000.0).toLong()

    /**
     * Tahlil ROI'ning pastki chegarasi — panel tepasi. Panel chegarasi kuchli
     * qirra bo'lgani uchun Y-tracking o'sha yerga «yopishib» qolmasligi kerak.
     */
    private fun panelTopInViewPx(): Int {
        val top = binding.panel.top
        return if (top > 0) top else (binding.labView.height * 0.85f).toInt()
    }

    /** Panel tepasi ekran koordinatasida (kadr shu koordinatada o'qiladi). */
    private fun panelTopOnScreenPx(): Int {
        val panel = IntArray(2)
        val view = IntArray(2)
        binding.panel.getLocationOnScreen(panel)
        binding.labView.getLocationOnScreen(view)
        return if (binding.panel.top > 0) panel[1] else view[1] + panelTopInViewPx()
    }

    private companion object {
        /** A frame matched this much later than usual cannot time a placement. */
        const val UNCERTAIN_DELAY_NS = 60_000_000L
        const val MIN_FRAME_MS = 4.0
        const val MAX_FRAME_MS = 34.0

        const val MIN_SPEED_PX_PER_SEC = 150f
        const val MAX_SPEED_PX_PER_SEC = 2500f
        const val SPEED_STEPS = 47
        const val SPEED_STEP_PX_PER_SEC = 50f
        const val BASE_SPEED_PX_PER_SEC = 600f

        const val ACCEL_STEPS = 20
        const val ACCEL_STEP = 0.01f
        const val BASE_GROWTH = 1.05f

        /** Sessiya fayllari (ichki xotira; ruxsat kerak emas). */
        const val SESSION_DIR = "sessions"
        /** Δ_input o'lchovida yuboriladigan bosishlar soni (100+ namuna). */
        const val INPUT_TAPS = 120
        /** Bosishlar orasidagi oraliq (ms). */
        const val INPUT_TAP_INTERVAL_MS = 120
        /** O'lchov tap'lari yuboriladigan balandlik (ko'rinish balandligiga nisbatan). */
        const val MEASURE_POINT_Y = 0.16f
        /** Sessiya boshidagi isinish: shu vaqtgacha tap yuborilmaydi. */
        const val WARMUP_NS = 3_000_000_000L
    }

    private fun measureInputLatency() = lifecycleScope.launch {
        if (inputBusy) return@launch
        inputBusy = true
        binding.statusText.setText(R.string.lab_input_running)
        // O'lchov paytida avto-qo'yish to'xtatiladi: tap'lar o'yin hisobiga tushmasin.
        measureActive = true
        clicker.stop()
        var channel: TapChannel? = null
        try {
            inputMessage = R.string.lab_input_empty
            // Root bo'lsa evdev yo'li, bo'lmasa Shizuku (shell) yo'li.
            val opened = openTapChannel()
            channel = opened
            if (opened == null) {
                inputMessage = R.string.lab_input_no_service
                recorder?.event("input", "measure skipped: no tap channel (no root, no Shizuku)")
                return@launch
            }

            // O'lchov maydoni: ko'rinishning markazidan ancha uzoqda, o'yin tap
            // radiusidan tashqarida — bu bosish blok qo'ymaydi va minorani kesmaydi.
            val location = IntArray(2)
            binding.labView.getLocationOnScreen(location)
            val x = location[0] + binding.labView.width / 2f
            val y = location[1] + binding.labView.height * MEASURE_POINT_Y

            inputStats.reset()
            pendingTapNs = 0L
            recorder?.event(
                "input",
                String.format(
                    Locale.US,
                    "measure start taps=%d interval_ms=%d x=%.1f y=%.1f channel=%s",
                    INPUT_TAPS,
                    INPUT_TAP_INTERVAL_MS,
                    x,
                    y,
                    opened.javaClass.simpleName
                )
            )
            repeat(INPUT_TAPS) {
                // Vaqt `System.nanoTime()` bilan olinadi — ms yaxlitlashi yo'q.
                pendingTapNs = System.nanoTime()
                withContext(Dispatchers.IO) { opened.tap(x, y) }
                delay(INPUT_TAP_INTERVAL_MS.toLong())
                pendingTapNs = 0L
            }
            recorder?.summary(
                "input",
                inputStats.samples,
                inputStats.meanNs(),
                inputStats.p95Ns(),
                inputStats.jitterNs(),
                "delta_input measurement"
            )
        } finally {
            // Xato yoki bekor qilishda ham root shell / fd yopilsin.
            channel?.let { runCatching { it.close() } }
            inputBusy = false
            measureActive = false
            pendingTapNs = 0L
            renderInput()
            binding.statusText.setText(
                if (sessionActive) R.string.lab_status_running else R.string.lab_status_idle
            )
        }
    }

    /**
     * Sessiya yozuvini boshlaydi. Fayl dastlab ilovaning ichki xotirasida
     * (`files/sessions`) ochiladi — bu ruxsat talab qilmaydi va jarayon
     * o'chirilsa ham yozilgan qatorlar saqlanib qoladi. To'xtatilganda uni
     * [askSessionLocation] orqali istalgan joyga nusxalash mumkin.
     */
    private fun startRecorder() {
        closeRecorder("restart")
        runCatching {
            val dir = File(filesDir, SESSION_DIR).apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "lab-$stamp.csv")
            val size = Displays.size(this)
            val hz = refreshHz()
            val oneFrame = displayFrameMs()
            val header = listOf(
                "app=PerfectTap v${BuildConfig.VERSION_NAME}",
                "started_at=" + SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss.SSS Z",
                    Locale.US
                ).format(Date()),
                "screen_px=${size.width}x${size.height} refresh_hz=$hz one_frame_ms=$oneFrame",
                "roi_bottom_px=$roiBottomY (panel tepasi — tahlil shu yergacha)",
                "speed_px_s=${binding.labView.motionBaseSpeedPxPerSec} " +
                    "growth=${binding.labView.motionGrowthPerBlock}",
                "clocks: t_ns/arrival_ns/content_ts_ns = System.nanoTime(); " +
                    "image_ts_ns/vsync_ns = frame clock (same base)",
                "rows: session/frame/tap/place/input/stats/clicker/setting; " +
                    "empty cells mean the column does not apply to that row"
            )
            recorder = LabSessionRecorder(file, header)
            lastSessionFile = file
            lastSessionRows = 0L
            binding.btnSaveSession.isEnabled = false
            binding.csvText.text = getString(R.string.lab_session_recording, 0L, file.name)
            recorder?.event(
                "session",
                String.format(
                    Locale.US,
                    "start speed_px_s=%.0f growth=%.2f one_frame_ms=%.2f refresh_hz=%.1f " +
                        "screen=%dx%d",
                    binding.labView.motionBaseSpeedPxPerSec,
                    binding.labView.motionGrowthPerBlock,
                    oneFrame,
                    hz,
                    size.width,
                    size.height
                )
            )
        }.onFailure {
            recorder = null
            binding.csvText.setText(R.string.lab_csv_empty)
        }
    }

    /** Yozuvni yakunlaydi: xulosa qatorlarini qo'shadi, faylni yopadi. */
    private fun closeRecorder(reason: String) {
        val active = recorder ?: return
        recorder = null
        active.summary(
            "stream",
            streamStats.samples,
            streamStats.meanNs(),
            streamStats.p95Ns(),
            streamStats.jitterNs(),
            "delta_stream summary"
        )
        active.summary(
            "input",
            inputStats.samples,
            inputStats.meanNs(),
            inputStats.p95Ns(),
            inputStats.jitterNs(),
            "delta_input summary"
        )
        val sim = binding.labView.simulation
        active.event(
            "session",
            "end reason=$reason blocks=${sim?.placed ?: 0} " +
                "perfect=${sim?.perfectCount ?: 0} miss=${sim?.misses ?: 0} " +
                "camera=${sim?.cameraShifts ?: 0} " +
                "taps=${clicker.taps} missed=$missedFrames rows=${active.rowsWritten}"
        )
        active.close()
        lastSessionFile = active.file
        lastSessionRows = active.rowsWritten
        binding.btnSaveSession.isEnabled = true
        binding.csvText.text = getString(R.string.lab_session_finished, active.rowsWritten)
    }

    /** "Nom va joy tanlash" oynasini ochadi (SAF): ruxsat so'ralmaydi. */
    private fun askSessionLocation() {
        val file = lastSessionFile
        if (file == null || !file.exists()) {
            binding.csvText.setText(R.string.lab_session_empty)
            return
        }
        if (destroyed) return
        runCatching { saveLauncher.launch(file.name) }
            .onFailure {
                binding.csvText.text = getString(
                    R.string.lab_session_save_failed,
                    it.message ?: it.javaClass.simpleName
                )
            }
    }

    /** Yozilgan sessiya faylini foydalanuvchi tanlagan joyga ko'chiradi. */
    private fun exportSession(uri: Uri) {
        val file = lastSessionFile ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val out = contentResolver.openOutputStream(uri)
                        ?: error("fayl ochilmadi")
                    out.use { stream -> file.inputStream().use { input -> input.copyTo(stream) } }
                }
            }
            binding.csvText.text = result.fold(
                onSuccess = {
                    getString(R.string.lab_session_saved, uri.toString(), lastSessionRows)
                },
                onFailure = {
                    getString(R.string.lab_session_save_failed, it.message ?: "?")
                }
            )
        }
    }

}
