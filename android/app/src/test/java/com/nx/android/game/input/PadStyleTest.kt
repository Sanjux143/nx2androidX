package com.nx.android.game.input

import org.junit.Assert.assertEquals
import org.junit.Test

/** 按键风格：默认是自带（数据包的主题贴图），每个游戏各记一份，认不出来的值回落到自带 */
class PadStyleTest {

    @Test
    fun `the bundled style is the default`() {
        assertEquals(PadStyle.PACK, PadStyle.of(null))
        assertEquals(PadStyle.PACK, PadStyle.of(""))
        assertEquals(PadStyle.PACK, PadStyle.of("pack"))
    }

    @Test
    fun `a value nobody knows falls back to the bundled style`() {
        assertEquals(PadStyle.PACK, PadStyle.of("bogus"))
        assertEquals(PadStyle.PACK, PadStyle.of("DEFAULT"))
    }

    @Test
    fun `the default style survives the round trip`() {
        assertEquals(PadStyle.DEFAULT, PadStyle.of("default"))
        assertEquals("default", PadStyle.DEFAULT.key)
        assertEquals("pack", PadStyle.PACK.key)
    }

    @Test
    fun `every game keeps its own style`() {
        assertEquals("pad.style.0100000000010000", PadStyle.key("0100000000010000"))
        assertEquals("pad.style.0100A00019DE0000", PadStyle.key("0100A00019DE0000"))
        assertEquals("pad.style", PadStyle.key(null))
        assertEquals("pad.style", PadStyle.key(""))
    }
}
