package com.nx.android.game.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 藏起来的控件：名单按游戏各记一份，存的是控件的名字 */
class PadHiddenTest {

    @Test
    fun `every game keeps its own list`() {
        assertEquals("pad.hidden", PadHidden.key(null))
        assertEquals("pad.hidden", PadHidden.key(""))
        assertEquals("pad.hidden.0100000000010000", PadHidden.key("0100000000010000"))
    }

    @Test
    fun `nothing hidden is the default`() {
        assertTrue(PadHidden.parse(null).isEmpty())
        assertTrue(PadHidden.parse("").isEmpty())
        assertTrue(PadHidden.parse("   ").isEmpty())
    }

    @Test
    fun `the list round trips`() {
        val names = setOf("zl", "zr", "ls")
        assertEquals(names, PadHidden.parse(PadHidden.format(names)))
    }

    @Test
    fun `the stored value is stable`() {
        assertEquals("ls,zl,zr", PadHidden.format(setOf("zl", "zr", "ls")))
        assertEquals("", PadHidden.format(emptySet()))
    }

    @Test
    fun `a broken value is read as nothing hidden`() {
        assertEquals(setOf("zl"), PadHidden.parse("zl,"))
        assertEquals(setOf("zl"), PadHidden.parse(" zl , "))
        assertTrue(PadHidden.parse(",,,").isEmpty())
    }
}
