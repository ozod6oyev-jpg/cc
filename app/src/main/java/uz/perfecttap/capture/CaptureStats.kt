package uz.perfecttap.capture

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

/** Snapshot of the capture pipeline, published roughly twice per second. */
data class CaptureStats(
    val width: Int,
    val height: Int,
    val fps: Double,
    val frameAvgMs: Double,
    val frameMaxMs: Double,
    val jitterMs: Double,
    val pipelineDelayMs: Double,
    val frames: Long,
    val bandCenterY: Int,
    val bandHeight: Int,
    val hasBlock: Boolean,
    val blockLeft: Float,
    val blockRight: Float,
    val peakCount: Int,
    val detectMs: Double,
    val calibrationSamples: Int,
    val calibrationCapacity: Int,
    val widthDriftP95: Float,
    val heapDeltaBytesPerSec: Long,
    /**
     * Kamera ko'rayotgan rasmning yorug'ligi: tahlil tasmasi o'rtachasi va
     * Y-tracking tasmasining o'rtachasi/eng yorug' qatori (0..255).
     * 0 ga yaqin qiymat — oqimda o'yin oynasi umuman yo'q.
     */
    val bandLuminance: Float = 0f,
    val stripLuminance: Float = 0f,
    val stripMaxLuminance: Float = 0f
)

/**
 * One captured frame for the lab. Published per frame, and only while the lab
 * asks for it, so the normal capture path stays allocation-free.
 */
data class LabObservation(
    val imageTimestampNs: Long,
    val arrivalNs: Long,
    val hasBlock: Boolean,
    val left: Float,
    val right: Float,
    val bandCenterY: Int,
    /** The block below the moving one, measured in a second band (the stack). */
    val hasStack: Boolean = false,
    val stackLeft: Float = 0f,
    val stackRight: Float = 0f,
    /** Detector cost for this frame (ms) and the number of edges it saw. */
    val detectMs: Double = 0.0,
    val peakCount: Int = 0
)

/** Cross-thread bridge between CaptureService and the UI. */
object CaptureBus {
    val running = MutableStateFlow(false)
    val stats = MutableStateFlow<CaptureStats?>(null)
    val message = MutableStateFlow<String?>(null)

    /**
     * Laboratoriya kadrlari **yo'qotilmasdan** uzatiladi.
     *
     * `StateFlow` oraliq qiymatlarni yutib yuboradi (kadrlar 15–60/s keladi,
     * yig'uvchi esa bir zum band bo'lsa eski kadr yo'qoladi), shuning uchun
     * bu yerda navbat ishlatiladi: yozuvga har bir tahlil tushadi.
     */
    private val labFrames = Channel<LabObservation>(
        capacity = 8_192,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    val labObservations: Channel<LabObservation> get() = labFrames

    /** Kadr oqimidan chaqiriladi: hech qachon bloklamaydi. */
    fun publishLabFrame(observation: LabObservation) {
        labFrames.trySend(observation)
    }

    /** Keyingi kadr shu faylga PNG bo'lib saqlansin (diagnostika). */
    private val snapshotRequest = java.util.concurrent.atomic.AtomicReference<java.io.File?>(null)

    fun requestSnapshot(file: java.io.File) {
        snapshotRequest.set(file)
    }

    /** Kadr oqimidan chaqiriladi; so'rov bo'lsa uni oladi. */
    fun takeSnapshotRequest(): java.io.File? = snapshotRequest.getAndSet(null)

    /** Rejim o'chganda eski kadrlar navbatda qolib ketmasin. */
    fun clearLabFrames() {
        while (labFrames.tryReceive().isSuccess) Unit
    }
}
