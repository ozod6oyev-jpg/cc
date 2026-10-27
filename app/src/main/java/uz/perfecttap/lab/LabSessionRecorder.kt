package uz.perfecttap.lab

import uz.perfecttap.capture.CaptureStats
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileWriter
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Butun laboratoriya sessiyasini yozib boradi — boshlangandan to'xtagunga qadar
 * **hamma** tahlil, bittasi ham tashlab ketilmaydi:
 *
 *  · `frame`   — har bir olingan kadr tahlili: blok topilgan/topilmagan, o'lchangan
 *                qirralar, Y tasma, stack qirralari, detektor vaqti, Δ_stream,
 *                kadr qachon chizilganiga mosligi (matched);
 *  · `tap`     — har bir yuborilgan bosish (vaqt, koordinata, qaysi kontent vaqti uchun);
 *  · `place`   — har bir qo'yish natijasi (siljish, perfect, kamera sakrashi, eni);
 *  · `input`   — har bir Δ_input namunasi (**mikrosekundda**) va µs agregatlar;
 *  · `stats`   — oqim statistikasi (fps, kadr vaqti, jitter, kechikish, detektor);
 *  · `setting` — slider/sozlama o'zgarishlari;
 *  · `session` — sessiya boshlanishi va yakuniy xulosa.
 *
 * Format — bitta CSV; birinchi ustun (`row`) yozuv turini bildiradi, shu turga
 * tegishli bo'lmagan ustunlar bo'sh qoladi. Fayl sarlavhasi ustunlarni va sessiya
 * metama'lumotini (`#` bilan) o'z ichiga oladi.
 *
 * Yozish alohida `pt-csv` oqimida: [frame] faqat navbatga qo'yadi, shuning
 * uchun formatlash ham, disk I/O ham o'lchov oqimini to'xtatmaydi.
 */
class LabSessionRecorder(
    val file: File,
    header: List<String> = emptyList()
) : Closeable {

    private class Record(
        val row: String,
        val tNs: Long = 0L,
        val imageTsNs: Long = 0L,
        val arrivalNs: Long = 0L,
        val vsyncNs: Long = 0L,
        val matched: Int = NOT_SET,
        val hasBlock: Int = NOT_SET,
        val leftPx: Float = Float.NaN,
        val rightPx: Float = Float.NaN,
        val bandY: Int = NOT_SET,
        val stackLeftPx: Float = Float.NaN,
        val stackRightPx: Float = Float.NaN,
        val detectMs: Double = Double.NaN,
        val peaks: Int = NOT_SET,
        val contentTsNs: Long = 0L,
        val tap: Int = NOT_SET,
        val xPx: Float = Float.NaN,
        val yPx: Float = Float.NaN,
        val offsetPx: Float = Float.NaN,
        val perfect: Int = NOT_SET,
        val cameraShift: Int = NOT_SET,
        val deltaInputNs: Long = 0L,
        val meanUs: Double = Double.NaN,
        val p95Us: Double = Double.NaN,
        val jitterUs: Double = Double.NaN,
        val samples: Int = NOT_SET,
        val meanNs: Double = Double.NaN,
        val p95Ns: Double = Double.NaN,
        val jitterNs: Double = Double.NaN,
        val note: String? = null
    )

    private object FlushMarker

    private val queue = ArrayBlockingQueue<Any>(QUEUE_CAPACITY)
    private val writer: BufferedWriter = BufferedWriter(FileWriter(file, false), 64 * 1024)
    private val rows = AtomicLong()
    private var pending = 0

    @Volatile
    private var running = true

    init {
        writer.write("# PerfectTap lab session\n")
        writer.write("# $COLUMNS\n")
        header.forEach { writer.write("# $it\n") }
        writer.write(COLUMNS)
        writer.write("\n")
        writer.flush()
    }

    private val thread = Thread({ drain() }, "pt-csv").apply {
        isDaemon = true
        start()
    }

    /** Yozilgan qatorlar soni (statistika uchun). */
    val rowsWritten: Long get() = rows.get()

    /** Bitta kadr tahlili: mos kelmagan kadr ham yoziladi ([matched] = 0). */
    fun frame(
        tNs: Long,
        imageTimestampNs: Long,
        arrivalNs: Long,
        vsyncNs: Long,
        matched: Boolean,
        hasBlock: Boolean,
        leftPx: Float,
        rightPx: Float,
        bandY: Int,
        stackLeftPx: Float,
        stackRightPx: Float,
        detectMs: Double,
        peaks: Int,
        contentTsNs: Long,
        tapCount: Int,
        note: String? = null
    ) {
        enqueue(
            Record(
                row = "frame",
                tNs = tNs,
                imageTsNs = imageTimestampNs,
                arrivalNs = arrivalNs,
                vsyncNs = vsyncNs,
                matched = if (matched) 1 else 0,
                hasBlock = if (hasBlock) 1 else 0,
                leftPx = leftPx,
                rightPx = rightPx,
                bandY = bandY,
                stackLeftPx = stackLeftPx,
                stackRightPx = stackRightPx,
                detectMs = detectMs,
                peaks = peaks,
                contentTsNs = contentTsNs,
                tap = tapCount,
                note = note
            )
        )
    }

    /** Yuborilgan bosish. [contentTsNs] — qaysi kontent vaqtiga mo'ljallangani. */
    fun tap(tNs: Long, xPx: Float, yPx: Float, contentTsNs: Long, tapIndex: Int) {
        enqueue(
            Record(
                row = "tap",
                tNs = tNs,
                xPx = xPx,
                yPx = yPx,
                contentTsNs = contentTsNs,
                tap = tapIndex
            )
        )
    }

    /** Qo'yish natijasi (o'yin tomonidan). */
    fun placement(
        tNs: Long,
        offsetPx: Float,
        perfect: Boolean,
        cameraShifted: Boolean,
        widthPx: Float,
        tapIndex: Int
    ) {
        enqueue(
            Record(
                row = "place",
                tNs = tNs,
                offsetPx = offsetPx,
                perfect = if (perfect) 1 else 0,
                cameraShift = if (cameraShifted) 1 else 0,
                tap = tapIndex,
                note = "width_px=" + String.format(Locale.US, "%.2f", widthPx)
            )
        )
    }

    /** Bitta Δ_input namunasi — faylga **mikrosekundda** yoziladi. */
    fun inputSample(tNs: Long, deltaNs: Long) {
        enqueue(Record(row = "input", tNs = tNs, deltaInputNs = deltaNs))
    }

    /**
     * Δ_stream / Δ_input agregate (sessiya oxiri yoki o'lchov oxiri).
     * `input` qatorlari mikrosekundda (`mean_us` ...), qolganlari millisekundda.
     */
    fun summary(
        kind: String,
        samples: Int,
        meanNs: Double,
        p95Ns: Double,
        jitterNs: Double,
        note: String? = null
    ) {
        val micros = kind == "input"
        enqueue(
            Record(
                row = kind,
                tNs = System.nanoTime(),
                samples = samples,
                meanNs = if (micros) Double.NaN else meanNs,
                p95Ns = if (micros) Double.NaN else p95Ns,
                jitterNs = if (micros) Double.NaN else jitterNs,
                meanUs = if (micros) meanNs / 1_000.0 else Double.NaN,
                p95Us = if (micros) p95Ns / 1_000.0 else Double.NaN,
                jitterUs = if (micros) jitterNs / 1_000.0 else Double.NaN,
                note = note
            )
        )
    }

    /** Kamera oqimi statistikasi (oqimdan ~2 marta/s). */
    fun stats(stats: CaptureStats) {
        enqueue(
            Record(
                row = "stats",
                tNs = System.nanoTime(),
                leftPx = stats.blockLeft,
                rightPx = stats.blockRight,
                bandY = stats.bandCenterY,
                detectMs = stats.detectMs,
                peaks = stats.peakCount,
                samples = stats.frames.toInt(),
                meanNs = stats.frameAvgMs * 1_000_000.0,
                p95Ns = stats.frameMaxMs * 1_000_000.0,
                jitterNs = stats.jitterMs * 1_000_000.0,
                deltaInputNs = (stats.pipelineDelayMs * 1_000_000.0).toLong(),
                note = String.format(
                    Locale.US,
                    "fps=%.2f size=%dx%d has_block=%b width_drift_p95=%.2f heap_bps=%d " +
                        "band_lum=%.0f strip_lum=%.0f strip_max=%.0f",
                    stats.fps,
                    stats.width,
                    stats.height,
                    stats.hasBlock,
                    stats.widthDriftP95,
                    stats.heapDeltaBytesPerSec,
                    stats.bandLuminance,
                    stats.stripLuminance,
                    stats.stripMaxLuminance
                )
            )
        )
    }

    /** Erkin yozuv: sessiya hodisalari, sozlama o'zgarishlari, xabarlar. */
    fun event(kind: String, note: String) {
        enqueue(Record(row = kind, tNs = System.nanoTime(), note = note))
    }

    private fun enqueue(record: Record) {
        // Never block the measurement path: a full queue means the disk is the
        // bottleneck, and dropping a row is better than stalling the camera.
        if (!running) return
        queue.offer(record)
    }

    private fun drain() {
        while (running || queue.isNotEmpty()) {
            when (val item = try {
                queue.take()
            } catch (_: InterruptedException) {
                continue
            }) {
                is Record -> writeRow(item)
                else -> runCatching { writer.flush() }
            }
        }
        runCatching { writer.flush() }
    }

    private fun writeRow(record: Record) {
        val line = StringBuilder(220)
        line.append(record.row)
        appendLong(line, record.tNs)
        appendLong(line, record.imageTsNs)
        appendLong(line, record.arrivalNs)
        appendLong(line, record.vsyncNs)
        // delta_stream_ms
        if (record.imageTsNs > 0L && record.vsyncNs > 0L) {
            appendDecimal(line, (record.imageTsNs - record.vsyncNs) / 1e6, 3)
        } else {
            line.append(',')
        }
        // delta_pipeline_ms
        if (record.arrivalNs > 0L && record.imageTsNs > 0L) {
            appendDecimal(line, (record.arrivalNs - record.imageTsNs) / 1e6, 3)
        } else {
            line.append(',')
        }
        appendInt(line, record.matched)
        appendInt(line, record.hasBlock)
        appendFloat(line, record.leftPx)
        appendFloat(line, record.rightPx)
        appendInt(line, record.bandY)
        appendFloat(line, record.stackLeftPx)
        appendFloat(line, record.stackRightPx)
        appendDecimal(line, record.detectMs, 3)
        appendInt(line, record.peaks)
        appendLong(line, record.contentTsNs)
        appendInt(line, record.tap)
        appendFloat(line, record.xPx)
        appendFloat(line, record.yPx)
        appendFloat(line, record.offsetPx)
        appendInt(line, record.perfect)
        appendInt(line, record.cameraShift)
        // delta_input_us — Δ_input mikrosekundlarda
        if (record.deltaInputNs > 0L) {
            appendDecimal(line, record.deltaInputNs / 1e3, 1)
        } else {
            line.append(',')
        }
        appendInt(line, record.samples)
        // mean/p95/jitter in ms (stream, stats)
        appendDecimal(line, toMs(record.meanNs), 3)
        appendDecimal(line, toMs(record.p95Ns), 3)
        appendDecimal(line, toMs(record.jitterNs), 3)
        // mean/p95/jitter in us (input)
        appendDecimal(line, record.meanUs, 1)
        appendDecimal(line, record.p95Us, 1)
        appendDecimal(line, record.jitterUs, 1)
        appendNote(line, record.note)
        line.append('\n')

        writer.write(line.toString())
        rows.incrementAndGet()
        // Keep the file useful even if the process is killed mid-session.
        if (++pending >= FLUSH_EVERY_ROWS) {
            pending = 0
            runCatching { writer.flush() }
        }
    }

    private fun toMs(ns: Double): Double = if (ns.isNaN()) Double.NaN else ns / 1e6

    private fun appendLong(target: StringBuilder, value: Long) {
        target.append(',').append(value)
    }

    private fun appendInt(target: StringBuilder, value: Int) {
        target.append(',')
        if (value != NOT_SET) target.append(value)
    }

    private fun appendFloat(target: StringBuilder, value: Float) {
        target.append(',')
        if (!value.isNaN()) target.append(String.format(Locale.US, "%.2f", value))
    }

    private fun appendDecimal(target: StringBuilder, value: Double, digits: Int) {
        target.append(',')
        if (!value.isNaN()) target.append(String.format(Locale.US, "%.${digits}f", value))
    }

    private fun appendNote(target: StringBuilder, note: String?) {
        target.append(',')
        if (note == null) return
        if (note.any { it == ',' || it == '"' || it == '\n' }) {
            target.append('"').append(note.replace("\"", "\"\"")).append('"')
        } else {
            target.append(note)
        }
    }

    /** Yozuvchi oqimga flush buyuradi; kutmaydi. */
    fun flush() {
        if (running) queue.offer(FlushMarker)
    }

    override fun close() {
        if (!running) return
        running = false
        // Wake the writer; it drains whatever is still queued before exiting.
        thread.interrupt()
        runCatching { thread.join(5_000) }
        queue.offer(FlushMarker)
        // Anything the queue still holds (or a writer that had to be interrupted)
        // is flushed here so no row is lost when the session ends.
        runCatching { writer.flush() }
        runCatching { writer.close() }
    }

    private companion object {
        const val QUEUE_CAPACITY = 8192
        const val FLUSH_EVERY_ROWS = 30
        const val NOT_SET = -1

        /**
         * Ustunlar tartibi. `row` — yozuv turi; boshqa ustunlar turga qarab
         * to'ldiriladi, mos kelmasa bo'sh qoladi.
         */
        const val COLUMNS =
            "row,t_ns,image_ts_ns,arrival_ns,vsync_ns,delta_stream_ms,delta_pipeline_ms," +
                "matched,has_block,left_px,right_px,band_y,stack_left_px,stack_right_px," +
                "detect_ms,peaks,content_ts_ns,tap,x_px,y_px,offset_px,perfect,camera_shift," +
                "delta_input_us,samples,mean_ms,p95_ms,jitter_ms,mean_us,p95_us,jitter_us,note"
    }
}
