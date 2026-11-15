package com.nx.android.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 查表规则：玩家自己绑的先，出厂字典兜底。
 *
 * 出厂字典按**位置**（扫描码）说话：游戏嘴里的 A/B/X/Y 指的就是 NS 手柄上的四个位置
 * （X 上、Y 左、A 右、B 下），手柄上印的字只是贴纸。
 * Android 给键起的名字（BUTTON_A、BUTTON_X…）每家手柄不一样，所以位置说了算，名字只是兜底。
 */
class PadMappingTest {

    @Test
    fun `the default dictionary is the Switch's layout by position`() {
        assertEquals("b", PadMapping.defaultTarget(PadMapping.Scan.SOUTH, 0))
        assertEquals("a", PadMapping.defaultTarget(PadMapping.Scan.EAST, 0))
        assertEquals("x", PadMapping.defaultTarget(PadMapping.Scan.NORTH, 0))
        assertEquals("y", PadMapping.defaultTarget(PadMapping.Scan.WEST, 0))
        assertEquals("l", PadMapping.defaultTarget(PadMapping.Scan.L1, 0))
        assertEquals("r", PadMapping.defaultTarget(PadMapping.Scan.R1, 0))
        assertEquals("zl", PadMapping.defaultTarget(PadMapping.Scan.L2, 0))
        assertEquals("zr", PadMapping.defaultTarget(PadMapping.Scan.R2, 0))
        assertEquals("ls", PadMapping.defaultTarget(PadMapping.Scan.L3, 0))
        assertEquals("rs", PadMapping.defaultTarget(PadMapping.Scan.R3, 0))
        assertEquals("plus", PadMapping.defaultTarget(PadMapping.Scan.START, 0))
        assertEquals("minus", PadMapping.defaultTarget(PadMapping.Scan.SELECT, 0))
        // the d-pad has no scan code: the key code's name it is
        assertEquals("up", PadMapping.defaultTarget(0, PadMapping.Key.DPAD_UP))
        assertEquals("down", PadMapping.defaultTarget(0, PadMapping.Key.DPAD_DOWN))
        assertEquals("left", PadMapping.defaultTarget(0, PadMapping.Key.DPAD_LEFT))
        assertEquals("right", PadMapping.defaultTarget(0, PadMapping.Key.DPAD_RIGHT))
        assertNull(PadMapping.defaultTarget(306, 0))
        assertNull(PadMapping.defaultTarget(0, 9999))
    }

    @Test
    fun `the position wins over the key code's name`() {
        // 任天堂的手柄把上面那颗叫 BUTTON_Y（keyCode 100），把左边那颗叫 BUTTON_X：
        // 名字各家的手柄不一样，位置才是说了算的
        assertEquals("x", PadMapping.defaultTarget(PadMapping.Scan.NORTH, PadMapping.Key.BUTTON_Y))
        assertEquals("y", PadMapping.defaultTarget(PadMapping.Scan.WEST, PadMapping.Key.BUTTON_X))
        // 通用键位表的手柄：名字与位置本来就对得上
        assertEquals("x", PadMapping.defaultTarget(PadMapping.Scan.NORTH, PadMapping.Key.BUTTON_X))
        assertEquals("b", PadMapping.defaultTarget(PadMapping.Scan.SOUTH, PadMapping.Key.BUTTON_A))
    }

    @Test
    fun `by print the face buttons are the Xbox print`() {
        // 弃用的那一套：按 Xbox 的印字（下 A、右 B、上 Y、左 X）
        assertEquals("a", PadMapping.defaultTarget(PadMapping.Scan.SOUTH, 0, labels = true))
        assertEquals("b", PadMapping.defaultTarget(PadMapping.Scan.EAST, 0, labels = true))
        assertEquals("y", PadMapping.defaultTarget(PadMapping.Scan.NORTH, 0, labels = true))
        assertEquals("x", PadMapping.defaultTarget(PadMapping.Scan.WEST, 0, labels = true))
        // the rest is the same either way
        assertEquals("l", PadMapping.defaultTarget(PadMapping.Scan.L1, 0, labels = true))
        assertEquals("plus", PadMapping.defaultTarget(PadMapping.Scan.START, 0, labels = true))
    }

    @Test
    fun `the default source of a game key is its position`() {
        assertEquals(PadSources.scan(PadMapping.Scan.NORTH), PadMapping.defaultSourceFor("x"))
        assertEquals(PadSources.scan(PadMapping.Scan.WEST), PadMapping.defaultSourceFor("y"))
        assertEquals(PadSources.scan(PadMapping.Scan.EAST), PadMapping.defaultSourceFor("a"))
        assertEquals(PadSources.scan(PadMapping.Scan.SOUTH), PadMapping.defaultSourceFor("b"))
        assertEquals(PadSources.key(PadMapping.Key.DPAD_UP), PadMapping.defaultSourceFor("up"))
        assertNull(PadMapping.defaultSourceFor("nothing"))
    }

    @Test
    fun `the player's own binding wins over the dictionary`() {
        val mapping = mapOf(PadSources.scan(305) to "y")
        assertEquals("y", PadMapping.resolveButton(mapping, labels = false, scanCode = 305, keyCode = PadMapping.Key.BUTTON_B))
        // a key the player did not touch: the dictionary, by position
        assertEquals("x", PadMapping.resolveButton(mapping, labels = false, scanCode = 307, keyCode = PadMapping.Key.BUTTON_X))
        // the key code is the fallback when there is no scan code
        assertEquals("b", PadMapping.resolveButton(mapping, labels = false, scanCode = 0, keyCode = PadMapping.Key.BUTTON_A))
        val byKey = mapOf(PadSources.key(PadMapping.Key.BUTTON_A) to "plus")
        assertEquals("plus", PadMapping.resolveButton(byKey, labels = false, scanCode = 0, keyCode = PadMapping.Key.BUTTON_A))
    }

    @Test
    fun `a disabled source is no button at all`() {
        val mapping = mapOf(PadSources.scan(314) to PadSources.DISABLED)
        assertTrue(PadMapping.isDisabled(mapping, 314, PadMapping.Key.BUTTON_SELECT))
        assertNull(PadMapping.resolveButton(mapping, labels = false, scanCode = 314, keyCode = PadMapping.Key.BUTTON_SELECT))
    }

    @Test
    fun `binding a key moves it and frees the target it took`() {
        val mapping = mapOf(PadSources.scan(305) to "a")
        val moved = PadMapping.withBinding(mapping, PadSources.scan(307), "a")
        assertEquals("a", moved[PadSources.scan(307)])
        assertNull(moved[PadSources.scan(305)])   // the old one let go
    }

    @Test
    fun `a conflict names both sides`() {
        val mapping = mapOf(PadSources.scan(305) to "a", PadSources.scan(307) to "b")
        val conflict = PadMapping.conflict(mapping, PadSources.scan(309), "a")
        assertNull(conflict?.sourceTarget)   // the new source was not bound to anything
        assertEquals(PadSources.scan(305), conflict?.targetSource)
        // swapping gives the target to the new source and lets the old one go
        val swapped = PadMapping.swapBinding(mapping, PadSources.scan(309), "a")
        assertEquals("a", swapped[PadSources.scan(309)])
        assertFalse(swapped.containsKey(PadSources.scan(305)))
    }

    @Test
    fun `an axis binding is a half axis`() {
        val mapping = mapOf(PadSources.axis(17, 1f) to "zl")
        assertEquals("zl", PadMapping.resolveAxis(mapping, 17, 0.9f))
        assertNull(PadMapping.resolveAxis(mapping, 17, -0.9f))
        assertTrue(PadMapping.hasAxisBinding(mapping, 17))
        assertFalse(PadMapping.hasAxisBinding(mapping, 18))
    }

    @Test
    fun `the sources we know`() {
        assertTrue(PadSources.isKnown("scan:305"))
        assertTrue(PadSources.isKnown("key:96"))
        assertTrue(PadSources.isKnown("axis:17:+"))
        assertFalse(PadSources.isKnown("scan:"))
        assertFalse(PadSources.isKnown("nonsense"))
        assertFalse(PadSources.isKnown("axis:0:+"))   // the sticks are not capturable
    }
}
