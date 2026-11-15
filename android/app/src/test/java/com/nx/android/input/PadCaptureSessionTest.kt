package com.nx.android.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 捕获式改键：抓一个物理键，绑到那个游戏键上。
 */
class PadCaptureSessionTest {

    @Test
    fun `a press is captured with its scan code`() {
        val session = PadCaptureSession()
        var captured: String? = null
        session.start(deviceId = 3, now = 1000) { captured = it }
        val offer = session.offerKey(
            deviceId = 3, isGamepad = true, down = true, up = false, repeatCount = 0,
            scanCode = 305, keyCode = 97, now = 1100,
        )
        assertTrue(offer.consumed)
        assertEquals("scan:305", captured)
    }

    @Test
    fun `a key without a scan code is captured by its key code`() {
        val session = PadCaptureSession()
        var captured: String? = null
        session.start(deviceId = 3, now = 1000) { captured = it }
        session.offerKey(3, true, true, false, 0, 0, 97, 1100)
        assertEquals("key:97", captured)
    }

    @Test
    fun `another controller's key is not captured`() {
        val session = PadCaptureSession()
        var captured: String? = null
        session.start(deviceId = 3, now = 1000) { captured = it }
        val offer = session.offerKey(4, true, true, false, 0, 305, 97, 1100)
        assertTrue(offer.consumed)   // the event does not reach the game
        assertEquals(null, captured)
    }

    @Test
    fun `the capture gives up after its time`() {
        val session = PadCaptureSession(timeoutMs = 1000)
        var captured: String? = null
        session.start(deviceId = 3, now = 1000) { captured = it }
        val offer = session.offerKey(3, true, true, false, 0, 305, 97, 3000)
        assertTrue(offer.cancelled)
        assertEquals(null, captured)
    }

    @Test
    fun `without a capture the keys go to the game`() {
        val session = PadCaptureSession()
        val offer = session.offerKey(3, true, true, false, 0, 305, 97, 1000)
        assertFalse(offer.consumed)
    }

    @Test
    fun `a disconnected controller cancels the capture`() {
        val session = PadCaptureSession()
        session.start(deviceId = 3, now = 1000) { }
        assertTrue(session.cancelDisconnectedDevice(3))
        assertFalse(session.cancelDisconnectedDevice(4))
    }
}
