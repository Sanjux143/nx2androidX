package com.nx.android.game.input

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 虚拟手柄的模型：布局、命中判定与触摸路由。
 *
 * 命中判定原来在宿主里（front_player.c 的 ctl_at）：圆角矩形的距离、触摸可达范围、两��控件时谁更深。
 * 现在 App 自己判，用的是同一套数——宿主把每个控件的圆角半径与可达范围一起给了（dm_pad_layout_json），
 * App 不再自己推导，于是两边的判定逐一致。
 *
 * 长度一律以「窗口高度」为单位（布局自己的单位）：宿主按它的窗口算好，App 按自己的视图高度乘一遍。
 */

/** 一个控件：名字、种类、按键位、位置与大小 */
data class PadControl(
    val name: String,
    val kind: String,          // stick / dpad / button / menu
    val btn: Long,
    val cx: Float, val cy: Float,   // 中心（窗口高度为单位）
    val hw: Float, val hh: Float,   // 半宽、半高
    val r: Float,               // 圆角半径
    val reach: Float,           // 边缘外还接住触摸的范围
    val s: Float,               // 玩家自己的大小系数（1 = 布局的）
    val hidden: Boolean = false,   // 玩家在编辑器里把它藏起来了（还在控件表里，只是对局里不出现）
) {
    val isMenu: Boolean get() = kind == "menu"
    val isStick: Boolean get() = kind == "stick"
    val isDpad: Boolean get() = kind == "dpad"
    /** 最小的那一半：可达范围与圆角都从它算 */
    val m: Float get() = min(hw, hh)
}

/** 布局：控件们、窗口的宽高、缩放系数与版本号（宿主给的） */
data class PadLayout(
    val name: String = "tablet",
    val ver: Int = -1,
    val opacity: Int = 60,
    val w: Float = 0f,
    val h: Float = 0f,
    val scale: Float = 1f,
    val controls: List<PadControl> = emptyList(),
) {
    companion object {
        /** 宿主给的布局（JSON，见 front_player.c 的 dm_pad_layout_json） */
        fun parse(json: String): PadLayout {
            val o = JSONObject(json)
            val arr = o.optJSONArray("controls") ?: return PadLayout()
            val list = ArrayList<PadControl>(arr.length())
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                list.add(
                    PadControl(
                        name = c.optString("n"),
                        kind = c.optString("k", "button"),
                        btn = c.optLong("b"),
                        cx = c.optDouble("cx", 0.0).toFloat(),
                        cy = c.optDouble("cy", 0.0).toFloat(),
                        hw = c.optDouble("hw", 0.0).toFloat(),
                        hh = c.optDouble("hh", 0.0).toFloat(),
                        r = c.optDouble("r", 0.0).toFloat(),
                        reach = c.optDouble("reach", 0.0).toFloat(),
                        s = c.optDouble("s", 1.0).toFloat(),
                    )
                )
            }
            return PadLayout(
                name = o.optString("layout", "tablet"),
                ver = o.optInt("ver", -1),
                opacity = o.optInt("opacity", 60),
                w = o.optDouble("w", 0.0).toFloat(),
                h = o.optDouble("h", 0.0).toFloat(),
                scale = o.optDouble("scale", 1.0).toFloat(),
                controls = list,
            )
        }
    }
}

/** 控件的大小系数（相对布局自己的大小）的上下限：宿主从前也是这两个数（OVE_SCALE_MIN/MAX） */
const val PAD_SIZE_MIN = 0.5f
const val PAD_SIZE_MAX = 2.0f

/**
 * 一个被拖动的控件的中心：留在屏幕上（长度都是窗口高度）。
 * 与宿主原来的 ov_drag_pos 同一套规矩：控件整个留在屏幕内。
 */
fun padDragPos(p: Float, half: Float, extent: Float): Float {
    val lo = min(half, extent / 2); val hi = extent - lo
    return min(max(p, lo), hi)
}

/**
 * 命中判定：圆角矩形的距离（负数在里面）与触摸可达范围
 *
 * 它只管几何：藏起来的控件照样命中（编辑器要能点到它们，才能把它找回来），
 * 「藏起来的控件不回答」这件事在对局的路由里判（PadRouter.route）。
 */
object PadHit {
    /** (x, y) 落在哪个控件上（-1 = 没有）；H 是视图的高度（像素），布局的数都是窗口高度 */
    fun at(ctrls: List<PadControl>, x: Float, y: Float, H: Float): Int {
        var best = -1
        var bd = Float.MAX_VALUE
        for (i in ctrls.indices) {
            val c = ctrls[i]
            val hw = c.hw * H; val hh = c.hh * H
            val rad = c.r * H; val reach = c.reach * H
            val dx = x - c.cx * H; val dy = y - c.cy * H
            val qx = abs(dx) - hw + rad; val qy = abs(dy) - hh + rad
            val px = max(qx, 0f); val py = max(qy, 0f)
            val d = sqrt(px * px + py * py) + min(max(qx, qy), 0f) - rad
            val m = min(hw, hh)
            // 落在可达范围内，且比别的控件「陷得更深」（按它的大小折算）
            if (d < reach) {
                val score = (d + m) / (m + reach)
                if (score < bd) { bd = score; best = i }
            }
        }
        return best
    }
}

/**
 * 一个控件换了大小系数（或是被拖到了别处）之后的样子：宽、高、圆角与可达范围都跟着走
 * （可达范围要看布局的密度系数，与宿主 ov_reach 同一套规矩）。
 */
fun padResized(c: PadControl, size: Float, scale: Float, cx: Float = c.cx, cy: Float = c.cy): PadControl {
    val old = if (c.s > 0f) c.s else 1f
    val f = size / old
    val hw = c.hw * f; val hh = c.hh * f
    val m = min(hw, hh)
    val reach = if (c.kind == "button" || c.kind == "menu") max(m * 0.25f, 0.045f * scale - m) else m * 0.5f
    return c.copy(cx = cx, cy = cy, hw = hw, hh = hh, r = c.r * f, reach = reach, s = size)
}

/** 一次触摸路由的结果：按键位、两个摇杆、属于游戏的触点（数组是复用的：调用方立刻就用掉） */
class PadOutput {
    var buttons = 0L
    var lx = 0; var ly = 0; var rx = 0; var ry = 0
    var ids: IntArray = EMPTY_IDS
    var xs: FloatArray = EMPTY_F
    var ys: FloatArray = EMPTY_F

    companion object {
        val EMPTY_IDS = IntArray(0)
        val EMPTY_F = FloatArray(0)
    }
}

/**
 * 触摸路由：每一根手指按下去时记住它落在哪个控件上，直到抬起；控件上的手指变成按键位与摇杆量，
 * 其余的照旧是游戏的触摸屏。多余的手指（超过 16 根）宿主那边也只收 16 根。
 */
class PadRouter {

    /** 按住的手指：pointer id → 控件下标（-1 = 游戏的触摸屏） */
    private val held = HashMap<Int, Int>()
    /** 按住的控件（画的时候用） */
    private val pressed = LinkedHashSet<Int>()
    /** 两个摇杆的帽（-1..1，窗口坐标，向下为正） */
    val knobs = floatArrayOf(0f, 0f, 0f, 0f)

    /** 菜单按钮：按下的手指、拖动了吗、按下的位置与手指相对按钮中心的偏移 */
    var menuPointer = -1
        private set
    var menuDragged = false
        private set
    /** 菜单按钮被点按了（不是拖动）：App 开自己的抽屉 */
    var menuTapped = false
        private set

    /** 取走「菜单按钮被点按了」这个信号（取过就没有了） */
    fun takeMenuTap(): Boolean {
        val t = menuTapped
        menuTapped = false
        return t
    }
    private var menuX0 = 0f
    private var menuY0 = 0f
    private var menuOffX = 0f
    private var menuOffY = 0f

    /** 上一次路由出来的结果（App 自己知道按住了什么） */
    var buttons: Long = 0
        private set

    /** 十字键此刻按着的方向（按键位）：画它的高亮用（斜向是两个位） */
    var dpadDir = 0L
        private set

    fun pressedControls(): Set<Int> = pressed

    /** 路由的中间量：复用的数组（一次触摸只填一次，不分配） */
    private val knob = FloatArray(4)
    private val gids = IntArray(16)
    private val gxs = FloatArray(16)
    private val gys = FloatArray(16)

    /**
     * 处理一次触摸：action 与所有指针（MotionEvent 的快照，n 个）。
     * 结果填进 out（复用的）：给游戏的结果（按键位、摇杆、游戏的触点）。
     */
    fun route(action: Int, actionIndex: Int, ids: IntArray, xs: FloatArray, ys: FloatArray, n: Int,
              ctrls: List<PadControl>, H: Float, out: PadOutput) {
        val down = action == ACTION_DOWN || action == ACTION_POINTER_DOWN
        val up = action == ACTION_UP || action == ACTION_POINTER_UP
        if (down) {
            val id = if (actionIndex < n) ids[actionIndex] else -1
            val x = if (actionIndex < n) xs[actionIndex] else 0f
            val y = if (actionIndex < n) ys[actionIndex] else 0f
            val hit = PadHit.at(ctrls, x, y, H)
            // 藏起来的控件不回答：它那片地方跟没有它一样，触摸归游戏
            val c = if (hit >= 0 && ctrls[hit].hidden) -1 else hit
            held[id] = c
            if (c >= 0) {
                pressed.add(c)
                if (ctrls[c].isMenu) {
                    menuPointer = id; menuDragged = false
                    menuX0 = x; menuY0 = y
                    menuOffX = ctrls[c].cx * H - x
                    menuOffY = ctrls[c].cy * H - y
                }
            }
        } else if (up) {
            val id = if (actionIndex < n) ids[actionIndex] else -1
            val c = held.remove(id) ?: -1
            if (c >= 0) pressed.remove(c)
            if (id == menuPointer) {
                if (c >= 0 && c < ctrls.size && ctrls[c].isMenu && !menuDragged) menuTapped = true   // 点按：开抽屉
                menuPointer = -1
            }
        } else if (action == ACTION_CANCEL) {
            release()
            out.buttons = 0; out.lx = 0; out.ly = 0; out.rx = 0; out.ry = 0
            out.ids = PadOutput.EMPTY_IDS; out.xs = PadOutput.EMPTY_F; out.ys = PadOutput.EMPTY_F
            return
        }

        // 每一根按着的手指：它按住的控件给什么
        var btn = 0L
        var dpad = 0L   // 十字键自己按着的方向（画它的高亮用）
        var lx = 0; var ly = 0; var rx = 0; var ry = 0
        knob[0] = 0f; knob[1] = 0f; knob[2] = 0f; knob[3] = 0f
        var gn = 0
        for (i in 0 until n) {
            if (up && i == actionIndex) continue   // 抬起的手指不算了
            val id = ids[i]
            val x = xs[i]; val y = ys[i]
            val c = held[id] ?: -1
            if (c < 0 || c >= ctrls.size) {   // 游戏的触摸屏
                if (gn < 16) { gids[gn] = id; gxs[gn] = x; gys[gn] = y; gn++ }
                continue
            }
            val k = ctrls[c]
            if (k.isMenu) continue   // 菜单按钮不发给游戏
            val hw = k.hw * H
            val dx = (x - k.cx * H) / hw; val dy = (y - k.cy * H) / hw
            val m = sqrt(dx * dx + dy * dy)
            when {
                k.kind == "button" -> btn = btn or k.btn
                k.isDpad -> {
                    if (m > 0.25f) {
                        val a = atan2(dy, dx)
                        val s = ((a / (Math.PI / 4)).roundToInt()) and 7   // 八个方向，0 = 右，顺时针（y 向下）
                        btn = btn or DPAD_DIRS[s]
                        dpad = dpad or DPAD_DIRS[s]   // 斜向是两个方向（宿主也是两个位）
                    }
                }
                else -> {   // 摇杆
                    var ux = dx; var uy = dy
                    if (m > 1f) { ux /= m; uy /= m }
                    val right = if (k.name == "stick_r") 1 else 0
                    knob[right * 2] = ux; knob[right * 2 + 1] = uy
                    val sx = (ux * STICK_MAX).toInt(); val sy = (-uy * STICK_MAX).toInt()
                    if (right == 1) { rx = sx; ry = sy } else { lx = sx; ly = sy }
                }
            }
        }
        buttons = btn
        dpadDir = dpad
        knobs[0] = knob[0]; knobs[1] = knob[1]; knobs[2] = knob[2]; knobs[3] = knob[3]
        out.buttons = btn; out.lx = lx; out.ly = ly; out.rx = rx; out.ry = ry
        // 游戏的触点：数组的长度就是个数（JNI 照它读），个数没变就复用
        if (gn == 0) {
            out.ids = PadOutput.EMPTY_IDS; out.xs = PadOutput.EMPTY_F; out.ys = PadOutput.EMPTY_F
        } else {
            if (out.ids.size != gn) { out.ids = IntArray(gn); out.xs = FloatArray(gn); out.ys = FloatArray(gn) }
            System.arraycopy(gids, 0, out.ids, 0, gn)
            System.arraycopy(gxs, 0, out.xs, 0, gn)
            System.arraycopy(gys, 0, out.ys, 0, gn)
        }
    }

    /** 菜单按钮：手指拖过了吗（拖过就是挪按钮，不是开抽屉） */
    fun menuMoved(x: Float, y: Float, H: Float): Boolean {
        if (menuPointer < 0) return false
        val dx = x - menuX0; val dy = y - menuY0
        if (!menuDragged && sqrt(dx * dx + dy * dy) < DRAG_SLOP * H) return false
        menuDragged = true
        return true
    }

    /** 菜单按钮的新位置（窗口的分数）：手指按下的位置与按钮中心的偏移保持不变，整个按钮留在屏幕内 */
    fun menuPosition(x: Float, y: Float, ctl: PadControl, W: Float, H: Float): Pair<Float, Float> {
        val px = dragPos(x + menuOffX, ctl.hw * H, W)
        val py = dragPos(y + menuOffY, ctl.hh * H, H)
        return Pair(px / W, py / H)
    }

    /** 一个被拖动的控件的中心：留在屏幕上（长度都是窗口高度） */
    fun dragPos(p: Float, half: Float, extent: Float): Float = padDragPos(p, half, extent)

    /** 手指抬起时收尾（菜单按钮的拖动结束） */
    fun release() {
        held.clear(); pressed.clear()
        menuPointer = -1; menuDragged = false
        dpadDir = 0L
        knobs[0] = 0f; knobs[1] = 0f; knobs[2] = 0f; knobs[3] = 0f
    }

    companion object {
        const val STICK_MAX = 32767
        const val DRAG_SLOP = 0.02f      // 手指按在菜单按钮上又走了这么远（窗口高度的比例），就是拖动而不是点按
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
        const val ACTION_POINTER_DOWN = 5
        const val ACTION_POINTER_UP = 6
        private val DPAD_DIRS = longArrayOf(
            1L shl 14,                                     // 右
            (1L shl 14) or (1L shl 15),                    // 右下
            1L shl 15,                                     // 下
            (1L shl 15) or (1L shl 12),                    // 左下
            1L shl 12,                                     // 左
            (1L shl 12) or (1L shl 13),                    // 左上
            1L shl 13,                                     // 上
            (1L shl 13) or (1L shl 14),                    // 右上
        )
        /** 十字键的方向名 → 它的按键位（主题贴图里的方向名，与 DPAD_DIRS 一样） */
        val DPAD_BITS = mapOf("right" to (1L shl 14), "down" to (1L shl 15), "left" to (1L shl 12), "up" to (1L shl 13))
    }
}
