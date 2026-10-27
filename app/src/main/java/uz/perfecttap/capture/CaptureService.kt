package uz.perfecttap.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import uz.perfecttap.MainActivity
import uz.perfecttap.R
import uz.perfecttap.core.Displays
import uz.perfecttap.detect.CalibrationMonitor
import uz.perfecttap.detect.EdgeDetector
import uz.perfecttap.detect.VerticalEdgeDetector
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * MediaProjection stream + band-based edge detection (2-3 bosqich).
 *
 * - foreground service with the mediaProjection type (required on Android 14+)
 * - ImageReader RGBA_8888 with 2 buffers, always acquiring the newest frame
 * - a dedicated URGENT_DISPLAY capture thread, so a busy UI cannot delay frames
 * - one preallocated scanline per session; frame handling allocates nothing
 * - publishes FPS, frame time, jitter, pipeline delay and heap growth to [CaptureBus]
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_START = "uz.perfecttap.action.START_CAPTURE"
        const val ACTION_STOP = "uz.perfecttap.action.STOP_CAPTURE"
        const val ACTION_LAB_MODE = "uz.perfecttap.action.LAB_MODE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_LAB = "lab_mode"
        /** Laboratoriya ROI'ning pastki chegarasi (panel tepasi), px. */
        const val EXTRA_ROI_BOTTOM = "roi_bottom_y"

        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "PerfectTap"

        /** PDF: analyse only a horizontal band, Y_scan ± 20 px. */
        private const val BAND_HEIGHT = 41

        /**
         * Y-tracking tasmasining eni. Blok kesilganda 120 px gacha torayadi va
         * kadrda 40 px siljiydi; tor tasma (15 px) bir kadrda blokdan chiqib
         * ketib, stackka «yopishib» qolardi. 81 px uchun blok baribir kesib o'tadi.
         */
        private const val STRIP_WIDTH = 81
        /** Blok ko'rinmay qolsa, tasma shu vaqtdan keyin markazga qaytariladi. */
        private const val STRIP_RECENTER_NS = 300_000_000L
        /**
         * O'lchangan blok shuncha kadr joyida turmasa, kamera statik stackka
         * «yopishib qolgan» hisoblanadi va bir blok balandlikka suriladi.
         */
        private const val STUCK_FRAMES = 6

        fun startIntent(
            context: Context,
            resultCode: Int,
            data: Intent,
            lab: Boolean = false,
            roiBottomY: Int = 0
        ): Intent =
            Intent(context, CaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_LAB, lab)
                putExtra(EXTRA_ROI_BOTTOM, roiBottomY)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, CaptureService::class.java).setAction(ACTION_STOP)

        /** Turns per-frame lab observations on/off without restarting the stream. */
        fun labIntent(context: Context, enabled: Boolean, roiBottomY: Int = 0): Intent =
            Intent(context, CaptureService::class.java).apply {
                action = ACTION_LAB_MODE
                putExtra(EXTRA_LAB, enabled)
                putExtra(EXTRA_ROI_BOTTOM, roiBottomY)
            }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var scanline: ScanlineReader? = null
    private var detector: EdgeDetector? = null
    private var stackDetector: EdgeDetector? = null
    private var stripReader: VerticalStripReader? = null
    private var verticalDetector: VerticalEdgeDetector? = null
    private val calibration = CalibrationMonitor()

    private val timing = FrameTiming()
    private var imageWidth = 0
    private var imageHeight = 0
    private var frames = 0L
    private var bandCenterY = 0
    private var stripCenterX = 0
    private var hasBlock = false
    private var blockLeft = 0f
    private var blockRight = 0f
    private var peakCount = 0
    private var detectMs = 0.0
    private var lastPipelineDelayNs = 0L
    private var lastPublishNs = 0L
    private var lastHeapBytes = 0L
    private var lastHeapSampleNs = 0L
    private var active = false
    private var labMode = false
    /** Lab ROI pastki chegarasi (panel tepasi); 0 = cheklanmagan. */
    private var roiBottomY = 0
    /** Oxirgi marta blok ko'rilgan vaqt; tasmani qayta markazlash uchun. */
    private var lastBlockSeenNs = 0L
    /** Oxirgi o'lchangan blok markazi va u joyida turgan kadrlar soni. */
    private var lastCenterX = Float.NaN
    private var staticFrames = 0
    /** Kamera ko'rayotgan rasmning yorug'ligi (0..255). */
    private var bandLuminance = 0f
    private var stripLuminance = 0f
    private var stripMaxLuminance = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }

            ACTION_LAB_MODE -> {
                labMode = active && intent.getBooleanExtra(EXTRA_LAB, false)
                val roi = intent.getIntExtra(EXTRA_ROI_BOTTOM, 0)
                if (roi > 0 && roi != roiBottomY) {
                    roiBottomY = roi
                    if (labMode) rebuildLabReaders()
                }
                Log.i(TAG, "lab mode -> $labMode (active=$active, roiBottom=$roiBottomY)")
                if (!labMode) CaptureBus.clearLabFrames()
                if (!active) stopSelf()
            }

            ACTION_START -> {
                if (active) return START_NOT_STICKY
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = intent.resultData()
                if (data == null) {
                    fail("Ekran oqimi ruxsat ma'lumoti yo'q")
                    return START_NOT_STICKY
                }
                labMode = intent.getBooleanExtra(EXTRA_LAB, false)
                roiBottomY = intent.getIntExtra(EXTRA_ROI_BOTTOM, 0)
                // Android 14+: the FGS must be running before getMediaProjection().
                startForegroundNotification()
                startCapture(resultCode, data)
            }

            else -> if (!active) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = try {
            manager?.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            null
        }
        if (mediaProjection == null) {
            fail("Ekran oqimi ochilmadi")
            return
        }
        projection = mediaProjection

        val thread = HandlerThread("pt-capture").also { it.start() }
        captureThread = thread
        val handler = Handler(thread.looper)
        captureHandler = handler
        // Frame handling must win against the UI thread and background work:
        // a late frame is measured as extra stream delay.
        handler.post {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        }

        // Required before createVirtualDisplay on Android 14+.
        mediaProjection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    if (!active) return
                    CaptureBus.message.value = "Ekran oqimi tizim tomonidan to'xtatildi"
                    stopCapture()
                    stopSelf()
                }
            },
            Handler(Looper.getMainLooper())
        )

        val size = Displays.size(this)
        Log.i(TAG, "capture start ${size.width}x${size.height}, lab=$labMode")
        imageWidth = size.width
        imageHeight = size.height
        scanline = ScanlineReader(size.width, BAND_HEIGHT)
        detector = EdgeDetector(size.width)
        // Lab mode measures the block below the moving one as the stack.
        stackDetector = EdgeDetector(size.width)
        stripCenterX = size.width / 2
        // Lab mode follows the moving block vertically (PDF: dinamik Y-tracking).
        // Tasma ROI bilan cheklanadi: panel chegarasi juda kuchli qirra bo'lib,
        // aks holda kuzatuv o'sha joyga «yopishib» qoladi.
        rebuildLabReaders()
        calibration.reset()
        bandCenterY = size.height / 2
        timing.reset()
        frames = 0
        lastPipelineDelayNs = 0
        hasBlock = false
        lastBlockSeenNs = 0L
        lastCenterX = Float.NaN
        staticFrames = 0
        bandLuminance = 0f
        stripLuminance = 0f
        stripMaxLuminance = 0f

        // Two buffers are enough with acquireLatestImage() and keep the queue,
        // and therefore the measured delay, as short as possible.
        val reader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ r -> onFrameAvailable(r) }, handler)

        val display = mediaProjection.createVirtualDisplay(
            "PerfectTapCapture",
            size.width,
            size.height,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )
        if (display == null) {
            fail("Virtual displey yaratilmadi")
            return
        }
        virtualDisplay = display

        active = true
        lastPublishNs = System.nanoTime()
        lastHeapBytes = usedHeapBytes()
        lastHeapSampleNs = lastPublishNs

        CaptureBus.running.value = true
        CaptureBus.stats.value = CaptureStats(
            width = size.width,
            height = size.height,
            fps = 0.0,
            frameAvgMs = 0.0,
            frameMaxMs = 0.0,
            jitterMs = 0.0,
            pipelineDelayMs = 0.0,
            frames = 0,
            bandCenterY = bandCenterY,
            bandHeight = BAND_HEIGHT,
            hasBlock = false,
            blockLeft = 0f,
            blockRight = 0f,
            peakCount = 0,
            detectMs = 0.0,
            calibrationSamples = 0,
            calibrationCapacity = calibration.capacity,
            widthDriftP95 = 0f,
            heapDeltaBytesPerSec = 0
        )
        CaptureBus.message.value = "Ekran oqimi boshlandi: ${size.width}×${size.height}"
    }

    private fun onFrameAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Throwable) {
            null
        } ?: return

        try {
            // The reader can be closed while this callback is already queued; its
            // buffers then become inaccessible and must not be read.
            if (!active) return
            val plane = image.planes.firstOrNull() ?: return
            val slice = scanline ?: return
            // 1) Y tracking: locate the moving block in a vertical strip placed at
            // the block's last measured X, so the strip stays on the moving block.
            val strip = stripReader
            val vertical = verticalDetector
            var movingBottomY = -1f
            var movingHeightPx = 0f
            if (labMode && strip != null && vertical != null) {
                val stripRows = strip.read(
                    buffer = plane.buffer,
                    rowStride = plane.rowStride,
                    pixelStride = plane.pixelStride,
                    centerX = stripCenterX,
                    imageWidth = image.width,
                    imageHeight = image.height
                )
                if (stripRows == strip.rows) {
                    val found = vertical.detect(strip.pixels)
                    stripLuminance = vertical.lastMeanLum
                    stripMaxLuminance = vertical.lastMaxLum
                    if (found != null) {
                        val center = strip.startY + found.centerRow * strip.rowStep
                        bandCenterY = center.roundToInt().coerceIn(BAND_HEIGHT / 2, bandLimitY())
                        movingBottomY = strip.startY + found.bottomRow * strip.rowStep
                        movingHeightPx = found.heightPx
                    }
                }
            }

            // 2) X detection: read the band at the tracked Y.
            val rows = slice.readBand(
                buffer = plane.buffer,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
                centerY = bandCenterY,
                imageWidth = image.width,
                imageHeight = image.height
            )
            if (rows <= 0) return

            val edgeDetector = detector
            if (edgeDetector != null) {
                hasBlock = edgeDetector.detect(slice.pixels, rows)
                peakCount = edgeDetector.peakCount
                detectMs = edgeDetector.detectNanos / 1_000_000.0
                bandLuminance = edgeDetector.meanLuminance
                if (hasBlock) {
                    blockLeft = edgeDetector.bestLeft
                    blockRight = edgeDetector.bestRight
                    calibration.add(edgeDetector.bestWidth)
                    stripCenterX = blockLeft.roundToInt() +
                        ((blockRight - blockLeft) * 0.5f).roundToInt()
                    lastBlockSeenNs = System.nanoTime()
                    val centerNow = (blockLeft + blockRight) * 0.5f
                    if (abs(centerNow - lastCenterX) < 2f) {
                        staticFrames++
                    } else {
                        staticFrames = 0
                        lastCenterX = centerNow
                    }
                } else if (labMode &&
                    System.nanoTime() - lastBlockSeenNs > STRIP_RECENTER_NS
                ) {
                    // Blok tasmadan chiqib ketgan bo'lishi mumkin: tasmani maydon
                    // markaziga qaytaramiz — blok har supurishda u yerdan o'tadi.
                    stripCenterX = imageWidth / 2
                }

                // Stack statik, harakatlanuvchisi undan roppa-rosa bir blok
                // balandda. Kamera stackka «yopishib» qolsa, o'lchov joyida
                // qotib qoladi — bir blok yuqoridagi qatordan qayta qidiramiz.
                if (labMode && hasBlock && staticFrames >= STUCK_FRAMES) {
                    val blockHeight = if (movingHeightPx > 1f) {
                        movingHeightPx
                    } else {
                        BAND_HEIGHT.toFloat()
                    }
                    val shifted = (bandCenterY - blockHeight).roundToInt()
                        .coerceIn(BAND_HEIGHT / 2, bandLimitY())
                    if (shifted != bandCenterY) {
                        val rowsUp = slice.readBand(
                            buffer = plane.buffer,
                            rowStride = plane.rowStride,
                            pixelStride = plane.pixelStride,
                            centerY = shifted,
                            imageWidth = image.width,
                            imageHeight = image.height
                        )
                        if (rowsUp > 0 && edgeDetector.detect(slice.pixels, rowsUp)) {
                            hasBlock = true
                            blockLeft = edgeDetector.bestLeft
                            blockRight = edgeDetector.bestRight
                            bandCenterY = shifted
                            stripCenterX = blockLeft.roundToInt() +
                                ((blockRight - blockLeft) * 0.5f).roundToInt()
                            staticFrames = 0
                            lastCenterX = (blockLeft + blockRight) * 0.5f
                            peakCount = edgeDetector.peakCount
                            detectMs = edgeDetector.detectNanos / 1_000_000.0
                        }
                    }
                }

                // 3) Stack measurement: a second band just below the moving block
                // reads the block it will land on, so the target centre comes from
                // the screen rather than from the game's own state.
                var stackHas = false
                var stackLeft = 0f
                var stackRight = 0f
                val stack = stackDetector
                if (labMode && stack != null && movingBottomY >= 0f) {
                    val stackY = (movingBottomY + movingHeightPx * 0.5f).roundToInt()
                        .coerceIn(BAND_HEIGHT / 2, bandLimitY())
                    val stackRows = slice.readBand(
                        buffer = plane.buffer,
                        rowStride = plane.rowStride,
                        pixelStride = plane.pixelStride,
                        centerY = stackY,
                        imageWidth = image.width,
                        imageHeight = image.height
                    )
                    // The block below is as wide as the moving one (1x1 stacking),
                    // so its width disambiguates the real edges from the digits
                    // drawn inside the block.
                    stack.expectedWidthPx = if (hasBlock) blockRight - blockLeft else -1f
                    if (stackRows > 0 && stack.detect(slice.pixels, stackRows)) {
                        stackHas = true
                        stackLeft = stack.bestLeft
                        stackRight = stack.bestRight
                    }
                }

                if (labMode) {
                    CaptureBus.publishLabFrame(LabObservation(
                        imageTimestampNs = image.timestamp,
                        arrivalNs = System.nanoTime(),
                        hasBlock = hasBlock,
                        left = blockLeft,
                        right = blockRight,
                        bandCenterY = bandCenterY,
                        hasStack = stackHas,
                        stackLeft = stackLeft,
                        stackRight = stackRight,
                        detectMs = detectMs,
                        peakCount = peakCount
                    ))
                }
            }

            timing.add(image.timestamp)
            frames++
            val now = System.nanoTime()
            lastPipelineDelayNs = now - image.timestamp

            if (now - lastPublishNs >= 500_000_000L) {
                lastPublishNs = now
                publishStats()
            }
        } catch (_: IllegalStateException) {
            // Buffer closed underneath us (stop, projection revoke) — drop the frame.
        } finally {
            runCatching { image.close() }
        }
    }

    /**
     * Y-tracking tasmasini ROI bilan quradi. Lab rejimi yoqilganda ROI
     * o'zgargan bo'lsa, tasma shu chegara bilan qayta quriladi.
     */
    private fun rebuildLabReaders() {
        val height = imageHeight
        if (imageWidth <= 0 || height <= 0) return
        val stripWidth = STRIP_WIDTH
        val rowStep = 2
        val startY = (height * 0.22f).toInt()
        val endY = maxOf(startY + rowStep * 4, bandLimitY() + BAND_HEIGHT / 2)
        val strip = VerticalStripReader(stripWidth, rowStep, startY, endY)
        stripReader = strip
        verticalDetector = VerticalEdgeDetector(stripWidth, rowStep, strip.rows)
    }

    /**
     * Tahlil hududining pastki chegarasi (kadr markazi uchun): panel tepasi
     * berilgan bo'lsa shu, aks holda ekranning 95% i.
     */
    private fun bandLimitY(): Int {
        val screenLimit = (imageHeight * 0.95f).toInt()
        val limit = if (roiBottomY in 1 until imageHeight) {
            // Tasma panel chegarasiga ham tegmasin: bir tasma balandligi zaxira.
            minOf(roiBottomY - BAND_HEIGHT, screenLimit)
        } else {
            screenLimit
        }
        return (limit - BAND_HEIGHT / 2).coerceAtLeast(BAND_HEIGHT / 2)
    }

    private fun publishStats() {
        CaptureBus.stats.value = CaptureStats(
            width = imageWidth,
            height = imageHeight,
            fps = timing.fps(),
            frameAvgMs = timing.avgDeltaNs() / 1_000_000.0,
            frameMaxMs = timing.maxDeltaNs() / 1_000_000.0,
            jitterMs = timing.jitterNs() / 1_000_000.0,
            pipelineDelayMs = lastPipelineDelayNs / 1_000_000.0,
            frames = frames,
            bandCenterY = bandCenterY,
            bandHeight = BAND_HEIGHT,
            hasBlock = hasBlock,
            blockLeft = blockLeft,
            blockRight = blockRight,
            peakCount = peakCount,
            detectMs = detectMs,
            calibrationSamples = calibration.samples,
            calibrationCapacity = calibration.capacity,
            widthDriftP95 = calibration.p95WidthDrift(),
            heapDeltaBytesPerSec = heapDeltaBytesPerSec(),
            bandLuminance = bandLuminance,
            stripLuminance = stripLuminance,
            stripMaxLuminance = stripMaxLuminance
        )
    }

    private fun usedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun heapDeltaBytesPerSec(): Long {
        val now = System.nanoTime()
        val elapsed = now - lastHeapSampleNs
        if (elapsed <= 0) return 0
        val current = usedHeapBytes()
        val delta = current - lastHeapBytes
        lastHeapBytes = current
        lastHeapSampleNs = now
        return delta * 1_000_000_000L / elapsed
    }

    private fun stopCapture() {
        if (!active && imageReader == null) {
            CaptureBus.running.value = false
            CaptureBus.stats.value = null
            return
        }
        val wasActive = active
        active = false
        labMode = false
        CaptureBus.clearLabFrames()

        // ImageReader buferi o'qilayotganda yopilsa, native xotira bo'shatilib
        // proses SIGSEGV bilan o'ladi. Shuning uchun yopish kadr oqimining
        // o'zida bajariladi: u yerda onFrameAvailable ham ishlaydi va ular
        // hech qachon parallel bo'lmaydi.
        val teardown = Runnable {
            runCatching { imageReader?.setOnImageAvailableListener(null, null) }
            runCatching { virtualDisplay?.release() }
            runCatching { imageReader?.close() }
            runCatching { projection?.stop() }
            projection = null
            virtualDisplay = null
            imageReader = null
            scanline = null
            detector = null
            stackDetector = null
            stripReader = null
            verticalDetector = null
        }
        val handler = captureHandler
        val onCaptureThread = handler != null &&
            handler.looper.thread.isAlive &&
            Thread.currentThread() !== handler.looper.thread
        if (wasActive && onCaptureThread) {
            handler!!.post(teardown)
            handler.post {
                captureThread?.quitSafely()
                captureThread = null
                captureHandler = null
            }
        } else {
            teardown.run()
            captureThread?.quitSafely()
            captureThread = null
            captureHandler = null
        }

        CaptureBus.running.value = false
        CaptureBus.stats.value = null
        stopForegroundCompat()
    }

    private fun fail(message: String) {
        CaptureBus.message.value = message
        stopCapture()
        stopSelf()
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.capture_notif_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ekran oqimi xizmati ishlayotganda ko'rinadi"
            }
            manager?.createNotificationChannel(channel)
        }

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_notif_title))
            .setContentText(getString(R.string.capture_notif_text))
            .setSmallIcon(R.drawable.ic_capture)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun Intent.resultData(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(EXTRA_RESULT_DATA)
        }
}
