package com.nx.android.appstate

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 玩家的门：进程在场是点击时问一次的事实（没有轮询、没有缓存），身份（title ID 与名字）
 * 是「上次启动的是谁」的记忆，存在偏好里供冷启动后的门使用。这里只测身份的读写回路——
 * 门的决策表在 LibraryLogicTest 里。
 */
class PlayerStateTest {

    @Test
    fun `the launched identity is read back`() {
        val p = PlayerState(MemoryIdentityStore())
        p.markLaunched("0100000000010000", "Hades II")
        assertEquals("0100000000010000", p.currentTitleId())
        assertEquals("Hades II", p.currentName())
    }

    @Test
    fun `a door without a launch answers empty`() {
        val p = PlayerState(MemoryIdentityStore())
        assertEquals("", p.currentTitleId())
        assertEquals("", p.currentName())
    }

    @Test
    fun `a new launch replaces the identity`() {
        val store = MemoryIdentityStore()
        val p = PlayerState(store)
        p.markLaunched("0100000000010000", "Hades II")
        p.markLaunched("0100000000020000", "Another game")
        assertEquals("0100000000020000", p.currentTitleId())
        assertEquals("Another game", p.currentName())
    }
}
