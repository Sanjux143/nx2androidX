package com.nx.android.core.ui.nav

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 手柄的键 → 界面导航动作的换算。
 *
 * 按**位置**解释：底下那颗面键是确认，右边那颗是返回（Android 的键名就是这么起的：
 * KEYCODE_BUTTON_A 是底下那颗，BUTTON_B 是右边那颗）。
 */
class NavKeyTest {

    @Test
    fun `the face button at the bottom confirms`() {
        assertEquals(NavKey.Confirm, NavKeys.fromKeyCode(KeyEvent.KEYCODE_BUTTON_A))
    }

    @Test
    fun `the face button on the right goes back`() {
        assertEquals(NavKey.Back, NavKeys.fromKeyCode(KeyEvent.KEYCODE_BUTTON_B))
    }

    @Test
    fun `the dpad moves`() {
        assertEquals(NavKey.Up, NavKeys.fromKeyCode(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(NavKey.Down, NavKeys.fromKeyCode(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(NavKey.Left, NavKeys.fromKeyCode(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(NavKey.Right, NavKeys.fromKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(NavKey.Confirm, NavKeys.fromKeyCode(KeyEvent.KEYCODE_DPAD_CENTER))
    }

    @Test
    fun `the controller's own back key goes back`() {
        assertEquals(NavKey.Back, NavKeys.fromKeyCode(KeyEvent.KEYCODE_BACK))
    }

    @Test
    fun `the start key opens the details`() {
        assertEquals(NavKey.Menu, NavKeys.fromKeyCode(KeyEvent.KEYCODE_BUTTON_START))
        assertEquals(NavKey.Menu, NavKeys.fromKeyEvent(315, KeyEvent.KEYCODE_BUTTON_START))
    }

    @Test
    fun `the shoulders switch the tabs`() {
        assertEquals(NavKey.TabLeft, NavKeys.fromKeyCode(KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals(NavKey.TabRight, NavKeys.fromKeyCode(KeyEvent.KEYCODE_BUTTON_R1))
        // 按位置也一样：左肩 310、右肩 311
        assertEquals(NavKey.TabLeft, NavKeys.fromKeyEvent(310, KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals(NavKey.TabRight, NavKeys.fromKeyEvent(311, KeyEvent.KEYCODE_BUTTON_R1))
    }

    @Test
    fun `keys that are not navigation do nothing`() {
        assertNull(NavKeys.fromKeyCode(KeyEvent.KEYCODE_BUTTON_X))
        assertNull(NavKeys.fromKeyCode(KeyEvent.KEYCODE_BUTTON_Y))
        assertNull(NavKeys.fromKeyCode(KeyEvent.KEYCODE_A))
        assertNull(NavKeys.fromKeyCode(KeyEvent.KEYCODE_VOLUME_UP))
        // Select（−）还没有动作
        assertNull(NavKeys.fromKeyCode(KeyEvent.KEYCODE_BUTTON_SELECT))
    }

    @Test
    fun `the injected keys are only the dpad, the face buttons, start and the shoulders`() {
        // 软键盘还要用：系统返回键与别的键不放进来
        assertNull(NavKeys.fromInjectedKeyCode(KeyEvent.KEYCODE_BACK))
        assertNull(NavKeys.fromInjectedKeyCode(KeyEvent.KEYCODE_A))
        assertEquals(NavKey.Confirm, NavKeys.fromInjectedKeyCode(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(NavKey.Back, NavKeys.fromInjectedKeyCode(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(NavKey.Menu, NavKeys.fromInjectedKeyCode(KeyEvent.KEYCODE_BUTTON_START))
        assertEquals(NavKey.TabLeft, NavKeys.fromInjectedKeyCode(KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals(NavKey.TabRight, NavKeys.fromInjectedKeyCode(KeyEvent.KEYCODE_BUTTON_R1))
        assertEquals(NavKey.Right, NavKeys.fromInjectedKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    @Test
    fun `the stick only counts when it is pushed to the side`() {
        assertNull(NavKeys.fromAxis(0f, 0f))
        assertNull(NavKeys.fromAxis(0.3f, -0.4f))
        assertEquals(NavKey.Right, NavKeys.fromAxis(0.9f, 0f))
        assertEquals(NavKey.Left, NavKeys.fromAxis(-0.9f, 0f))
        assertEquals(NavKey.Down, NavKeys.fromAxis(0f, 0.9f))
        assertEquals(NavKey.Up, NavKeys.fromAxis(0f, -0.9f))
    }

    @Test
    fun `a diagonal takes the stronger side`() {
        assertEquals(NavKey.Right, NavKeys.fromAxis(0.9f, 0.7f))
        assertEquals(NavKey.Down, NavKeys.fromAxis(0.7f, 0.9f))
    }

    @Test
    fun `the face buttons are told apart by where they are, not by their name`() {
        // Xbox 布局：底下那颗叫 BUTTON_A，右边那颗叫 BUTTON_B
        assertEquals(NavKey.Confirm, NavKeys.fromKeyEvent(304, KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(NavKey.Back, NavKeys.fromKeyEvent(305, KeyEvent.KEYCODE_BUTTON_B))
        // 任天堂布局（Switch Pro）：底下那颗叫 BUTTON_B，右边那颗叫 BUTTON_A —— 位置不变
        assertEquals(NavKey.Confirm, NavKeys.fromKeyEvent(304, KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(NavKey.Back, NavKeys.fromKeyEvent(305, KeyEvent.KEYCODE_BUTTON_A))
    }

    @Test
    fun `the dpad is told apart by its key code`() {
        // 十字键在各家布局里都是同一个 keyCode
        assertEquals(NavKey.Up, NavKeys.fromKeyEvent(0, KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(NavKey.Right, NavKeys.fromKeyEvent(0, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(NavKey.Down, NavKeys.fromKeyEvent(0, KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(NavKey.Left, NavKeys.fromKeyEvent(0, KeyEvent.KEYCODE_DPAD_LEFT))
    }

    @Test
    fun `a key without a scan code falls back to its key code`() {
        assertEquals(NavKey.Confirm, NavKeys.fromKeyEvent(0, KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(NavKey.Back, NavKeys.fromKeyEvent(0, KeyEvent.KEYCODE_BUTTON_B))
        assertNull(NavKeys.fromKeyEvent(0, KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun `holding a direction repeats at a steady pace`() {        // 首键立即
        assertEquals(true, NavKeys.repeatAllowed(0))
        // 按住不放：不是每一次重复都发
        assertEquals(false, NavKeys.repeatAllowed(1))
        assertEquals(false, NavKeys.repeatAllowed(2))
        assertEquals(true, NavKeys.repeatAllowed(3))
        assertEquals(false, NavKeys.repeatAllowed(4))
        assertEquals(true, NavKeys.repeatAllowed(6))
    }

    @Test
    fun `the stick fires once and waits for the center`() {
        val edge = NavEdge()
        // 推到底：一次
        assertEquals(NavKey.Right, edge.onAxis(0.9f, 0f))
        // 一直偏着：不再发
        assertNull(edge.onAxis(0.9f, 0f))
        assertNull(edge.onAxis(1f, 0f))
        // 回中：复位
        assertNull(edge.onAxis(0f, 0f))
        // 再推：又一次
        assertEquals(NavKey.Right, edge.onAxis(0.9f, 0f))
    }

    @Test
    fun `the stick can change direction without the center`() {
        val edge = NavEdge()
        assertEquals(NavKey.Right, edge.onAxis(0.9f, 0f))
        assertEquals(NavKey.Down, edge.onAxis(0f, 0.9f))
        assertEquals(NavKey.Up, edge.onAxis(0f, -0.9f))
    }
}
