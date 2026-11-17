package com.nx.android.feature.library

import com.nx.android.domain.GameStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 库页的主动作：触屏的主按钮、手柄的确认键、提示条上的文案都走它，
 * 所以这里定死了，三处就不会走样。
 *
 * 只有两个动作进入这个映射：完成的包「运行」（运行库过期也一样——游戏页的 Loading 自己重建），
 * 其余「构建」。没有「更新运行库」这个动作了。
 */
class MainActionTest {

    @Test
    fun `a finished port runs`() {
        assertEquals(MainAction.RUN, mainActionOf(GameStatus.DONE))
    }

    @Test
    fun `a game that is being ported shows its build`() {
        assertEquals(MainAction.VIEW_BUILD, mainActionOf(GameStatus.PORTING))
    }

    @Test
    fun `a game that was never ported is built`() {
        assertEquals(MainAction.BUILD, mainActionOf(GameStatus.NEEDS_BUILD))
        assertEquals(MainAction.BUILD, mainActionOf(GameStatus.NOT_PORTED))
    }
}
