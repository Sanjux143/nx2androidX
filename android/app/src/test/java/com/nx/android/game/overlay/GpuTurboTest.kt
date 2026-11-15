package com.nx.android.game.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/** GPU 提频的三档：关、一直最高、掉帧才拉高；认不出来的值当「关」 */
class GpuTurboTest {

    @Test
    fun `the host's three modes`() {
        assertEquals(listOf("0", "1", "auto"), GpuTurbo.MODES)
    }

    @Test
    fun `the two live modes stand`() {
        assertEquals(GpuTurbo.ON, GpuTurbo.normalize("1"))
        assertEquals(GpuTurbo.AUTO, GpuTurbo.normalize("auto"))
    }

    @Test
    fun `anything else is off`() {
        assertEquals(GpuTurbo.OFF, GpuTurbo.normalize(null))
        assertEquals(GpuTurbo.OFF, GpuTurbo.normalize("0"))
        assertEquals(GpuTurbo.OFF, GpuTurbo.normalize("junk"))
    }
}
