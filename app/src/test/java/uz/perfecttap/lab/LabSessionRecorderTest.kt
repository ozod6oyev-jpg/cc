package uz.perfecttap.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Sessiya yozuvchisi CSV qatorlarini to'g'ri tuzishi va **hamma** tahlilni
 * (topilgan, topilmagan, mos kelmagan kadrlar) yozishi tekshiriladi.
 */
class LabSessionRecorderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val ms = 1_000_000L
    private val columns = 32

    private fun session(block: (LabSessionRecorder) -> Unit): List<List<String>> {
        val file = File(temporaryFolder.root, "lab.csv")
        LabSessionRecorder(file, listOf("app=test", "screen_px=1080x2400")).use(block)
        return file.readLines()
            .filterNot { it.startsWith("#") }
            .drop(1) // header
            .map { it.split(",", limit = columns) }
    }

    @Test
    fun writesHeaderWithColumnsAndMetadata() {
        val file = File(temporaryFolder.root, "lab.csv")
        LabSessionRecorder(file, listOf("app=test", "refresh_hz=116.0")).close()

        val lines = file.readLines()
        assertTrue(lines[0].startsWith("# PerfectTap"))
        assertTrue(lines[1].startsWith("# row,t_ns,image_ts_ns"))
        assertTrue(lines.contains("# app=test"))
        assertTrue(lines.contains("# refresh_hz=116.0"))
        assertEquals(
            "row,t_ns,image_ts_ns,arrival_ns,vsync_ns,delta_stream_ms,delta_pipeline_ms," +
                "matched,has_block,left_px,right_px,band_y,stack_left_px,stack_right_px," +
                "detect_ms,peaks,content_ts_ns,tap,x_px,y_px,offset_px,perfect,camera_shift," +
                "delta_input_us,samples,mean_ms,p95_ms,jitter_ms,mean_us,p95_us,jitter_us,note",
            lines.last()
        )
    }

    @Test
    fun writesMatchedFrameWithAllMeasurements() {
        val row = session { logger ->
            logger.frame(
                tNs = 50 * ms,
                imageTimestampNs = 36 * ms,
                arrivalNs = 44 * ms,
                vsyncNs = 26 * ms,
                matched = true,
                hasBlock = true,
                leftPx = 412.25f,
                rightPx = 668.75f,
                bandY = 1180,
                stackLeftPx = 420.5f,
                stackRightPx = 660f,
                detectMs = 0.41,
                peaks = 6,
                contentTsNs = 30 * ms,
                tapCount = 3
            )
        }.single()

        assertEquals(columns, row.size)
        assertEquals("frame", row[0])
        assertEquals("36000000", row[2])
        assertEquals("44000000", row[3])
        assertEquals("26000000", row[4])
        assertEquals("10.000", row[5])
        assertEquals("8.000", row[6])
        assertEquals("1", row[7])
        assertEquals("1", row[8])
        assertEquals("412.25", row[9])
        assertEquals("668.75", row[10])
        assertEquals("1180", row[11])
        assertEquals("420.50", row[12])
        assertEquals("660.00", row[13])
        assertEquals("0.410", row[14])
        assertEquals("6", row[15])
        assertEquals("30000000", row[16])
        assertEquals("3", row[17])
        assertEquals("", row[31])
    }

    @Test
    fun writesUnmatchedReasonInNote() {
        val row = session { logger ->
            logger.frame(
                tNs = 10 * ms,
                imageTimestampNs = 9 * ms,
                arrivalNs = 12 * ms,
                vsyncNs = 0L,
                matched = false,
                hasBlock = true,
                leftPx = 100f,
                rightPx = 300f,
                bandY = 900,
                stackLeftPx = 0f,
                stackRightPx = 0f,
                detectMs = 0.5,
                peaks = 4,
                contentTsNs = 0L,
                tapCount = 0,
                note = "no_draw_match"
            )
        }.single()

        assertEquals("no_draw_match", row[31])
    }

    @Test
    fun writesUnmatchedAndEmptyFramesToo() {
        val rows = session { logger ->
            logger.frame(
                tNs = 10 * ms,
                imageTimestampNs = 9 * ms,
                arrivalNs = 12 * ms,
                vsyncNs = 0L,
                matched = false,
                hasBlock = true,
                leftPx = 100f,
                rightPx = 300f,
                bandY = 900,
                stackLeftPx = 0f,
                stackRightPx = 0f,
                detectMs = 0.5,
                peaks = 4,
                contentTsNs = 0L,
                tapCount = 0
            )
            logger.frame(
                tNs = 20 * ms,
                imageTimestampNs = 19 * ms,
                arrivalNs = 22 * ms,
                vsyncNs = 0L,
                matched = false,
                hasBlock = false,
                leftPx = Float.NaN,
                rightPx = Float.NaN,
                bandY = 900,
                stackLeftPx = 0f,
                stackRightPx = 0f,
                detectMs = 0.2,
                peaks = 0,
                contentTsNs = 0L,
                tapCount = 0
            )
        }

        assertEquals(2, rows.size)
        assertEquals("0", rows[0][7])
        assertEquals("1", rows[0][8])
        assertEquals("", rows[0][5])
        assertEquals("0", rows[1][8])
        assertEquals("", rows[1][9])
    }

    @Test
    fun writesTapPlacementInputAndSummaryRows() {
        val rows = session { logger ->
            logger.tap(tNs = 100 * ms, xPx = 540f, yPx = 1200f, contentTsNs = 90 * ms, tapIndex = 1)
            logger.placement(
                tNs = 101 * ms,
                offsetPx = 2.5f,
                perfect = true,
                cameraShifted = false,
                widthPx = 300f,
                tapIndex = 1
            )
            logger.inputSample(tNs = 102 * ms, deltaNs = 12 * ms)
            logger.summary("stream", 5, 8 * ms.toDouble(), 9 * ms.toDouble(), 2 * ms.toDouble())
            logger.event("setting", "speed_px_s=600 growth=1.05")
        }

        val tap = rows[0]
        assertEquals("tap", tap[0])
        assertEquals("540.00", tap[18])
        assertEquals("1200.00", tap[19])
        assertEquals("90000000", tap[16])

        val place = rows[1]
        assertEquals("place", place[0])
        assertEquals("2.50", place[20])
        assertEquals("1", place[21])
        assertEquals("0", place[22])

        val input = rows[2]
        assertEquals("input", input[0])
        // Δ_input mikrosekundda: 12 ms → 12000.0 us
        assertEquals("12000.0", input[23])

        val summary = rows[3]
        assertEquals("stream", summary[0])
        assertEquals("5", summary[24])
        assertEquals("8.000", summary[25])
        assertEquals("2.000", summary[27])
        assertEquals("", summary[28])

        val setting = rows[4]
        assertEquals("setting", setting[0])
        assertEquals("speed_px_s=600 growth=1.05", setting[31])
    }

    @Test
    fun writesInputSummaryInMicroseconds() {
        val row = session { logger ->
            logger.summary("input", 120, 12_345_600.0, 20_000_000.0, 3_000_000.0)
        }.single()

        assertEquals("input", row[0])
        assertEquals("120", row[24])
        assertEquals("", row[25]) // mean_ms bo'sh: input µs da
        assertEquals("12345.6", row[28])
        assertEquals("20000.0", row[29])
        assertEquals("3000.0", row[30])
    }

    @Test
    fun quotesNotesWithSeparators() {
        val file = File(temporaryFolder.root, "lab.csv")
        LabSessionRecorder(file).use { logger ->
            logger.event("session", "end reason=user, rows=10")
        }
        val dataLine = file.readLines().last()
        assertTrue(dataLine.startsWith("session,"))
        // Vergul va qo'shtirnoq ham noto'g'ri ajratilmasligi uchun qo'shtirnoqqa olinadi.
        assertTrue(dataLine.endsWith(",\"end reason=user, rows=10\""))
    }
}
