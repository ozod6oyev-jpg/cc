package uz.perfecttap

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.net.Uri
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
import rikka.shizuku.Shizuku
import uz.perfecttap.capture.CaptureBus
import uz.perfecttap.capture.CaptureService
import uz.perfecttap.capture.CaptureStats
import uz.perfecttap.core.Displays
import uz.perfecttap.core.RootManager
import uz.perfecttap.databinding.ActivityMainBinding
import uz.perfecttap.inject.ShizukuInjector
import uz.perfecttap.inject.TapChannel
import uz.perfecttap.inject.TapChannelFactory
import uz.perfecttap.inject.TouchDevice
import uz.perfecttap.inject.TouchDeviceFinder
import uz.perfecttap.lab.LabActivity
import uz.perfecttap.auto.OverlayService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private companion object {
        /** Ko'rinadigan bosqich raqami; versiya `versionName` bilan birga oshiriladi. */
        const val STAGE = "6"
        const val SHIZUKU_REQUEST_CODE = 1001
    }

    private lateinit var binding: ActivityMainBinding
    private val root = RootManager()
    private val finder = TouchDeviceFinder(root)

    private var device: TouchDevice? = null
    private var channel: TapChannel? = null
    private val logLines = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            openPanelAfterConsent = false
            log("Ekran oqimi ruxsati berilmadi")
            return@registerForActivityResult
        }
        log("Ekran oqimi ruxsati olindi — xizmat ishga tushmoqda")
        val auto = openPanelAfterConsent
        openPanelAfterConsent = false
        // Avto-kliker butun ekranni kuzatadi (ROI = 0).
        val intent = CaptureService.startIntent(this, result.resultCode, data, lab = auto, roiBottomY = 0)
        ContextCompat.startForegroundService(this, intent)
        if (auto) openOverlay()
    }

    /** Ekran oqimi ruxsatidan keyin panel ochilsinmi. */
    private var openPanelAfterConsent = false

    /** Sessiya faylini foydalanuvchi tanlagan joyga saqlash (SAF). */
    private var sessionToSave: File? = null
    private val saveSessionLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> if (uri != null) exportSession(uri) }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) log("Bildirishnoma ruxsati berilmadi (xizmat baribir ishlaydi)")
        requestProjectionConsent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.titleText.text =
            getString(R.string.title_with_stage, STAGE, BuildConfig.VERSION_NAME)

        binding.btnCheck.setOnClickListener { refresh() }
        binding.btnShizuku.setOnClickListener { requestShizukuPermission() }
        binding.btnTapCenter.setOnClickListener { tapCenterWithCountdown() }
        binding.btnTapFive.setOnClickListener { tapFiveTimes() }
        binding.btnCaptureStart.setOnClickListener { onStartCaptureClicked() }
        binding.btnCaptureStop.setOnClickListener {
            startService(CaptureService.stopIntent(this))
        }
        binding.btnAutoPanel.setOnClickListener { onAutoPanelClicked() }
        binding.btnSaveLastSession.setOnClickListener { saveLastSession() }
        binding.btnOpenLab.setOnClickListener {
            startActivity(Intent(this, LabActivity::class.java))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    CaptureBus.running.collect { renderCaptureRunning(it) }
                }
                launch {
                    CaptureBus.stats.collect { renderCaptureStats(it) }
                }
                launch {
                    CaptureBus.message.collect { message ->
                        if (message != null && CaptureBus.message.compareAndSet(message, null)) {
                            log(message)
                        }
                    }
                }
            }
        }

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        refresh()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        channel?.close()
        channel = null
        super.onDestroy()
    }

    private fun refresh() = lifecycleScope.launch {
        setBusy(true)
        closeChannel()
        device = null
        binding.axisStatus.text = "ABS: —"
        binding.deviceStatus.text = getString(R.string.device_status_unknown)
        binding.rootStatus.text = getString(R.string.root_status_unknown)
        log("=== Holat tekshirilmoqda ===")

        val shizukuState = ShizukuInjector.state()
        renderShizuku(shizukuState)

        val granted = withContext(Dispatchers.IO) { root.isGranted() }
        if (!granted) {
            binding.rootStatus.text = getString(R.string.root_denied)
            log("✗ Root yo'q — Shizuku yo'li tekshiriladi")
            if (shizukuState.canInject) {
                val ch = ShizukuInjector.openTapChannel()
                if (ch != null) {
                    binding.deviceStatus.text = getString(R.string.device_shizuku)
                    binding.axisStatus.text = "ABS: kerak emas (piksel koordinatalari)"
                    channel = ch
                    log("Tayyor — usul: ${ch.description}")
                } else {
                    log("✗ Shizuku kanali ochilmadi (InputManager ko'rinmayapti)")
                }
            }
            setBusy(false)
            return@launch
        }
        binding.rootStatus.text = getString(R.string.root_granted)
        log("Root bor (libsu)")

        val discovery = withContext(Dispatchers.IO) { finder.discover() }
        discovery.log.forEach(::log)
        val dev = discovery.device
        if (dev == null) {
            binding.deviceStatus.text = getString(R.string.device_not_found)
            log("✗ Sensorli ekran topilmadi")
            setBusy(false)
            return@launch
        }

        binding.deviceStatus.text = getString(R.string.device_found, dev.node.name, dev.node.devPath)
        binding.axisStatus.text = getString(
            R.string.axis_range,
            dev.xAxis.min,
            dev.xAxis.max,
            dev.yAxis.min,
            dev.yAxis.max
        )

        val size = Displays.size(this@MainActivity)
        val opened = withContext(Dispatchers.IO) {
            TapChannelFactory(root).open(dev, size.width, size.height)
        }
        opened.log.forEach(::log)
        val ch = opened.channel
        if (ch == null) {
            log("✗ Bosish kanali ochilmadi")
            setBusy(false)
            return@launch
        }

        device = dev
        channel = ch
        log("Tayyor — usul: ${modeLabel(opened.mode)}")
        setBusy(false)
    }

    /**
     * Shizuku ruxsat natijasi Activity.onRequestPermissionsResult ga emas, shu
     * listener'ga keladi (asosiy oqimda).
     */
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != SHIZUKU_REQUEST_CODE) return@OnRequestPermissionResultListener
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            log(if (granted) "Shizuku ruxsati berildi" else "Shizuku ruxsati rad etildi")
            refresh()
        }

    private fun requestShizukuPermission() {
        val state = ShizukuInjector.state()
        renderShizuku(state)
        when {
            !state.running -> log(getString(R.string.shizuku_no_service))
            state.permissionGranted -> refresh()
            !ShizukuInjector.requestPermission(SHIZUKU_REQUEST_CODE) ->
                log(getString(R.string.shizuku_request_failed))
        }
    }

    private fun renderShizuku(state: ShizukuInjector.State) {
        binding.shizukuStatus.text = when {
            !state.running -> getString(R.string.shizuku_not_running)
            !state.permissionGranted -> getString(R.string.shizuku_denied)
            else -> getString(R.string.shizuku_granted, state.version ?: "?")
        }
        binding.btnShizuku.isEnabled = !state.canInject
    }

    private fun tapCenterWithCountdown() = lifecycleScope.launch {
        val ch = channel ?: return@launch
        setBusy(true)
        for (second in 3 downTo 1) {
            log("Bosish ${second} s dan keyin…")
            delay(1_000)
        }
        val (width, height) = displaySize()
        injectTap(ch, width / 2f, height / 2f)
        setBusy(false)
    }

    private fun tapFiveTimes() = lifecycleScope.launch {
        val ch = channel ?: return@launch
        setBusy(true)
        val (width, height) = displaySize()
        repeat(5) { index ->
            log("Bosish ${index + 1}/5")
            injectTap(ch, width / 2f, height / 2f)
            delay(1_000)
        }
        setBusy(false)
    }

    /** Bosish koordinatalari ekran pikselida; kanal o'zi kerakli ko'rinishga o'giradi. */
    private suspend fun injectTap(ch: TapChannel, pixelX: Float, pixelY: Float) {
        log("  x=${pixelX.toInt()} px, y=${pixelY.toInt()} px")
        val start = SystemClock.elapsedRealtimeNanos()
        withContext(Dispatchers.IO) { ch.tap(pixelX, pixelY) }
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        log("  yuborildi (${String.format("%.1f", elapsedMs)} ms)")
    }

    private fun onStartCaptureClicked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestProjectionConsent()
        }
    }

    /**
     * Avto-kliker: overlay ruxsati → (bildirishnoma) → ekran oqimi → panel.
     * Oqim allaqachon ishlayotgan bo'lsa, faqat butun ekran tahliliga o'tadi.
     */
    private fun onAutoPanelClicked() {
        if (!Settings.canDrawOverlays(this)) {
            log(getString(R.string.overlay_permission_needed))
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        if (CaptureBus.running.value) {
            startService(CaptureService.labIntent(this, true, 0))
            openOverlay()
            return
        }
        openPanelAfterConsent = true
        onStartCaptureClicked()
    }

    private fun openOverlay() {
        startService(OverlayService.startIntent(this))
        log("Avto-kliker paneli ochildi — o'yinga o'ting va «Skanerlash»ni bosing")
        // Bosish kanalini panel o'zi ochadi; bu yerdagi kanal unga xalaqit bermasin.
        closeChannel()
        setBusy(false)
    }

    /** Eng oxirgi sessiya (lab yoki avto) faylini saqlash. */
    private fun saveLastSession() {
        val newest = File(filesDir, "sessions").listFiles { f -> f.extension == "csv" }
            ?.maxByOrNull { it.lastModified() }
        if (newest == null) {
            log(getString(R.string.session_none))
            return
        }
        sessionToSave = newest
        saveSessionLauncher.launch(newest.name)
    }

    private fun exportSession(uri: Uri) {
        val file = sessionToSave ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val out = contentResolver.openOutputStream(uri) ?: error("fayl ochilmadi")
                    out.use { stream -> file.inputStream().use { it.copyTo(stream) } }
                }
            }
            log(
                result.fold(
                    onSuccess = { getString(R.string.session_saved, file.name) },
                    onFailure = { getString(R.string.session_save_failed, it.message ?: "?") }
                )
            )
        }
    }

    private fun requestProjectionConsent() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            log("✗ Ekran oqimi xizmati mavjud emas")
            return
        }
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun renderCaptureRunning(running: Boolean) {
        binding.btnCaptureStart.isEnabled = !running
        binding.btnCaptureStop.isEnabled = running
        binding.captureStatus.text =
            getString(if (running) R.string.capture_running else R.string.capture_stopped)
        if (!running) binding.captureStats.text = getString(R.string.capture_stats_placeholder)
    }

    private fun renderCaptureStats(stats: CaptureStats?) {
        if (stats == null) return
        val detectorLine = if (stats.hasBlock) {
            getString(
                R.string.stats_detector,
                stats.blockLeft,
                stats.blockRight - stats.blockLeft,
                (stats.blockLeft + stats.blockRight) * 0.5f
            )
        } else {
            getString(R.string.stats_detector_none, stats.peakCount)
        }
        binding.captureStats.text = listOf(
            getString(R.string.stats_stream, stats.width, stats.height, stats.fps, stats.frames),
            getString(R.string.stats_frame, stats.frameAvgMs, stats.frameMaxMs),
            getString(R.string.stats_latency, stats.jitterMs, stats.pipelineDelayMs),
            detectorLine,
            getString(R.string.stats_detect_time, stats.detectMs, stats.peakCount, stats.bandCenterY),
            getString(
                R.string.stats_calibration,
                stats.calibrationSamples,
                stats.calibrationCapacity,
                stats.widthDriftP95
            ),
            getString(R.string.stats_memory, stats.heapDeltaBytesPerSec / 1024)
        ).joinToString("\n")
    }

    private fun modeLabel(mode: String): String = when (mode) {
        "direct" -> "bevosita yozish"
        "root-shell" -> "root shell"
        "sendevent" -> "sendevent (zaxira)"
        "shizuku" -> "Shizuku (shell)"
        else -> mode
    }

    private fun displaySize(): Pair<Int, Int> {
        val size = Displays.size(this)
        return size.width to size.height
    }

    private fun closeChannel() {
        channel?.close()
        channel = null
    }

    private fun setBusy(value: Boolean) {
        binding.btnCheck.isEnabled = !value
        val ready = !value && channel != null
        binding.btnTapCenter.isEnabled = ready
        binding.btnTapFive.isEnabled = ready
    }

    private fun log(message: String) {
        val line = "${timeFormat.format(Date())}  $message"
        logLines.addLast(line)
        while (logLines.size > 300) logLines.removeFirst()
        binding.logView.text = logLines.joinToString("\n")
        binding.scroll.post { binding.scroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }
}
