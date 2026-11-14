package com.nx.android.game.input

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import com.nx.android.data.PrefsStore
import com.nx.android.game.Native
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * 虚拟手柄的绘制层（android-virtual-pad）：只画，命中判定与输入分流在 App 自己手里
 * （PadModel 的 PadHit / PadRouter），宿主只收结果（Native.padInput）。
 *
 * App 自己知道按住了什么、摇杆的帽在哪：不再每帧轮询宿主的状态，也没有「让位标志」那一套。
 * 布局来自宿主（dm_pad_layout_json）：控件的位置与大小（按屏幕密度缩放好的），还有圆角与可达范围。
 * 绘制优先用数据包自带的主题贴图（overlay/theme.tsv + 图册，与宿主主题同一套数据），没有主题时退回矢量绘制。
 *
 * 画的时候每帧不分配：贴图按控件分好组（theme.tsv 读进来时就分），名字到下标的表也是，
 * 贴图的源与目标矩形是复用的，控件表缓存着（编辑器改了它才重算）。
 */
class PadView(context: Context, private val prefs: PrefsStore, private val pack: File?, private val titleId: String?) : View(context) {

    companion object {
        const val KEY_SHOWN = "pad.shown"
        const val KEY_OPACITY = "pad.opacity"
        /** 默认主题包（ergo）在 app 自己的 assets 里：移植器把整棵 nxnative 树打进了 APK */
        const val DEFAULT_THEME_DIR = "tool/nxnative/dm/android/themes/ergo"
        private const val TAG = "nx2a.pad"
        private val LABELS = mapOf("plus" to "+", "minus" to "−")
        private const val TAP_MS = 14      // 按下那一下的时长（照旧宿主 haptics_android.c 的 vib_tap）
        private const val TAP_AMP = 140    // 它的振幅（1..255）
    }

    /** 主题从哪来：自带读数据包的 overlay/，默认读 app 里的默认主题包 */
    private class ThemeSource(
        /** 主题表（theme.tsv）的文本 */
        val text: () -> String?,
        /** 图册（atlas）的位图 */
        val atlas: (String) -> Bitmap?,
    )

    /** 一个贴图：图册里的位置与大小、相对控件中心的偏移（设计用的 CSS px） */
    private class Sp(
        val part: String, val state: String,
        val ax: Int, val ay: Int, val aw: Int, val ah: Int, val ox: Float, val oy: Float,
    )

    /** 控件的一个部分（十字键的一个方向，按键就是它自己）：静止与按住的两张贴图 */
    private class Part(val part: String) {
        var idle: Sp? = null
        var held: Sp? = null
        /** 十字键的方向位（别的控件是 0）：按住了这个方向就画它的按住贴图 */
        val dirBit: Long = PadRouter.DPAD_BITS[part] ?: 0L
    }

    /** 一个控件的贴图：装饰（在别的下面）、摇杆的帽、它的各部分 */
    private class CtlSp {
        val decor = ArrayList<Sp>(1)
        val knob = ArrayList<Sp>(1)
        val parts = ArrayList<Part>(4)
        fun part(name: String): Part {
            for (p in parts) if (p.part == name) return p
            val p = Part(name); parts.add(p); return p
        }
    }

    /** 「显示手柄」每个游戏一份（键 pad.shown.<title id>） */
    private val shownKey = if (titleId.isNullOrEmpty()) KEY_SHOWN else "$KEY_SHOWN.$titleId"

    /** 手柄的设置：自己一份文件（只有游戏页这个进程写，见 PadPrefs） */
    private val padPrefs = PadPrefs(context)

    /** 「按键风格」也是每个游戏一份（键 pad.style.<title id>） */
    private val styleKey = PadStyle.key(titleId)

    /** 当前风格：自带（主题贴图）或透明（简化的半透明形状）。它只改绘制，不动输入 */
    private var styleState: PadStyle = PadStyle.PACK

    /** 布局（宿主给的）与路由（App 自己的） */
    var layout: PadLayout = PadLayout()
        private set
    private val router = PadRouter()
    private var layoutName = "tablet"
    private var layoutVer = -1
    private var layoutW = 0f        // 布局算出来的宿主窗口尺寸
    private var layoutH = 0f
    private var controllers = 0      // 正在使用的物理手柄（有的话，虚拟手柄让位）

    /** 编辑器：控件下标 → 覆盖（拖动中的位置、大小对话框的预览）。改动只留在 App 这里，存盘时才写进宿主 */
    private class Pend(val cx: Float, val cy: Float, val s: Float)
    private val pend = HashMap<Int, Pend>()

    /** 控件表（画的时候用）：缓存着，编辑器的改动与布局重读让它失效 */
    private var controlsCache: List<PadControl>? = null

    /** 编辑器开着吗（画的时候压暗画面，给每个控件画一圈细白框） */
    var editing = false
        private set

    private var themeReady = false
    private var themeAlpha = 1f
    private var themeScale = 1f       // atlas texels per CSS px
    private var themeTravel = 10f     // CSS px
    private var designH = 0f
    private var atlas: Bitmap? = null

    /** 贴图按控件分好组：名字 → 它的贴图（读 theme.tsv 时分好，画的时候不再分） */
    private val themeByCtl = HashMap<String, CtlSp>()
    /** 每个控件（下标）的贴图：与布局里的控件一一对应 */
    private var spriteOf = ArrayList<CtlSp?>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val spPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint()
    private val srcRect = Rect()
    private val dstRect = RectF()

    var shown: Boolean
        get() = padPrefs.bool(shownKey, true)
        set(v) { padPrefs.put(shownKey, v); pushPrefs(); answerControls(); invalidate() }
    var opacity: Int
        get() = padPrefs.str(KEY_OPACITY).toIntOrNull() ?: 60
        set(v) { padPrefs.put(KEY_OPACITY, v.coerceIn(10, 100).toString()); pushPrefs(); invalidate() }

    /**
     * 按键风格：自带（数据包的主题贴图）或默认（app 里的默认主题包）。
     * 换个风格只动绘制的主题状态——路由器的按键状态不碰（不会出现「幽灵按键」）。
     */
    var style: PadStyle
        get() = styleState
        set(v) {
            if (v == styleState) return
            styleState = v
            padPrefs.put(styleKey, v.key)
            applyStyle()
        }

    init {
        // 迁移：旧版本把手柄的设置写在 nx2a 里（那文件主进程也在写，会互相覆盖），
        // 第一次见到这个游戏时把它们搬进手柄自己的文件。
        // --touch 的数据包写着 overlay=0（手柄先藏着），而宿主会重写 settings.txt
        // （那两项不再由它保存），所以第一次见到就由 App 收下
        if (!padPrefs.contains(shownKey)) {
            val old = prefs.prefs
            padPrefs.put(shownKey, when {
                old.contains(shownKey) -> old.getBoolean(shownKey, true)
                old.contains(KEY_SHOWN) -> old.getBoolean(KEY_SHOWN, true)   // 旧版的全局开关
                else -> packSaysHidden()
            })
        }
        if (!padPrefs.contains(KEY_OPACITY)) padPrefs.put(KEY_OPACITY, prefs.str(KEY_OPACITY, "60"))
        // 风格：玩家上次选的那套（没选过或认不出来就是自带）
        styleState = PadStyle.of(padPrefs.str(styleKey))
    }

    /** 数据包的第一份设置里写着 overlay=0 吗（--touch 的游戏：手柄先藏着） */
    private fun packSaysHidden(): Boolean = runCatching {
        val f = pack?.let { File(it, "settings.txt") } ?: return@runCatching false
        if (!f.isFile) return@runCatching false
        f.readLines().any { it.trim().startsWith("overlay=0") }
    }.getOrDefault(false)

    /** 把这两个值告诉宿主（宿主只留运行时的副本） */
    private fun pushPrefs() = runCatching { Native.padPrefs(shown, opacity) }   // 宿主还没载入时跳过

    /** 手柄可见吗（没有物理手柄在用时才画） */
    fun visible(): Boolean = shown && controllers == 0

    /**
     * 手柄回答触摸吗（它回答就是按键，不回答就每一根手指都是游戏的触摸）。
     *
     * 规矩照旧宿主 overlay_layout.h 的 ov_answer：画出来=全部控件回答，藏着=什么都不回答。
     * 物理手柄在用时手柄让位，也是这样（front_player.c 的注释：这时每一根触摸都是游戏的）。
     * 手柄藏起来时它看不见，所以它不能还按着键，也不能还抢触摸。
     */
    private fun answering(): Boolean = visible()

    /** 上一次它回答吗（null = 还没看过：第一次只看，不放键） */
    private var answeringLast: Boolean? = null

    /** 空的控件表：没有控件命中，每一根手指都是游戏的触摸（空列表是个单例，不分配） */
    private val noCtrls = emptyList<PadControl>()

    /**
     * 这一刻该给宿主什么控件：手柄画着就给它全部，藏着就一个都不给
     * （藏着的那一片地方不回答：每一根手指都是游戏的触摸）。
     *
     * 刚藏起来（或刚出现）时：手里按着的键放掉（藏了就没人看得见它按着），
     * 摇杆的量与菜单按钮的按住也跟着放，并且告诉宿主「没有键按着了」
     * （宿主只收 App 给的结果；游戏的触点照旧留着，那是 out 里上一次路由出来的）。
     */
    private fun answerControls(): List<PadControl> {
        val a = answering()
        if (answeringLast != null && a != answeringLast) {
            answeringLast = a
            router.release()
            lastButtons = 0L
            runCatching { Native.padInput(0, 0, 0, 0, 0, out.ids, out.xs, out.ys) }
        }
        answeringLast = a
        return if (a) controls() else noCtrls
    }

    /** 物理手柄的个数变了（App 监听系统的输入设备） */
    fun setControllers(n: Int) {
        if (controllers == n) return
        controllers = n
        answerControls()
        invalidate()
    }

    // ---- 布局：宿主给的，版本或窗口尺寸变了就重读 ----

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        fetchLayout()
        loadTheme()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w > 0 && h > 0 && (w != ow || h != oh)) fetchLayout()   // 尺寸一变（旋转/满屏切换）重拉布局
    }

    /** 布局重读（宿主的版本号或窗口尺寸变了） */
    fun fetchLayout() {
        runCatching {
            val o = JSONObject(Native.padLayout())
            val l = PadLayout.parse(o.toString())
            if (l.w <= 0f || l.h <= 0f) return   // 宿主还没拿到窗口：这份布局没有意义，等下一次
            layoutVer = l.ver
            layoutW = l.w; layoutH = l.h
            if (l.name != layoutName) { layoutName = l.name; loadTheme() }   // 布局换了（旋转跨过 1.9）：图册与精灵跟着换
            layout = l
            controlsCache = null
            buildCtlIndex()
            invalidate()
        }
    }

    /** 控件的名字 → 它在布局里的下标（画的时候不用每帧在列表里找） */
    private fun buildCtlIndex() {
        spriteOf = ArrayList(layout.controls.size)
        for (c in layout.controls) spriteOf.add(themeByCtl[c.name])
    }

    // ---- 触摸：路由，然后把结果送给宿主 ----

    /** 触摸的快照（复用的：一次触摸不分配） */
    private val tIds = IntArray(16)
    private val tXs = FloatArray(16)
    private val tYs = FloatArray(16)
    private val out = PadOutput()

    /**
     * 一次触摸：算出每个手指落在哪个控件上，给游戏按键位、摇杆与触点。
     * 返回 true：这个事件 App 吃掉了（游戏的触点已经由路由分好）。
     */
    fun onTouch(e: MotionEvent): Boolean {
        val n = minOf(e.pointerCount, 16)
        for (i in 0 until n) { tIds[i] = e.getPointerId(i); tXs[i] = e.getX(i); tYs[i] = e.getY(i) }
        // 手柄画着才回答触摸：藏起来的时候它那片地方什么都不回答，每根手指都是游戏的触摸
        // （旧宿主 overlay_layout.h 的 ov_answer：画出来=全部控件回答，藏着=什么都不回答；
        // 物理手柄在用时手柄让位，也是这样）
        val ctrls = answerControls()
        // actionMasked，不是 action：getAction() 给的是「动作 | 手指下标 << 8」的合成值，
        // 第二根手指的 POINTER_DOWN 是 261 而不是 5，路由就认不出来（那样两个键不能一起按）
        router.route(e.actionMasked, e.actionIndex, tIds, tXs, tYs, n, ctrls, height.toFloat(), out)
        // 菜单按钮：拖动就是挪按钮（立刻存进宿主的布局），点按就是开抽屉
        if (router.menuPointer >= 0) {
            val i = tIds.indexOf(router.menuPointer)
            if (i >= 0 && router.menuMoved(tXs[i], tYs[i], height.toFloat())) {
                val c = ctrls.indexOfFirst { it.isMenu }
                if (c >= 0) {
                    val pos = router.menuPosition(tXs[i], tYs[i], ctrls[c], width.toFloat(), height.toFloat())
                    runCatching {
                        Native.padLayoutSet(c, pos.first, pos.second, ctrls[c].s)
                        Native.padLayoutSave()
                    }
                    fetchLayout()
                }
            }
        }
        if (router.takeMenuTap()) onMenuTapped()
        runCatching {
            Native.padInput(out.buttons, out.lx, out.ly, out.rx, out.ry, out.ids, out.xs, out.ys)
        }
        // 按下的那一下震：新的键被按住就震一下（旧宿主也是这样：dm_haptics_android_tap）
        if ((out.buttons and lastButtons.inv()) != 0L) tapVibration()
        lastButtons = out.buttons
        invalidate()
        return true
    }

    /** 上一次路由出来的按键位（拿来算「哪个键是刚按下的」） */
    private var lastButtons = 0L

    /**
     * 按键按下的那一下短震：照旧宿主 haptics_android.c 的 vib_tap（14 ms、振幅 140；
     * 机器没有振幅控制就打系统的点击效果）。系统自己的触感反馈（performHapticFeedback）跟随
     * 「触摸振动」这个设置，很多机器上根本不震，所以这里直接用 Vibrator。
     */
    private fun tapVibration() {
        val v = vibrator ?: return
        if (!rumbleOn()) return
        runCatching {
            when {
                v.hasAmplitudeControl() -> v.vibrate(VibrationEffect.createOneShot(TAP_MS.toLong(), TAP_AMP))
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                else -> @Suppress("DEPRECATION") v.vibrate(TAP_MS.toLong())
            }
        }
    }

    /** 抽屉里的「震动」开关：它也是宿主自己的设置（旧宿主给按键 tick 用的就是它） */
    private fun rumbleOn(): Boolean = runCatching { Native.menuGet(2) != 0 }.getOrDefault(true)

    /** 机器的振动器（API 31 起走 VibratorManager） */
    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }.getOrNull()

    /** 点按了菜单按钮（App 开自己的抽屉） */
    var onMenuTapped: () -> Unit = {}

    /** 画哪些控件（编辑器开着时用改动过的那一份） */
    fun controls(): List<PadControl> {
        controlsCache?.let { return it }
        val base = layout.controls
        val outList: List<PadControl> = if (pend.isEmpty()) base else {
            val l = ArrayList<PadControl>(base.size)
            for ((i, c) in base.withIndex()) {
                val p = pend[i]
                l += if (p == null) c else padResized(c, p.s, layout.scale, p.cx, p.cy)
            }
            l
        }
        controlsCache = outList
        return outList
    }

    // ---- 编辑器：改动记在 App 这里，存盘时写进宿主的布局 ----

    fun openEditor() {
        editing = true
        pend.clear()
        controlsCache = null
        invalidate()
    }

    fun closeEditor() {
        editing = false
        pend.clear()
        controlsCache = null
        invalidate()
    }

    /** 拖动一个控件（窗口高度为单位）：只改 App 这里的画面 */
    fun moveControl(ctl: Int, cx: Float, cy: Float) {
        val c = layout.controls.getOrNull(ctl) ?: return
        val p = pend[ctl]
        pend[ctl] = Pend(cx, cy, p?.s ?: c.s)
        controlsCache = null
        invalidate()
    }

    /** 松手：把位置写进宿主并存进数据包的 settings.txt */
    fun dropControl(ctl: Int, cx: Float, cy: Float) {
        val c = layout.controls.getOrNull(ctl) ?: return
        val s = pend[ctl]?.s ?: c.s
        writeLayout(ctl, cx, cy, s)
        pend.remove(ctl)
        controlsCache = null
        fetchLayout()
    }

    /** 大小对话框：滑杆拖着的时候先看看多大（只改 App 这里的画面） */
    fun previewSize(ctl: Int, size: Float) {
        val c = layout.controls.getOrNull(ctl) ?: return
        val p = pend[ctl]
        pend[ctl] = Pend(p?.cx ?: c.cx, p?.cy ?: c.cy, size)
        controlsCache = null
        invalidate()
    }

    /** 大小对话框：确定，把大小写进宿主并存进数据包的 settings.txt */
    fun commitSize(ctl: Int, size: Float) {
        val c = layout.controls.getOrNull(ctl) ?: return
        val p = pend[ctl]
        writeLayout(ctl, p?.cx ?: c.cx, p?.cy ?: c.cy, size)
        pend.remove(ctl)
        controlsCache = null
        fetchLayout()
    }

    /** 大小对话框：取消，回到原来的大小 */
    fun cancelPreview(ctl: Int) {
        if (pend.remove(ctl) == null) return
        controlsCache = null
        invalidate()
    }

    /** 写进宿主：位置是窗口的分数（横向按宽高比换一下），大小是系数 */
    private fun writeLayout(ctl: Int, cx: Float, cy: Float, size: Float) {
        val aspect = if (layout.w > 0f && layout.h > 0f) layout.w / layout.h else 1f
        runCatching {
            Native.padLayoutSet(ctl, cx / aspect, cy, size)
            Native.padLayoutSave()
        }
    }

    /** 恢复默认（布局自己放的地方与大小）：宿主把存着的偏移清掉，控件回到布局给的位置 */
    fun resetControls() {
        pend.clear()
        controlsCache = null
        runCatching { Native.padLayoutReset(); Native.padLayoutSave() }
        fetchLayout()
        invalidate()
    }

    // ---- 主题：数据包的 overlay/theme.tsv + 图册（与宿主主题同源数据）----

    /** 换风格：只动绘制的主题状态，输入状态不碰 */
    private fun applyStyle() {
        loadTheme()
        invalidate()
    }

    /** 丢掉主题：没有主题可读时绘制走矢量退路 */
    private fun clearTheme() {
        atlas = null
        themeReady = false
        themeAlpha = 1f
        themeByCtl.clear()
        buildCtlIndex()
    }

    /** 当前风格的主题从哪来：自带 = 数据包的 overlay/，默认 = app 里的默认主题包 */
    private fun themeSource(): ThemeSource? = when (styleState) {
        PadStyle.PACK -> pack?.let { p ->
            ThemeSource(
                text = { File(p, "overlay/theme.tsv").takeIf { it.isFile }?.readText() },
                atlas = { name -> BitmapFactory.decodeFile(File(File(p, "overlay"), name).absolutePath) },
            )
        }
        PadStyle.DEFAULT -> ThemeSource(
            text = {
                runCatching {
                    this.context.assets.open("$DEFAULT_THEME_DIR/theme.tsv").use { it.readBytes().toString(Charsets.UTF_8) }
                }.onFailure { android.util.Log.w(TAG, "the default theme pack is not in the assets ($DEFAULT_THEME_DIR): $it") }.getOrNull()
            },
            atlas = { name ->
                runCatching {
                    this.context.assets.open("$DEFAULT_THEME_DIR/$name").use { BitmapFactory.decodeStream(it) }
                }.onFailure { android.util.Log.w(TAG, "the default theme pack has no $name: $it") }.getOrNull()
            },
        )
    }

    /** 读主题：自带读数据包自带的 overlay/，默认读 app 里的默认主题包；都没有就走矢量退路 */
    private fun loadTheme() {
        clearTheme()
        val src = themeSource() ?: return
        runCatching {
            val tsv = src.text() ?: return@runCatching
            var atlasName: String? = null
            val groups = HashMap<String, CtlSp>()
            var dw = 0f; var dh = 0f
            for (raw in tsv.split('\n')) {
                val line = raw.trimEnd('\r')
                if (line.isEmpty() || line.startsWith("#")) continue
                val c = line.split('\t')
                when {
                    c.size >= 2 && c[0] == "alpha" -> themeAlpha = c[1].toFloatOrNull() ?: 1f
                    c.size >= 7 && c[0] == "layout" -> if (c[1] == layoutName) {
                        atlasName = c[4]; dh = c[3].toFloatOrNull() ?: 0f
                        themeScale = c[5].toFloatOrNull() ?: 1f; themeTravel = c[6].toFloatOrNull() ?: 10f
                    }
                    c.size >= 10 && c[0] == "sprite" && c[1] == layoutName -> {
                        // sprite <layout> <control> <part> <state> <atlas x> <y> <w> <h> <ox> <oy>
                        val set = groups.getOrPut(c[2]) { CtlSp() }
                        val sp = Sp(c[3], c[4], c[5].toIntOrNull() ?: 0, c[6].toIntOrNull() ?: 0,
                                    c[7].toIntOrNull() ?: 0, c[8].toIntOrNull() ?: 0,
                                    c[9].toFloatOrNull() ?: 0f, c.getOrNull(10)?.toFloatOrNull() ?: 0f)
                        when (sp.state) {
                            "decor" -> set.decor.add(sp)
                            "knob" -> set.knob.add(sp)
                            "idle" -> set.part(sp.part).idle = sp
                            "held" -> set.part(sp.part).held = sp
                        }
                    }
                }
            }
            val name = atlasName ?: return@runCatching
            val bmp = src.atlas(name) ?: return@runCatching
            atlas = bmp; designH = dh
            themeByCtl.clear(); themeByCtl.putAll(groups)
            themeReady = groups.isNotEmpty() && dh > 0
            buildCtlIndex()
        }
    }

    private fun label(n: String) = LABELS[n] ?: n.uppercase()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val ctrls = controls()
        if (editing) {
            // 编辑态：画面压暗（游戏本来就等着），手柄照常画，每个控件一圈细白框提示它的边界
            dimPaint.color = Color.argb(76, 0, 0, 0)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            drawPad(canvas, ctrls)
            drawFrames(canvas, ctrls)
            return
        }
        if (!visible() || ctrls.isEmpty()) return
        drawPad(canvas, ctrls)
    }

    /** 手柄本身：主题贴图（没有主题时退回矢量） */
    private fun drawPad(canvas: Canvas, ctrls: List<PadControl>) {
        val H = height.toFloat()
        val a = (opacity / 100f) * themeAlpha
        text.textSize = H * 0.022f
        val pressed = router.pressedControls()

        val atlasB = atlas
        if (themeReady && atlasB != null) {
            // 主题贴图：精灵矩形在图册上，位置 = 控件中心（窗口高度 × 视图高度）+ 偏移（CSS px × 缩放）。
            // 缩放与宿主 ov_tsp 一样：窗口高 / 设计高 × 密度系数 × 控件自己的大小系数——少了后两个，
            // 贴图就比命中框小，按键看着没变大
            val kBase = (H / designH) * layout.scale
            spPaint.alpha = (255 * a).toInt().coerceIn(0, 255)
            // 装饰：在别的下面（宿主 ov_theme_draw 也是先画装饰）
            for ((i, c) in ctrls.withIndex()) {
                val set = spriteOf.getOrNull(i) ?: continue
                for (s in set.decor) drawSp(canvas, atlasB, s, c, kBase * c.s, 0f, 0f)
            }
            // 控件：一个一个来（布局的顺序）
            for ((i, c) in ctrls.withIndex()) {
                val set = spriteOf.getOrNull(i) ?: continue
                val kc = kBase * c.s
                // 摇杆的帽：跟着手指走（宿主 ov_theme_draw 乘的是 travel × 自己的缩放）
                for (s in set.knob) {
                    val ki = if (c.name == "stick_r") 2 else 0
                    drawSp(canvas, atlasB, s, c, kc,
                           router.knobs[ki] * themeTravel * kc, router.knobs[ki + 1] * themeTravel * kc)
                }
                // 按键自己：按住了画按住的那张（十字键：按着哪个方向就亮哪个方向，斜向亮两个）
                for (p in set.parts) {
                    val on = if (c.isDpad) p.dirBit != 0L && (router.dpadDir and p.dirBit) != 0L
                             else pressed.contains(i)
                    val s = if (on) (p.held ?: p.idle) else p.idle
                    if (s != null) drawSp(canvas, atlasB, s, c, kc, 0f, 0f)
                }
            }
            return
        }

        // 矢量退路（包里没有主题时）
        for ((i, c) in ctrls.withIndex()) {
            val cx = c.cx * H; val cy = c.cy * H; val rr = c.hw * H
            val held = pressed.contains(i)
            fill.color = Color.argb(((if (held) 55 else 25) * a).toInt(), 255, 255, 255)
            canvas.drawCircle(cx, cy, rr, fill)
            line.color = Color.argb((190 * a).toInt(), 255, 255, 255); line.strokeWidth = 2.5f
            canvas.drawCircle(cx, cy, rr, line)
            text.color = Color.argb((220 * a).toInt(), 255, 255, 255)
            canvas.drawText(label(c.name), cx, cy + text.textSize / 3, text)
        }
    }

    /** 一个贴图：源矩形在图册上，目标矩形在控件中心加偏移的地方（矩形是复用的，不分配） */
    private fun drawSp(canvas: Canvas, atlasB: Bitmap, s: Sp, c: PadControl, k: Float, ox: Float, oy: Float) {
        val vw = s.aw / themeScale * k; val vh = s.ah / themeScale * k
        val dx = s.ox * k + ox; val dy = s.oy * k + oy
        val cx = c.cx * height + dx; val cy = c.cy * height + dy
        srcRect.set(s.ax, s.ay, s.ax + s.aw, s.ay + s.ah)
        dstRect.set(cx, cy, cx + vw, cy + vh)
        canvas.drawBitmap(atlasB, srcRect, dstRect, spPaint)
    }

    /**
     * 编辑器里的框：每个控件一圈细白框，提示它的边界（宿主从前也是这样画的：ov_render 的 edit 分支，
     * SP_WHITE 细框，透明度 0.35）。
     */
    private fun drawFrames(canvas: Canvas, ctrls: List<PadControl>) {
        val H = height.toFloat()
        val t = max(2f, H * 0.003f)
        line.color = Color.argb(90, 255, 255, 255)
        line.strokeWidth = t
        for (c in ctrls) {
            val cx = c.cx * H; val cy = c.cy * H
            val hw = c.hw * H; val hh = c.hh * H
            canvas.drawRect(cx - hw, cy - hh, cx + hw, cy + hh, line)
        }
    }
}
