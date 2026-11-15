package com.nx.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 帧生成的旋钮：夹取、env 行的形状（DLL 行只在有私有副本时给），与 DLL 的表层校验 */
class FrameGenKnobsTest {

    @Test
    fun `clamping keeps the multiplier and flow in the host's range`() {
        assertEquals(2, FrameGenKnobs.clampMult(1)); assertEquals(3, FrameGenKnobs.clampMult(3)); assertEquals(4, FrameGenKnobs.clampMult(9))
        assertEquals(0, FrameGenKnobs.clampFlow(-5)); assertEquals(25, FrameGenKnobs.clampFlow(10)); assertEquals(100, FrameGenKnobs.clampFlow(150))
    }

    @Test
    fun `env lines carry the switch, the knobs and the dll`() {
        assertEquals(0, FrameGenKnobs(false, 3, 60, 25).envLines(null).size)   // off and no DLL: no rows at all
        val k = FrameGenKnobs(true, 3, 60, 25)
        assertEquals(listOf("DM_LSFG=1", "DM_LSFG_MULT=3", "DM_LSFG_TARGET=60", "DM_LSFG_FLOW=25"), k.envLines(null).toList())
        assertEquals(listOf("DM_LSFG=1", "DM_LSFG_MULT=3", "DM_LSFG_TARGET=60", "DM_LSFG_FLOW=25", "DM_LSFG_DLL=/data/lsfg/Lossless.dll"),
                     k.envLines("/data/lsfg/Lossless.dll").toList())
        assertEquals(0, FrameGenKnobs(false, 9, 60, 25).envLines(null).size)   // off: no rows, whatever the knobs hold
    }

    @Test
    fun `the dll's path goes to the host even when the generation is off`() {
        // the player turns the generation on in the drawer, with the game running: the host has to know the file then
        assertEquals(listOf("DM_LSFG_DLL=/data/lsfg/Lossless.dll"),
                     FrameGenKnobs(false, 2, 0, 0).envLines("/data/lsfg/Lossless.dll").toList())
        // no file on the device: nothing to hand over
        assertEquals(0, FrameGenKnobs(false, 2, 0, 0).envLines(null).size)
    }

    @Test
    fun `the flow's default is half the picture, not the game's own resolution`() {
        // the default the settings fall back to: 50%, not auto (the game's own resolution: at 1080p the full-size flow
        // costs more than the frame it buys)
        assertEquals(50, FrameGenKnobs.DEFAULT_FLOW)
        assertTrue(FrameGenKnobs(true, 2, 0, FrameGenKnobs.DEFAULT_FLOW).envLines(null).contains("DM_LSFG_FLOW=50"))
        // auto is still one of the choices (0 = the game's own resolution), it is just not the default any more
        assertEquals(0, FrameGenKnobs.clampFlow(0))
    }

    @Test
    fun `a PE image of at least 100 KB validates`() {
        val f = File.createTempFile("dll", ".bin")
        f.writeBytes(ByteArray(50)); assertFalse(LosslessDll.looksValid(f))
        f.writeBytes(ByteArray(200_000)); assertFalse(LosslessDll.looksValid(f))          // big but not a PE image
        val bytes = ByteArray(200_000); bytes[0] = 'M'.code.toByte(); bytes[1] = 'Z'.code.toByte()
        f.writeBytes(bytes); assertTrue(LosslessDll.looksValid(f))
        f.delete()
    }
}
