package com.nx.android.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 推给宿主的映射表（行式文本）：宿主那边读的就是它（nxnative/dm/android/pad_map.h）。
 */
class PadSpecTest {

    @Test
    fun `the table is one block per device`() {
        val text = PadSpec.build(listOf(
            PadSpec.Device(
                deviceId = 3,
                labels = true,
                player = 1,
                calibration = PadCalibration(deadzone = 0.15f, trigger = 0.55f),
                bindings = mapOf(PadSources.scan(305) to "a", PadSources.scan(314) to PadSources.DISABLED),
            ),
            PadSpec.Device(deviceId = 7, labels = false),
        ))
        val lines = text.trim().lines()
        assertEquals("v1", lines[0])
        assertEquals("dev 3 labels=1 player=1 dz=0.15 tr=0.55", lines[1])
        assertTrue(lines.contains("bind scan:305 a"))
        assertTrue(lines.contains("unbind scan:314"))
        assertTrue(lines.contains("dev 7 labels=0 player=0 dz=0.12 tr=0.5"))
    }

    @Test
    fun `junk never reaches the host`() {
        val text = PadSpec.build(listOf(
            PadSpec.Device(deviceId = 1, labels = false, bindings = mapOf("nonsense" to "a", PadSources.scan(305) to "nosuchbutton")),
        ))
        assertTrue(!text.contains("nonsense"))
        assertTrue(!text.contains("nosuchbutton"))
    }
}
