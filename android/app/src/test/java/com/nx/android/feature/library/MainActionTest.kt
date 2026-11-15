package com.nx.android.feature.library

import com.nx.android.domain.GameStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 库页的主动作：触屏的主按钮、手柄的确认键、提示条上的文案都走它，
 * 所以这里定死了，三处就不会走样。
 */
class MainActionTest {

    @Test
    fun `a finished port runs`() {
        assertEquals(MainAction.RUN, mainActionOf(GameStatus.DONE, false))
    }

    @Test
    fun `an old pack is ported again`() {
        assertEquals(MainAction.BUILD, mainActionOf(GameStatus.DONE, true))
    }

    @Test
    fun `a game that is being ported shows its build`() {
        assertEquals(MainAction.VIEW_BUILD, mainActionOf(GameStatus.PORTING, false))
    }

    @Test
    fun `a game that was never ported is built`() {
        assertEquals(MainAction.BUILD, mainActionOf(GameStatus.NEEDS_BUILD, false))
        assertEquals(MainAction.BUILD, mainActionOf(GameStatus.NOT_PORTED, false))
    }
}
