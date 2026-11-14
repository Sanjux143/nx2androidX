package com.nx.android.game

import android.app.Activity
import android.content.Intent
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.annotation.SuppressLint
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.nx.android.NxApp
import com.nx.android.R
import com.nx.android.data.AppLocale
import com.nx.android.data.FrameGenSettings
import com.nx.android.data.LosslessDll
import com.nx.android.data.PrefsStore
import com.nx.android.data.RatePin
import com.nx.android.data.RateSettings
import com.nx.android.data.drivers.Drivers
import com.nx.android.data.playback.PackEnvStore
import com.nx.android.game.hud.HudField
import com.nx.android.core.ui.ProvideAppLocale
import com.nx.android.game.overlay.GameDrawer
import com.nx.android.game.hud.HudOverlay
import com.nx.android.game.input.PadEditor
import com.nx.android.game.input.PadView
import com.nx.android.game.overlay.TextPrompt
import com.nx.android.core.ui.theme.NxTheme
import org.json.JSONObject

/**
 * 音量键归 Android：游戏的音频走媒体音量组（AAUDIO_USAGE_GAME），系统拿不到这些键就永远调不了音量
 * （音量条不出现，游戏的声音也一点不变）。其余按键照旧交给游戏。
 */
internal fun forwardsKeyToGame(keyCode: Int): Boolean =
    keyCode != KeyEvent.KEYCODE_VOLUME_UP &&
        keyCode != KeyEvent.KEYCODE_VOLUME_DOWN &&
        keyCode != KeyEvent.KEYCODE_VOLUME_MUTE

/**
 * One game in this process (":play"): the host (libdmplay.so, the copy the library made in the app's private
 * folder) is loaded here and started with the pack folder, the SurfaceView's surface goes to the host's Vulkan
 * swapchain and every input event is forwarded as the snapshot the shared core reads (jni_android.h). The
 * drawer (Compose, over the surface) holds the game's live switches — the frame generation among them; Back
 * stays with the game's own pause menu. The host ends the process when the game ends (one game per process),
 * and [endProcess] kills it too — a started host cannot be started again in the same process, so a recreated
 * activity of a running game only re-hands its window ([hostRunning]).
 */
class GameActivity : ComponentActivity(), SurfaceHolder.Callback {

    companion object {
        const val EXTRA_PACK = "pack"          // the data pack folder (the port's output, shared storage)
        const val EXTRA_HOST = "host"          // the game's libdmplay.so in the app's private folder
        const val EXTRA_TITLE_ID = "title_id"  // keeps the saves, the temp and the log apart per game
        const val EXTRA_NAME = "name"
        const val EXTRA_EXIT = "exit"

        /** a start already happened in this process: a recreated activity takes the running game's window over */
        @Volatile var hostRunning = false

        /**
         * ends a running game from the app side (the intent lands in onNewIntent).
         *
         * CLEAR_TOP|SINGLE_TOP：命中任务里**已有的**那一个游戏页，把它上面的 finish 掉。**不能**用
         * FLAG_ACTIVITY_NEW_TASK——那会让系统按 affinity 另找位置，目标不在栈顶时**新建**一个游戏页
         * 实例；新实例在 onCreate 里撞上 hostRunning 直接早退，结果是一个不接管窗口、没有输入的黑屏。
         */
        fun stop(activity: Activity) {
            val i = Intent(activity, GameActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_EXIT, true)
            activity.startActivity(i)
        }

        /** the game again (back from the app): the running page comes to the front, no new one is made */
        fun resume(activity: Activity) {
            activity.startActivity(
                Intent(activity, GameActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    /** 界面语言：toast 与抽屉里的词从这里取 */
    private val locale: AppLocale get() = (application as NxApp).locale

    private var paused by mutableStateOf(false)
    private var drawerOpen by mutableStateOf(false)
    private var fgSettings: FrameGenSettings? = null
    private var rateSettings: RateSettings? = null
    private var packPath: String? = null
    private lateinit var padView: PadView

    // 性能 HUD：两条线与它显示哪些字段（App 的偏好，重启后仍在）
    private var hudStats by mutableStateOf(false)
    private var hudDevice by mutableStateOf(false)
    private var hudFields by mutableStateOf(HudField.DEFAULT)
    private var hudFahrenheit by mutableStateOf(false)

    /** 游戏要文字时（宿主的通知 3）：App 用系统输入法问玩家 */
    private data class TextReq(val title: String, val text: String, val max: Int)
    private var textReq by mutableStateOf<TextReq?>(null)

    /** 布局编辑器开着吗（App 自己的面板：游戏等着，触摸归编辑器） */
    private var editorOpen by mutableStateOf(false)

    // ---- 物理手柄：App 监听系统的输入设备，告诉宿主有几个在用（宿主不再自己轮询设备列表）----
    private val inputManager: InputManager get() = getSystemService(InputManager::class.java)
    private val ctrlDevices = mutableSetOf<Int>()   // 真正发过输入的手柄（发过才算在用）
    private var ctrlListener: InputManager.InputDeviceListener? = null
    private var ctrlCount = -1

    private fun isGamepad(deviceId: Int): Boolean {
        val d = inputManager.getInputDevice(deviceId) ?: return false
        if (d.isVirtual) return false   // 虚拟设备（小米常驻的 uinput-xiaomi 之类）：它说是手柄，却从不发输入
        val s = d.sources
        return (s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
               (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    /** 一个手柄发了输入：它算「在用」，虚拟手柄不算（老前端也是这个规矩：issue #22） */
    private fun noteController(source: Int, deviceId: Int) {
        if ((source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK)) == 0) return
        if (!isGamepad(deviceId)) return
        if (ctrlDevices.add(deviceId)) pushControllers()
    }

    private fun pushControllers() {
        val n = ctrlDevices.size
        if (n == ctrlCount) return
        ctrlCount = n
        padView.setControllers(n)   // 有手柄在用：虚拟手柄让位
        runCatching { Native.padDevices(n) }   // 宿主还没载入时跳过
    }

    private fun watchControllers() {
        val l = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(id: Int) { pushControllers() }   // 接上了不算在用：它得先发输入
            override fun onInputDeviceRemoved(id: Int) { if (ctrlDevices.remove(id)) pushControllers() }
            override fun onInputDeviceChanged(id: Int) { pushControllers() }
        }
        ctrlListener = l
        runCatching { inputManager.registerInputDeviceListener(l, Handler(Looper.getMainLooper())) }
    }

    private fun immersive() {
        window.decorView   // the decor must exist: the insets controller hangs off it (a plain Activity has none until it is asked for)
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else @Suppress("DEPRECATION") window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val frame = FrameLayout(this)
        val surface = SurfaceView(this)
        surface.holder.addCallback(this)
        frame.addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // 虚拟手柄绘制层（android-virtual-pad）：画在游戏画面之上、抽屉之下；不消费触点——命中与分流在宿主
        packPath = intent.getStringExtra(EXTRA_PACK)
        val titleId = intent.getStringExtra(EXTRA_TITLE_ID)?.ifEmpty { null }
        padView = PadView(this, PrefsStore(this), packPath?.let { java.io.File(it) }, titleId)
        padView.onMenuTapped = { runOnUiThread { drawerOpen = !drawerOpen } }   // ☰ 按钮：App 开自己的抽屉
        frame.addView(padView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // the drawer's layer: a ComposeView over the game's surface (the composition needs the activity's owners on the view)
        val compose = ComposeView(this)
        compose.setViewTreeLifecycleOwner(this)
        compose.setViewTreeViewModelStoreOwner(this)
        compose.setViewTreeSavedStateRegistryOwner(this)
        compose.setContent { NxTheme { ProvideAppLocale(locale) { DrawerRoot() } } }
        frame.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(frame)
        immersive()
        if (hostRunning) return   // a recreation (the system rebuilt the activity): the running game takes the new window in surfaceCreated
        if (intent.getBooleanExtra(EXTRA_EXIT, false)) return endProcess()
        val pack = intent.getStringExtra(EXTRA_PACK)
        val host = intent.getStringExtra(EXTRA_HOST)
        if (pack.isNullOrEmpty() || host.isNullOrEmpty()) { toast(locale.str(R.string.game_no_game_to_run)); endProcess(); return }
        if (com.nx.android.data.library.LibraryFileScanner.portFromPack(java.io.File(pack))?.runnable != true) {
            toast(locale.str(R.string.game_needs_report))
            endProcess()
            return
        }
        try {
            System.load(host)
        } catch (t: Throwable) {
            toast(locale.str(R.string.game_cannot_load_host, t.message) + "\n" + locale.str(R.string.runtime_play_failed))
            endProcess()
            return
        }
        // the title id keeps this game's saves, temp and log apart from the others' (host_start_common)
        fgSettings = titleId?.let { FrameGenSettings(PrefsStore(this), it) }
        rateSettings = titleId?.let { RateSettings(PrefsStore(this), it) }
        // the default driver: a game whose driver was never picked runs on the app's built-in one (the row goes
        // into the pack's env.txt, which the host reads). A driver the player picked is left alone.
        applyDefaultDriver(java.io.File(pack))
        // the performance HUD's own preferences: the two lines, the fields, the temperature's unit
        val prefs = PrefsStore(this)
        hudStats = prefs.bool(PrefsStore.HUD_STATS, false)
        hudDevice = prefs.bool(PrefsStore.HUD_DEVICE, false)
        hudFields = HudField.of(prefs.str(PrefsStore.HUD_FIELDS, "").split(",").filter { it.isNotBlank() })
            .ifEmpty { HudField.DEFAULT }
        hudFahrenheit = prefs.bool(PrefsStore.HUD_TEMP_F, false)
        val env = fgSettings?.load()?.envLines(LosslessDll.ensure(this)?.absolutePath) ?: emptyArray()
        if (!Native.start(pack, filesDir.path, titleId, resources.displayMetrics.densityDpi, env, this)) {
            endProcess()   // the save import asked to end: it happens at the next start
            return
        }
        hostRunning = true
        // 手柄的显示与浓淡：App 的偏好是唯一真值，宿主只留运行时的副本（settings.txt 不再写这两项）
        runCatching { Native.padPrefs(padView.shown, padView.opacity) }
        // 玩家选的帧率档位：start 之后才下发——包的 env.txt 在宿主启动时读过，晚 setenv 的那份留下来，
        // 玩家的选择盖过档案自己的 DM_FPS_PIN。没选过就不下发，档案的默认继续说话。
        rateSettings?.load()?.let { pin -> runCatching { Native.perfPinSet(pin.fps) } }
        watchControllers()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /**
     * The default driver: the app's built-in one (the driver it ships), written into the pack when the player
     * never picked a driver for this game. The row is written once — a driver the player picked (the device's own
     * one included) stays what it is.
     */
    private fun applyDefaultDriver(pack: java.io.File) {
        val builtIn = Drivers.list(this).firstOrNull { it.shipped } ?: return
        PackEnvStore.ensureDriverDefault(pack, builtIn.library.absolutePath)
    }

    // ---- 性能 HUD 的开关与字段（App 的偏好，唯一真值）----

    private fun saveHudStats(on: Boolean) { hudStats = on; PrefsStore(this).put(PrefsStore.HUD_STATS, on) }
    private fun saveHudDevice(on: Boolean) { hudDevice = on; PrefsStore(this).put(PrefsStore.HUD_DEVICE, on) }
    private fun saveHudFields(fields: Set<HudField>) {
        hudFields = fields
        PrefsStore(this).put(PrefsStore.HUD_FIELDS, HudField.keys(fields))
    }
    private fun saveHudFahrenheit(on: Boolean) { hudFahrenheit = on; PrefsStore(this).put(PrefsStore.HUD_TEMP_F, on) }

    /**
     * 帧率档位：App 的偏好是唯一真值（按游戏记着），宿主那边只是运行时的副本——
     * 钉住一档（60/40/30）或放它自由自适应（0），即时生效（perf_android.c 的 fps_pin 每 30 帧重读一次）。
     */
    private fun saveRatePin(pin: RatePin) {
        rateSettings?.save(pin)
        runCatching { Native.perfPinSet(pin.fps) }
    }

    /**
     * 宿主的上行通知（front_player.c 的 dm_app_notice）：宿主不知道抽屉，它只说发生了什么。
     * 1 = 玩家点了 ☰（轻点不是拖动）→ 抽屉开关；2 = 宿主打开了自己的面板（文本输入/布局编辑器/存档选择）
     * → 抽屉收起，面板画在游戏画面上，不被抽屉盖住；3 = 游戏要文字 → App 用系统输入法问玩家。
     * 通知可能来自宿主的任何线程（触摸那条在主线程，文本输入那条在游戏的线程上），状态只在主线程上改。
     */
    fun onHostNotice(what: Int) {
        runOnUiThread {
            when (what) {
                1 -> drawerOpen = !drawerOpen
                2 -> drawerOpen = false
                3 -> textReq = readTextRequest()
            }
        }
    }

    /** 游戏要问的内容（宿主那边挂着，等着 App 的回答） */
    private fun readTextRequest(): TextReq? = runCatching {
        val o = JSONObject(Native.textRequest())
        TextReq(o.optString("title"), o.optString("text"), o.optInt("max", 0))
    }.getOrNull()

    /** 玩家的回答：文字交回宿主，游戏的线程继续 */
    private fun submitText(ok: Boolean, text: String) {
        textReq = null
        runCatching { Native.textResult(ok, text) }
    }

    // ---- 布局编辑器（App 自己的面板）：游戏等着，触摸归编辑器 ----

    private fun openEditor() {
        drawerOpen = false
        editorOpen = true
        padView.openEditor()
        runCatching { Native.hold(true) }   // 游戏先等着（时钟不走，声音静下来）
    }

    /** 关闭编辑器：改动已经存好了（拖动松手就存，大小对话框确定才存），这里只是收尾 */
    private fun closeEditor() {
        editorOpen = false
        padView.closeEditor()
        runCatching { Native.hold(false) }
    }

    /** 恢复默认布局：控件回到布局自己放的地方与大小（存进数据包的 settings.txt） */
    private fun resetPadLayout() {
        padView.resetControls()
    }

    /** the drawer's tree: the HUD, the panel, and the paused overlay (the surface keeps presenting through it) */
    @androidx.compose.runtime.Composable
    private fun DrawerRoot() {
        Box(Modifier.fillMaxSize()) {
            if (paused) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null) { paused = false; Native.lifecycle(1) },
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) { androidx.compose.material3.Text(locale.str(R.string.drawer_paused_overlay), color = Color.White,
                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge) }
            }
            // 性能 HUD：画在游戏画面之上（App 自己画，宿主不再往画面里画东西）；顶中，避开刘海与挖孔。
            // 它排在抽屉之前：抽屉是前景的面板，HUD 该在它下面——否则读数会压在抽屉的顶上。
            HudOverlay(
                enabled = hudStats,
                deviceLine = hudDevice,
                fields = hudFields,
                fahrenheit = hudFahrenheit,
                engineLabel = { fgSettings?.load()?.let { "LSFG ${it.mult}x" } },
                chargingText = locale.str(R.string.hud_charging),
                packPath = packPath,
                modifier = Modifier.align(Alignment.TopCenter).displayCutoutPadding().padding(top = 8.dp),
            )
            GameDrawer(
                paused = paused,
                open = drawerOpen,
                onOpenChange = { drawerOpen = it },
                settings = fgSettings,
                onRatePin = { saveRatePin(it) },
                padShown = { padView.shown },
                onPadShown = { padView.shown = it },
                padOpacity = { padView.opacity },
                onPadOpacity = { padView.opacity = it },
                padStyle = { padView.style },
                onPadStyle = { padView.style = it },
                hudStats = { hudStats },
                onHudStats = { saveHudStats(it) },
                hudDevice = { hudDevice },
                onHudDevice = { saveHudDevice(it) },
                hudFields = { hudFields },
                onHudFields = { saveHudFields(it) },
                hudFahrenheit = { hudFahrenheit },
                onHudFahrenheit = { saveHudFahrenheit(it) },
                onLayoutEditor = { openEditor() },
                onResetPadLayout = { resetPadLayout() },
                // 暂停/继续：游戏原地停/起，抽屉不收——玩家可以一边停着一边调设置
                onPauseGame = {
                    paused = true
                    Native.lifecycle(0)
                },
                onResumeGame = {
                    paused = false
                    Native.lifecycle(1)
                },
                onExit = {
                    drawerOpen = false
                    endProcess()
                },
            )
            // 布局编辑器：App 自己的面板，画在游戏画面上（游戏先等着）
            if (editorOpen) {
                PadEditor(
                    controls = { padView.controls() },
                    onDrag = { c, cx, cy -> padView.moveControl(c, cx, cy) },
                    onDrop = { c, cx, cy -> padView.dropControl(c, cx, cy) },
                    onSizePreview = { c, s -> padView.previewSize(c, s) },
                    onSizeCommit = { c, s -> padView.commitSize(c, s) },
                    onSizeCancel = { c -> padView.cancelPreview(c) },
                    onDone = { closeEditor() },
                )
            }
            // 游戏的文本输入：系统的输入法（宿主那边挂起游戏等着）
            textReq?.let { req ->
                key(req.title + req.text) {
                    TextPrompt(req.title, req.text, req.max,
                        onOk = { submitText(true, it) },
                        onCancel = { submitText(false, "") })
                }
            }
        }
    }

    // ---- the surface: the window passes over owned, and dropping it destroys the swapchain before it returns ----
    override fun surfaceCreated(h: SurfaceHolder) { Native.setSurface(h.surface); padView.fetchLayout() }
    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) { Native.setSurface(h.surface); padView.fetchLayout() }
    override fun surfaceDestroyed(h: SurfaceHolder) = Native.setSurface(null)

    // ---- input: the touches are the app's now (it works out what they hit); the keys go to the host ----
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (editorOpen) return true   // 编辑器开着：触摸归编辑器
        padView.onTouch(e)
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        noteController(e.source, e.deviceId)   // 手柄动了：它算「在用」，虚拟手柄让位
        Native.motion(e.source, e.deviceId, e.action, IntArray(0), FloatArray(0), FloatArray(0), axes(e))
        return true
    }

    private fun axes(e: MotionEvent): FloatArray = floatArrayOf(
        e.getAxisValue(MotionEvent.AXIS_X), e.getAxisValue(MotionEvent.AXIS_Y), e.getAxisValue(MotionEvent.AXIS_Z), e.getAxisValue(MotionEvent.AXIS_RZ),
        e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_HAT_X), e.getAxisValue(MotionEvent.AXIS_HAT_Y),
        e.getAxisValue(MotionEvent.AXIS_BRAKE), e.getAxisValue(MotionEvent.AXIS_GAS))

    /**
     * everything is consumed here (as the NativeActivity's queue did) — except the volume keys, which belong to
     * Android (the game's audio is in the media volume group, so the system's volume bar is the game's volume).
     * Back goes to the frontmost thing:
     * the drawer closes it, the host's own panel (text entry, layout editor, save picker) steps back in it
     * (dm_menu_state: -1 closed, else a panel is up), and with nothing up Back opens the drawer.
     * 游戏暂停时也一样：暂停遮罩吃掉了所有触摸（菜单按钮点不到），Back 是那时唯一能叫出抽屉的入口。
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (!forwardsKeyToGame(e.keyCode)) return false   // 音量键：系统的事，游戏与宿主都不沾
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_DOWN) {
                val hostPanel = !drawerOpen && hostPanelUp()   // 抽屉在前面时，Back 归抽屉
                when {
                    editorOpen -> closeEditor()   // 编辑器：Back 是收工（改动早就存好了）
                    drawerOpen -> drawerOpen = false
                    hostPanel -> Native.key(e.deviceId, e.source, e.keyCode, e.action, e.metaState, e.repeatCount)   // 宿主的面板在后退一步
                    else -> drawerOpen = true
                }
            }
            return true   // Back 只在宿主的面板开着时交给宿主
        }
        if (e.action == KeyEvent.ACTION_DOWN || e.action == KeyEvent.ACTION_UP) {
            noteController(e.source, e.deviceId)   // 手柄按了键：它算「在用」，虚拟手柄让位
            Native.key(e.deviceId, e.source, e.keyCode, e.action, e.metaState, e.repeatCount)
        }
        return true
    }

    /** 宿主自己的面板开着吗（文本输入、布局编辑器、存档选择；dm_menu_state: -1 是关着的） */
    private fun hostPanelUp(): Boolean =
        runCatching { Native.menuState().substringBefore(' ').toIntOrNull() ?: -1 }.getOrDefault(-1) != -1

    // ---- lifecycle: the same dance as the NativeActivity's callbacks ----
    override fun onResume() { super.onResume(); Native.lifecycle(1); padView.fetchLayout() }

    /** The phone's language changed (configChanges holds it): the app's words are read again */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        locale.configurationChanged()
    }
    override fun onPause() { super.onPause(); Native.lifecycle(0) }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
        Native.lifecycle(if (hasFocus) 2 else 3)
    }

    override fun onDestroy() {
        ctrlListener?.let { runCatching { inputManager.unregisterInputDeviceListener(it) } }
        ctrlListener = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_EXIT, false)) endProcess()
    }

    /**
     * 这一页没了，进程也走：宿主在一个进程里只能跑一次（见类注释）。
     * 游戏没起来也一样要把进程带走——只结束页面不结束进程，会留下一个「有进程没游戏」的幽灵，
     * 库页会以为游戏还在跑（点「运行」就会去回到一个已经不在的页面）。
     */
    private fun endProcess() {
        finish()
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 300)
    }
}
