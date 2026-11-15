package com.nx.android.core.ui.nav

import android.view.KeyEvent
import kotlin.math.abs

/**
 * 手柄在 App 自己的界面里能做的事。
 *
 * 这一套含义只在这里定义一次：页面订阅它，不自己解释按键。
 *
 * 按**位置**解释（底下的面键是确认，右边的是返回）——这与游戏里手柄默认映射的约定一致
 * （见 controller-center 的 D14：出厂字典按位置判定），提示条上画的也是这个位置。
 *
 * 它**不**读玩家在「手柄中心」里改的键位档案：那是「手柄 → 游戏」的路，这里是「手柄 → 界面」，
 * 两件事的方向不同，别接错。
 */
enum class NavKey {
    Up, Down, Left, Right,

    /** 底下那颗面键：确认（当前这一层的主要动作） */
    Confirm,

    /** 右边那颗面键：返回 */
    Back,

    /** Start 键：打开当前选中东西的详情 */
    Menu,

    /** 左肩键：上一个页签 */
    TabLeft,

    /** 右肩键：下一个页签 */
    TabRight,
}

/**
 * 按键码与摇杆 → [NavKey] 的换算。
 *
 * 纯函数，没有 Android UI 的依赖：JVM 里可直接测。
 */
object NavKeys {

    /** 摇杆推到底才算方向（十字键帽与左摇杆各一份，帽子优先）。 */
    const val STICK_THRESHOLD = 0.6f

    /** 按住不放时的连发节流：首键立即，之后每 [REPEAT_EVERY] 次重复发一次。 */
    const val REPEAT_EVERY = 3

    /**
     * Linux 手柄规范的扫描码（**位置**）。
     *
     * 与 `PadMapping.Scan` 是同一组数（平台的事实，各存一份：界面这一层不去依赖游戏那边）。
     */
    object Scan {
        const val SOUTH = 304   // 底下那颗面键
        const val EAST = 305    // 右边那颗面键
        const val TL = 310      // 左肩键
        const val TR = 311      // 右肩键
        const val START = 315   // Start 键
    }

    /** 按键码 → 导航动作。不是导航用的键返回 null。 */
    fun fromKeyCode(keyCode: Int): NavKey? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> NavKey.Up
        KeyEvent.KEYCODE_DPAD_DOWN -> NavKey.Down
        KeyEvent.KEYCODE_DPAD_LEFT -> NavKey.Left
        KeyEvent.KEYCODE_DPAD_RIGHT -> NavKey.Right
        KeyEvent.KEYCODE_DPAD_CENTER -> NavKey.Confirm
        KeyEvent.KEYCODE_BUTTON_A -> NavKey.Confirm   // 底下的面键
        KeyEvent.KEYCODE_BUTTON_B -> NavKey.Back       // 右侧的面键
        KeyEvent.KEYCODE_BUTTON_START -> NavKey.Menu   // Start
        KeyEvent.KEYCODE_BUTTON_L1 -> NavKey.TabLeft   // 左肩键
        KeyEvent.KEYCODE_BUTTON_R1 -> NavKey.TabRight  // 右肩键
        KeyEvent.KEYCODE_BACK -> NavKey.Back           // 手柄自己的返回键（Home 键等）
        else -> null
    }

    /**
     * 调试用的注入口子（`adb shell input keyevent`）能带进来的键。
     *
     * 只认十字键、面键、Start 与肩键：**不带**系统返回键，也别把软键盘的键吃了——
     * 调试的时候软键盘还要用（PhantomFix 那个对话框要输码）。
     */
    fun fromInjectedKeyCode(keyCode: Int): NavKey? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1 -> fromKeyCode(keyCode)
        else -> null
    }

    /**
     * Linux 的扫描码（**位置**）：底下 304、右边 305、左肩 310、右肩 311、Start 315。
     *
     * 系统给的 keyCode 是按手柄上**印的字**翻的，各家不一样：Xbox 布局把底下那颗叫
     * `BUTTON_A`，任天堂布局（Switch Pro）把底下那颗叫 `BUTTON_B`、右边那颗叫 `BUTTON_A`。
     * 扫描码说的是按键**在哪儿**，每一家都一样——游戏那边早就是按它认的
     * （见 `PadMapping` 的出厂字典），界面这边也按它认，两边才对得上。
     */
    fun fromScanCode(scanCode: Int): NavKey? = when (scanCode) {
        Scan.SOUTH -> NavKey.Confirm   // 底下那颗
        Scan.EAST -> NavKey.Back       // 右边那颗
        Scan.TL -> NavKey.TabLeft       // 左肩键
        Scan.TR -> NavKey.TabRight      // 右肩键
        Scan.START -> NavKey.Menu       // Start
        else -> null
    }

    /**
     * 一个按键事件 → 导航动作。
     *
     * 十字键按 keyCode（`DPAD_*` 在各家布局里都一样）；其余按**扫描码**（位置），
     * 扫描码没有才落回 keyCode。
     */
    fun fromKeyEvent(scanCode: Int, keyCode: Int): NavKey? {
        val byKey = fromKeyCode(keyCode)
        if (byKey == NavKey.Up || byKey == NavKey.Down || byKey == NavKey.Left || byKey == NavKey.Right) return byKey
        return fromScanCode(scanCode) ?: byKey
    }

    /**
     * 摇杆推到底的方向。
     *
     * x 往右是正，y 往下是正（Android 的坐标就是这样：上负下正）。
     * 两个方向都推到底时取更大的那个。
     */
    fun fromAxis(x: Float, y: Float, threshold: Float = STICK_THRESHOLD): NavKey? {
        val ax = abs(x)
        val ay = abs(y)
        return when {
            ax < threshold && ay < threshold -> null
            ax >= ay -> if (x > 0f) NavKey.Right else NavKey.Left
            else -> if (y > 0f) NavKey.Down else NavKey.Up
        }
    }

    /** 按住不放时该不该发：首键立即，之后按 [every] 的节奏发。 */
    fun repeatAllowed(repeatCount: Int, every: Int = REPEAT_EVERY): Boolean =
        repeatCount == 0 || repeatCount % every == 0
}

/**
 * 摇杆的边沿触发：推到底发一次，回中才能再走一步。
 *
 * 不这样做的话，摇杆一推就一路走到头（它不会「按一下」，它一直偏着）。
 */
class NavEdge(private val threshold: Float = NavKeys.STICK_THRESHOLD) {

    private var last: NavKey? = null

    /** 摇杆现在的位置给了哪个方向（该发的时候才给，回去了就给 null）。 */
    fun onAxis(x: Float, y: Float): NavKey? {
        val dir = NavKeys.fromAxis(x, y, threshold)
        if (dir == null) {
            last = null
            return null
        }
        if (dir == last) return null
        last = dir
        return dir
    }
}
