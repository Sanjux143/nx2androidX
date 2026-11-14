package com.nx.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 帧率档位：只有宿主认得的档位算数，选项表与值一一对应 */
class RatePinTest {

    @Test
    fun `only the host's steps are valid`() {
        assertTrue(RatePin.valid(0))    // 跟随游戏
        assertTrue(RatePin.valid(60)); assertTrue(RatePin.valid(40)); assertTrue(RatePin.valid(30))
        assertFalse(RatePin.valid(50)); assertFalse(RatePin.valid(-1)); assertFalse(RatePin.valid(120))
    }

    @Test
    fun `the options and the values line up`() {
        assertEquals(listOf(0, 60, 40, 30), RatePin.PINS.toList())
        assertEquals(0, RatePin.indexOf(0))
        assertEquals(1, RatePin.indexOf(60))
        assertEquals(2, RatePin.indexOf(40))
        assertEquals(3, RatePin.indexOf(30))
        assertEquals(-1, RatePin.indexOf(50))   // 宿主不认得的档位：自由自适应
    }
}
