package uz.perfecttap.capture

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Laboratoriya kadrlari **yo'qotilmasdan** uzatilishi tekshiriladi: yozuv
 * «hamma tahlil, bittasi ham tashlanmaydi» degan talabga tayanadi.
 */
class CaptureBusTest {

    @After
    fun clear() {
        CaptureBus.clearLabFrames()
    }

    @Test
    fun keepsEveryPublishedFrameInOrder() {
        CaptureBus.clearLabFrames()
        val count = 3_000

        repeat(count) { index ->
            CaptureBus.publishLabFrame(
                LabObservation(
                    imageTimestampNs = index.toLong(),
                    arrivalNs = 0L,
                    hasBlock = true,
                    left = 0f,
                    right = 1f,
                    bandCenterY = 0
                )
            )
        }

        repeat(count) { index ->
            val observation = CaptureBus.labObservations.tryReceive().getOrNull()
            assertNotNull("frame $index missing", observation)
            assertEquals(index.toLong(), observation!!.imageTimestampNs)
        }
    }

    @Test
    fun clearDropsFramesFromAPreviousSession() {
        CaptureBus.publishLabFrame(
            LabObservation(1L, 1L, true, 0f, 1f, 0)
        )

        CaptureBus.clearLabFrames()

        assertEquals(null, CaptureBus.labObservations.tryReceive().getOrNull())
    }
}
