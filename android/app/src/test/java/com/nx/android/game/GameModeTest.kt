package com.nx.android.game

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Android 的游戏模式转发给宿主的规则（上游 #294）：宿主自己画的那些游戏（NVN 与 Vulkan 的重放）
 * 走性能档的节奏策略，OpenGL ES 的游戏（手机自己的驱动在画）与老的数据包（不知道图形接口）不转发。
 */
class GameModeTest {

    @Test
    fun `the performance mode reaches a game the host draws`() {
        assertEquals(2, GameMode.forward(2, "nvn"))
        assertEquals(2, GameMode.forward(2, "vulkan"))
    }

    @Test
    fun `the standard mode reaches the host too`() {
        assertEquals(1, GameMode.forward(1, "nvn"))
    }

    @Test
    fun `a game the phone's own driver draws keeps the system's own handling`() {
        assertEquals(0, GameMode.forward(2, "gl"))
    }

    @Test
    fun `an old pack without the graphics field is left alone`() {
        assertEquals(0, GameMode.forward(2, ""))
    }

    @Test
    fun `the case of the field does not matter`() {
        assertEquals(2, GameMode.forward(2, "NVN"))
    }
}
