package com.nx.android.game.input

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 藏起来的控件不回答触摸：它那片地方跟没有它一样，触摸归游戏。
 * （隐藏是玩家在编辑器里长按出来的；对局里手柄照旧画其余的控件。）
 */
class PadRouterHiddenTest {

    /** 一个方块按钮（在窗口高度的单位里占 0.1 见方） */
    private fun button(name: String, btn: Long, hidden: Boolean = false) = PadControl(
        name = name,
        kind = "button",
        btn = btn,
        cx = 0.5f, cy = 0.5f,
        hw = 0.05f, hh = 0.05f,
        r = 0.01f, reach = 0.01f,
        s = 1f,
        hidden = hidden,
    )

    /** 按下去一次（手指落在控件的正中间） */
    private fun press(c: PadControl, H: Float = 100f): PadOutput {
        val out = PadOutput()
        PadRouter().route(
            PadRouter.ACTION_DOWN, 0,
            intArrayOf(0), floatArrayOf(c.cx * H), floatArrayOf(c.cy * H), 1,
            listOf(c), H, out,
        )
        return out
    }

    @Test
    fun `a visible control answers with its button`() {
        val out = press(button("zl", 1L shl 8))
        assertEquals(1L shl 8, out.buttons)
        assertEquals(0, out.ids.size)   // 这次触摸是按键，不是游戏的触摸屏
    }

    @Test
    fun `a hidden control answers nothing`() {
        val out = press(button("zl", 1L shl 8, hidden = true))
        assertEquals(0L, out.buttons)
        assertEquals(1, out.ids.size)   // 这次触摸交给游戏
    }

    @Test
    fun `the hidden flag does not move the control`() {
        val shown = button("zl", 1L shl 8)
        val ghost = button("zl", 1L shl 8, hidden = true)
        assertEquals(shown.cx, ghost.cx, 0f)
        assertEquals(shown.hw, ghost.hw, 0f)
        assertEquals(shown.name, ghost.name)
    }
}
