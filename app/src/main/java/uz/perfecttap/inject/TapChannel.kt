package uz.perfecttap.inject

import uz.perfecttap.core.RootManager
import java.io.BufferedWriter
import java.io.Closeable
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Bosishni yuborish yo'li. Koordinatalar **ekran pikselida** beriladi: evdev
 * kanallari ularni o'z o'qlariga aylantiradi, Shizuku esa to'g'ridan-to'g'ri
 * ishlatadi.
 */
interface TapChannel : Closeable {
    val description: String
    fun tap(x: Float, y: Float, holdMs: Long = 8)
}

/**
 * Fastest path: the app owns an fd on the input node and writes input_event
 * structs itself. Only possible when the node is writable from the app domain.
 */
class DirectTapChannel(
    private val stream: FileOutputStream,
    private val encoder: EvdevEncoder,
    private val pressureMax: Int?,
    private val device: TouchDevice,
    private val screenWidthPx: Int,
    private val screenHeightPx: Int
) : TapChannel {

    private var trackingId = 1

    override val description: String = "to'g'ridan-to'g'ri fd (app)"

    override fun tap(x: Float, y: Float, holdMs: Long) {
        val pressure = pressureMax?.let { (it / 2).coerceAtLeast(1) }
        val xRaw = device.xAxis.toRaw(x, screenWidthPx)
        val yRaw = device.yAxis.toRaw(y, screenHeightPx)
        stream.write(encoder.down(xRaw, yRaw, trackingId, pressure))
        stream.flush()
        Thread.sleep(holdMs)
        stream.write(encoder.up())
        stream.flush()
        trackingId = nextTrackingId(trackingId)
    }

    override fun close() {
        runCatching { stream.close() }
    }
}

/**
 * Fallback path: a persistent root shell keeps fd 3 open on the input node, and
 * each tap is a `printf` of raw bytes. Works even when SELinux forbids the app
 * domain from writing input devices.
 */
class ShellTapChannel(
    private val session: ShellSession,
    private val encoder: EvdevEncoder,
    private val pressureMax: Int?,
    private val device: TouchDevice,
    private val screenWidthPx: Int,
    private val screenHeightPx: Int
) : TapChannel {

    private var trackingId = 1

    override val description: String get() = "root shell fd 3 (${session.command})"

    override fun tap(x: Float, y: Float, holdMs: Long) {
        val pressure = pressureMax?.let { (it / 2).coerceAtLeast(1) }
        val xRaw = device.xAxis.toRaw(x, screenWidthPx)
        val yRaw = device.yAxis.toRaw(y, screenHeightPx)
        session.sendRaw("printf '" + encoder.down(xRaw, yRaw, trackingId, pressure).toPrintfEscapes() + "' >&3")
        Thread.sleep(holdMs)
        session.sendRaw("printf '" + encoder.up().toPrintfEscapes() + "' >&3")
        trackingId = nextTrackingId(trackingId)
    }

    override fun close() {
        session.close()
    }
}

internal fun nextTrackingId(current: Int): Int = if (current >= 65_000) 1 else current + 1

/** Each byte becomes a 3-digit octal escape, e.g. 0x00 -> \000, 0xFF -> \377. */
fun ByteArray.toPrintfEscapes(): String = buildString(size * 4) {
    for (byte in this@toPrintfEscapes) {
        val value = byte.toInt() and 0xFF
        append('\\')
        append('0' + (value shr 6))
        append('0' + ((value shr 3) and 7))
        append('0' + (value and 7))
    }
}

/** A root `su` process used as a low-latency command pipe. */
class ShellSession private constructor(
    private val process: Process,
    val command: String,
    private val writer: BufferedWriter,
    private val lines: LinkedBlockingQueue<String>,
    private val readerThread: Thread
) {

    fun sendRaw(command: String) {
        writer.write(command)
        writer.write("\n")
        writer.flush()
    }

    /** Waits for any of [markers]; returns the matching line or null on timeout. */
    fun awaitAny(markers: Collection<String>, timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            val line = lines.poll(remaining, TimeUnit.MILLISECONDS) ?: return null
            if (markers.any { it in line }) return line
        }
    }

    fun close() {
        runCatching { writer.close() }
        runCatching { readerThread.interrupt() }
        runCatching { process.destroy() }
    }

    companion object {
        // Covers Magisk, KernelSU, APatch and legacy SuperSU layouts; "su" is last
        // so PATH lookup still catches unusual ROMs.
        private val CANDIDATES = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/debug_ramdisk/su",
            "/sbin/su",
            "/su/bin/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/apd/bin/su",
            "su"
        )

        /**
         * Starts `su`, waits for a shell, then opens fd 3 on [devPath].
         * Returns null (with human readable reasons appended to [log]) on failure.
         */
        fun open(devPath: String, log: MutableList<String>): ShellSession? {
            for (binary in CANDIDATES) {
                val process = try {
                    ProcessBuilder(binary).redirectErrorStream(true).start()
                } catch (_: IOException) {
                    continue
                }
                val session = try {
                    attach(process, binary)
                } catch (t: Throwable) {
                    runCatching { process.destroy() }
                    log += "su ($binary) ishga tushmadi: ${t.message}"
                    continue
                }

                session.sendRaw("echo __PT_HELLO__")
                if (session.awaitAny(listOf("__PT_HELLO__"), 6_000) == null) {
                    log += "su ($binary) javob bermadi (root ruxsati berilganmi?)"
                    session.close()
                    continue
                }

                session.sendRaw("exec 3>$devPath && echo __PT_FD_OK__ || echo __PT_FD_FAIL__")
                val answer = session.awaitAny(
                    listOf("__PT_FD_OK__", "__PT_FD_FAIL__"),
                    4_000
                )
                if (answer == null || "__PT_FD_FAIL__" in answer) {
                    log += "su ($binary) $devPath ni ocha olmadi"
                    session.close()
                    continue
                }
                log += "Root shell tayyor: $binary (fd 3 → $devPath)"
                return session
            }
            return null
        }

        private fun attach(process: Process, binary: String): ShellSession {
            val queue = LinkedBlockingQueue<String>()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().useLines { seq ->
                        seq.forEach { queue.put(it) }
                    }
                } catch (_: Throwable) {
                    // Process closed; awaitAny will time out.
                }
            }.apply {
                isDaemon = true
                name = "pt-su-reader"
                start()
            }
            val writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.US_ASCII))
            return ShellSession(process, binary, writer, queue, reader)
        }
    }
}

/**
 * Last-resort fallback: runs `sendevent` for each event through the root shell.
 * Much slower than fd writes, but `sendevent` exists on every Android build and
 * does not depend on shell redirection support.
 */
class SendeventTapChannel(
    private val root: RootManager,
    private val devPath: String,
    private val encoder: EvdevEncoder,
    private val pressureMax: Int?,
    private val device: TouchDevice,
    private val screenWidthPx: Int,
    private val screenHeightPx: Int
) : TapChannel {

    private var trackingId = 1

    override val description: String = "sendevent (sekin zaxira usul)"

    override fun tap(x: Float, y: Float, holdMs: Long) {
        val pressure = pressureMax?.let { (it / 2).coerceAtLeast(1) }
        val xRaw = device.xAxis.toRaw(x, screenWidthPx)
        val yRaw = device.yAxis.toRaw(y, screenHeightPx)
        root.exec(sendeventCommand(encoder.downEvents(xRaw, yRaw, trackingId, pressure)))
        Thread.sleep(holdMs)
        root.exec(sendeventCommand(encoder.upEvents()))
        trackingId = nextTrackingId(trackingId)
    }

    private fun sendeventCommand(events: List<EvdevEvent>): String =
        events.joinToString("; ") { "sendevent $devPath ${it.type} ${it.code} ${it.value}" }

    override fun close() = Unit
}

/** Tries the direct fd first, then a root shell pipe, then sendevent. */
class TapChannelFactory(private val root: RootManager) {

    data class Result(
        val channel: TapChannel?,
        val mode: String,
        val log: List<String>
    )

    fun open(device: TouchDevice, screenWidthPx: Int, screenHeightPx: Int): Result {
        val log = mutableListOf<String>()
        val path = device.node.devPath

        val chmod = root.exec("chmod 666 $path")
        log += if (chmod.success) {
            "chmod 666 $path — OK"
        } else {
            "chmod 666 $path — xato: ${chmod.stderr.ifBlank { "code=${chmod.code}" }}"
        }

        direct(device, screenWidthPx, screenHeightPx)?.let {
            log += "Yozish usuli: ${it.description}"
            return Result(it, "direct", log)
        }

        log += "Bevosita yozib bo'lmadi (SELinux) — root shell oqimiga o'tildi"
        // Bu yerda baytlarni ilova emas, root shell yozadi: struct input_event
        // o'lchami shell jarayonining bitligiga bog'liq (32-bit ilova 64-bit
        // qurilmada ishlasa ham shell 64-bit bo'ladi).
        val encoder = EvdevEncoder(is64Bit = isDevice64Bit())
        ShellSession.open(path, log)?.let { session ->
            log += "Yozish usuli: root shell (${session.command})"
            return Result(
                ShellTapChannel(
                    session,
                    encoder,
                    device.pressureMax,
                    device,
                    screenWidthPx,
                    screenHeightPx
                ),
                "root-shell",
                log
            )
        }

        val sendevent = root.exec("command -v sendevent")
        if (sendevent.success && sendevent.stdout.isNotBlank()) {
            log += "Yozish usuli: sendevent (zaxira)"
            return Result(
                SendeventTapChannel(
                    root,
                    path,
                    encoder,
                    device.pressureMax,
                    device,
                    screenWidthPx,
                    screenHeightPx
                ),
                "sendevent",
                log
            )
        }

        return Result(null, "none", log + "✗ Bosish kanali ochilmadi")
    }

    private fun direct(device: TouchDevice, w: Int, h: Int): TapChannel? = try {
        val stream = FileOutputStream(device.node.devPath)
        DirectTapChannel(
            stream,
            EvdevEncoder(is64Bit = is64BitAbi()),
            device.pressureMax,
            device,
            w,
            h
        )
    } catch (_: Throwable) {
        null
    }

    private fun is64BitAbi(): Boolean = android.os.Process.is64Bit()

    private fun isDevice64Bit(): Boolean = android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
}
