package uz.perfecttap.inject

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Linux input event type/code constants used by a type-B multitouch tap. */
object Evdev {
    const val EV_SYN = 0x00
    const val EV_KEY = 0x01
    const val EV_ABS = 0x03

    const val SYN_REPORT = 0x00

    const val ABS_MT_SLOT = 0x2f
    const val ABS_MT_POSITION_X = 0x35
    const val ABS_MT_POSITION_Y = 0x36
    const val ABS_MT_TRACKING_ID = 0x39
    const val ABS_MT_PRESSURE = 0x3a

    const val BTN_TOOL_FINGER = 0x145
    const val BTN_TOUCH = 0x14a
}

data class EvdevEvent(val type: Int, val code: Int, val value: Int)

/**
 * Encodes touch input as `struct input_event` records or as `sendevent` lines.
 *
 * Layout (little endian):
 *   64-bit: timeval { i64 sec, i64 usec } + u16 type + u16 code + i32 value = 24 bytes
 *   32-bit: timeval { i32 sec, i32 usec } + u16 type + u16 code + i32 value = 16 bytes
 *
 * Timestamps are left at zero: the evdev write path stamps events with the
 * kernel's current time, so a value from the app would be ignored anyway.
 */
class EvdevEncoder(val is64Bit: Boolean) {

    val structSize: Int = if (is64Bit) 24 else 16

    fun event(type: Int, code: Int, value: Int): ByteArray =
        encode(listOf(EvdevEvent(type, code, value)))

    /** Finger down: slot 0 -> new tracking id -> position -> (pressure) -> BTN_TOUCH. */
    fun downEvents(xRaw: Int, yRaw: Int, trackingId: Int, pressure: Int? = null): List<EvdevEvent> =
        buildList {
            add(EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_SLOT, 0))
            add(EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_TRACKING_ID, trackingId))
            add(EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_POSITION_X, xRaw))
            add(EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_POSITION_Y, yRaw))
            if (pressure != null) add(EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_PRESSURE, pressure))
            add(EvdevEvent(Evdev.EV_KEY, Evdev.BTN_TOUCH, 1))
            add(EvdevEvent(Evdev.EV_KEY, Evdev.BTN_TOOL_FINGER, 1))
            add(EvdevEvent(Evdev.EV_SYN, Evdev.SYN_REPORT, 0))
        }

    /** Finger up: release tracking id and buttons. */
    fun upEvents(): List<EvdevEvent> = listOf(
        EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_SLOT, 0),
        EvdevEvent(Evdev.EV_ABS, Evdev.ABS_MT_TRACKING_ID, -1),
        EvdevEvent(Evdev.EV_KEY, Evdev.BTN_TOUCH, 0),
        EvdevEvent(Evdev.EV_KEY, Evdev.BTN_TOOL_FINGER, 0),
        EvdevEvent(Evdev.EV_SYN, Evdev.SYN_REPORT, 0)
    )

    /** A single SYN_REPORT commits all pending events atomically. */
    fun down(xRaw: Int, yRaw: Int, trackingId: Int, pressure: Int? = null): ByteArray =
        encode(downEvents(xRaw, yRaw, trackingId, pressure))

    fun up(): ByteArray = encode(upEvents())

    fun encode(events: List<EvdevEvent>): ByteArray {
        val buffer = ByteBuffer.allocate(structSize * events.size).order(ByteOrder.LITTLE_ENDIAN)
        for (event in events) {
            if (is64Bit) {
                buffer.putLong(0L)
                buffer.putLong(0L)
            } else {
                buffer.putInt(0)
                buffer.putInt(0)
            }
            buffer.putShort(event.type.toShort())
            buffer.putShort(event.code.toShort())
            buffer.putInt(event.value)
        }
        return buffer.array()
    }
}
