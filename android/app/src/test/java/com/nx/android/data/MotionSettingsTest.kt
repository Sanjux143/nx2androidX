package com.nx.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 体感的两个开关：交给宿主的 env 行与运行中的 ctl 行 */
class MotionSettingsTest {

    @Test
    fun `the motion on gives the host its row`() {
        assertEquals(
            listOf("DM_MOTION=1", "DM_MOTION_RAW=0"),
            MotionKnobs(on = true, raw = false).envLines().toList(),
        )
    }

    @Test
    fun `the measured direction is a second row`() {
        assertEquals(
            listOf("DM_MOTION=1", "DM_MOTION_RAW=1"),
            MotionKnobs(on = true, raw = true).envLines().toList(),
        )
    }

    @Test
    fun `the motion off says so`() {
        assertEquals("DM_MOTION=0", MotionKnobs(on = false, raw = false).envLines()[0])
    }
}
