package com.nx.android.input.ui

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nx.android.input.GamepadScan
import com.nx.android.input.PadCalibration
import com.nx.android.input.PadCaptureSession
import com.nx.android.input.PadMapping
import com.nx.android.input.PadProfileKey
import com.nx.android.input.PadProfileStore
import com.nx.android.input.PadSources
import java.io.File

/**
 * 手柄中心的状态：档案、当前看哪只手柄、抓键与撞车。
 *
 * 中心在两处开（首页的顶栏入口、游戏里的抽屉），状态放在这里，两处共用一份：
 * 档案写的是同一个文件（每只手柄一份，存在 App 自己的目录里），游戏读到的也是它。
 *
 * 改完档案 [onChanged] 会被叫一次：游戏里那次要把新表推给宿主（改完即时生效，不用重启游戏）；
 * 首页那次没有宿主可推，存下就好（游戏下次起来自己读）。
 */
class PadCenterController(
    private val store: PadProfileStore,
    private val onChanged: (Map<String, PadProfileStore.Profile>) -> Unit = {},
    private val devices: () -> List<GamepadScan.Info> = { GamepadScan.devices.value },
    private val now: () -> Long = { SystemClock.uptimeMillis() },
) {

    /** 撞车的那个绑定，等玩家选：替换、交换还是取消。 */
    data class Conflict(
        val deviceId: Int,
        val source: String,      // 玩家刚按下的那个物理键
        val target: String,      // 他想绑的游戏键
        val otherSource: String?,   // 原来占着这个目标键的物理键
        val otherTarget: String?,   // 玩家按下的那个键原来绑着什么
    )

    private val loaded = store.load()

    /** 每只手柄一份档案（键 → 档案）。 */
    var profiles by mutableStateOf(loaded.profiles)
        private set

    /** 档案坏了、被救回来的：面板上要说一句（玩家的改动可能没在）。 */
    var recovered by mutableStateOf(loaded.recoveredFromCorruption)
        private set

    /** 面板上正看着哪只手柄。 */
    var selectedId by mutableStateOf<Int?>(null)
        private set

    /** 正在等玩家按下的那个游戏键（改键时）。 */
    var capturing by mutableStateOf<String?>(null)
        private set

    /** 撞车了，等玩家选。 */
    var conflict by mutableStateOf<Conflict?>(null)
        private set

    private val capture = PadCaptureSession()
    private var captureDeviceId: Int? = null

    /** 这只手柄的档案（没有就是空的那一份）。 */
    fun profile(info: GamepadScan.Info): PadProfileStore.Profile =
        profiles[info.profileKey] ?: PadProfileStore.Profile(info.profileKey)

    /** 面板上看哪一只。 */
    fun select(id: Int?) {
        selectedId = id
    }

    /** 抓一个物理键：抓到就绑到那个游戏键上。 */
    fun capture(deviceId: Int, target: String) {
        conflict = null
        capturing = target
        captureDeviceId = deviceId
        capture.start(deviceId, now()) { source ->
            captured(deviceId, target, source)
        }
    }

    /** 抓到了：没有撞车就绑上，撞车了让玩家选。 */
    private fun captured(deviceId: Int, target: String, source: String) {
        capturing = null
        captureDeviceId = null
        val info = device(deviceId) ?: return
        val profile = profile(info)
        val hit = PadMapping.conflict(profile.bindings, source, target)
        if (hit != null) {
            conflict = Conflict(deviceId, source, target, hit.targetSource, hit.sourceTarget)
        } else {
            save(profile.copy(bindings = PadMapping.withBinding(profile.bindings, source, target)))
        }
    }

    /** 玩家选了：替换（新的顶掉旧的）、交换（两个对调）、取消（什么都不做）。 */
    fun resolveConflict(replace: Boolean, swap: Boolean) {
        val hit = conflict ?: return
        conflict = null
        val info = device(hit.deviceId) ?: return
        val profile = profile(info)
        val bindings = when {
            swap -> PadMapping.swapBinding(profile.bindings, hit.source, hit.target)
            replace -> PadMapping.withBinding(profile.bindings, hit.source, hit.target)
            else -> profile.bindings
        }
        save(profile.copy(bindings = bindings))
    }

    /** 去掉一条绑定（那个键落回出厂字典）。 */
    fun clearBinding(info: GamepadScan.Info, source: String) {
        val profile = profile(info)
        save(profile.copy(bindings = PadMapping.withoutBinding(profile.bindings, source)))
    }

    /**
     * 一键预设：面键按 **Xbox 的印字**（下面 A、右边 B、上 Y、左 X）。
     *
     * 出厂字典是 NS 的位置（游戏嘴里的 A/B/X/Y 指 NS 手柄上的四个位置）；
     * Xbox 印字的手柄想要「印字说了算」，点这个就行——它只是写四条绑定，
     * 「恢复默认」一键撤回。
     */
    fun applyXboxPrint(info: GamepadScan.Info) {
        val profile = profile(info)
        val faces = setOf("a", "b", "x", "y")
        val print = mapOf(
            PadSources.scan(PadMapping.Scan.SOUTH) to "a",   // 下面那颗
            PadSources.scan(PadMapping.Scan.EAST) to "b",    // 右边那颗
            PadSources.scan(PadMapping.Scan.NORTH) to "y",   // 上面那颗
            PadSources.scan(PadMapping.Scan.WEST) to "x",    // 左边那颗
        )
        val bindings = profile.bindings.filterValues { it !in faces } + print
        save(profile.copy(bindings = bindings))
    }

    /** 全部绑定恢复出厂。 */
    fun resetBindings(info: GamepadScan.Info) {
        val profile = profile(info)
        save(profile.copy(bindings = emptyMap()))
    }

    /** 校准（死区与扳机阈值）。 */
    fun setCalibration(info: GamepadScan.Info, calibration: PadCalibration) {
        val profile = profile(info)
        save(profile.copy(calibration = calibration))
    }

    /** 抓键的会话作废（玩家改了主意，或者面板关了）。 */
    fun cancelCapture() {
        capture.cancel()
        capturing = null
        captureDeviceId = null
    }

    /**
     * 手柄的增减：拔掉的那只从面板上消失，抓它的会话作废。
     */
    fun devicesChanged(devices: List<GamepadScan.Info>) {
        val ids = devices.mapTo(mutableSetOf()) { it.id }
        if (selectedId != null && selectedId !in ids) selectedId = null
        captureDeviceId?.let { id ->
            if (id !in ids) {
                capture.cancelDisconnectedDevice(id)
                capturing = null
                captureDeviceId = null
            }
        }
    }

    /**
     * 一个按键事件进来（抓键用）。
     *
     * @return true = 这个事件被吃掉了（抓键的时候不该再下泄给游戏）。
     */
    fun offerKey(
        deviceId: Int,
        isGamepad: Boolean,
        down: Boolean,
        up: Boolean,
        repeatCount: Int,
        scanCode: Int,
        keyCode: Int,
    ): Boolean {
        val offer = capture.offerKey(
            deviceId, isGamepad, down, up, repeatCount, scanCode, keyCode, now(),
        )
        if (offer.cancelled) {
            capturing = null
            captureDeviceId = null
        }
        return offer.consumed
    }

    /**
     * 一根模拟轴进来（抓键用：扳机推到底、十字键帽扳到边也算一次按键）。
     *
     * @return true = 这个事件被吃掉了。
     */
    fun offerAxis(deviceId: Int, isGamepad: Boolean, axisValues: List<Pair<Int, Float>>): Boolean {
        val offer = capture.offerAxis(deviceId, isGamepad, axisValues, now())
        if (offer.cancelled) {
            capturing = null
            captureDeviceId = null
        }
        return offer.consumed
    }

    /**
     * 这个物理键（deviceId 的那只）现在是哪个游戏键：档案的绑定优先，出厂字典兜底。
     * 示波画的就是这一层——玩家按了手柄上印着 A 的那颗，「按位置」时亮的是 B。
     */
    fun resolveTarget(deviceId: Int, scanCode: Int, keyCode: Int): String? {
        val info = device(deviceId)
        // 列表里没有这只设备（它刚重连，设备 id 变了）：按它自己算档案键
        val key = info?.profileKey
            ?: android.view.InputDevice.getDevice(deviceId)?.let { PadProfileKey.forDevice(it).key }
            ?: return null
        val profile = profiles[key]
        val mapping = profile?.bindings ?: emptyMap()
        val labels = profile?.labels ?: false
        return PadMapping.resolveButton(mapping, labels, scanCode, keyCode)
    }

    /** 这只手柄的死区（示波画的是游戏收到的那一份：死区里的偏移算 0）。 */
    fun deadzoneOf(info: GamepadScan.Info): Float =
        profiles[info.profileKey]?.calibration?.deadzone ?: PadCalibration.DEFAULT_DEADZONE

    /** 档案存下：写文件，再叫外面（游戏里要把新表推给宿主）。 */
    private fun save(profile: PadProfileStore.Profile) {
        profiles = profiles + (profile.key to profile)
        store.save(profiles)
        onChanged(profiles)
    }

    /** 从磁盘重读一遍档案（游戏回到前台时：玩家可能刚在首页改过）。 */
    fun reload() {
        val fresh = store.load()
        profiles = fresh.profiles
        recovered = fresh.recoveredFromCorruption
    }

    private fun device(deviceId: Int): GamepadScan.Info? =
        devices().firstOrNull { it.id == deviceId }

    companion object {
        /** 这个 App 里的手柄档案（游戏与首页用的是同一个文件）。 */
        fun forContext(context: Context): PadCenterController = PadCenterController(
            PadProfileStore(File(context.filesDir, "pads/${PadProfileStore.FILE_NAME}")),
        )
    }
}

/** 这只设备的可抓轴（扳机与十字键帽）与它们此刻的值。 */
fun capturableAxes(event: MotionEvent): List<Pair<Int, Float>> =
    event.device?.motionRanges.orEmpty()
        .filter { it.isFromSource(InputDevice.SOURCE_JOYSTICK) }
        .filter { PadSources.isCapturableAxis(it.axis) }
        .map { it.axis to event.getAxisValue(it.axis) }

/** 测振动：设备马达优先，机身兜底（蓝牙手柄常常没有马达）。 */
fun vibratePad(context: Context, info: GamepadScan.Info) {
    val manager = context.getSystemService(android.hardware.input.InputManager::class.java)
    val device = manager?.getInputDevice(info.id)
    val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) device?.vibratorManager?.defaultVibrator else device?.vibrator
    val effect = android.os.VibrationEffect.createOneShot(
        120, android.os.VibrationEffect.DEFAULT_AMPLITUDE,
    )
    if (vibrator?.hasVibrator() == true) {
        vibrator.vibrate(effect)
    } else {
        context.getSystemService(android.os.Vibrator::class.java)?.vibrate(effect)   // 没有马达：机身
    }
}
