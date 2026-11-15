package com.nx.android.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一只手柄此刻的样子（手柄中心的示波面板）：按着的键、摇杆的偏移。
 *
 * 只看，不吃：这些事件照旧进游戏（除非手柄中心开着，那时中心自己吞掉）。
 */
data class PadTestState(
    val deviceId: Int,
    val name: String,
    val pressed: Set<String> = emptySet(),
    val leftX: Float = 0f,
    val leftY: Float = 0f,
    val rightX: Float = 0f,
    val rightY: Float = 0f,
    val eventCount: Long = 0,
)

/**
 * 手柄的输入遥测：被动喂数，永不消费事件。
 *
 * 高亮的是**游戏**的键（映射之后的）：玩家按了手柄上印着 A 的那颗，映射是「按位置」时亮的是 B ——
 * 面板上看得见的正是这一层。
 */
object PadTestInput {

    /** 每只设备一份状态。 */
    private val mutableStates = MutableStateFlow<Map<Int, PadTestState>>(emptyMap())
    val states: StateFlow<Map<Int, PadTestState>> = mutableStates.asStateFlow()

    @Volatile private var observing = false
    private val keyPressed = HashMap<Int, MutableSet<String>>()
    private val axisDirections = HashMap<Int, Set<String>>()

    /** 开始看（手柄中心打开时）。 */
    fun start() {
        observing = true
        keyPressed.clear()
        axisDirections.clear()
        mutableStates.value = emptyMap()
    }

    /** 不看了。 */
    fun stop() {
        observing = false
        keyPressed.clear()
        axisDirections.clear()
        mutableStates.value = emptyMap()
    }

    /** 拔掉的手柄从面板上消失。 */
    fun retainDevices(deviceIds: Set<Int>) {
        if (!observing) return
        mutableStates.value = mutableStates.value.filterKeys { it in deviceIds }
        keyPressed.keys.retainAll(deviceIds)
        axisDirections.keys.retainAll(deviceIds)
    }

    /**
     * 一个按键事件。
     *
     * @param resolve 这个物理键对应哪个游戏键（null：这个键不进游戏）。由手柄中心按档案给出。
     */
    fun onKey(event: KeyEvent, resolve: (deviceId: Int, scanCode: Int, keyCode: Int) -> String?) {
        if (!observing) return
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return
        if (event.repeatCount > 0 && event.action == KeyEvent.ACTION_DOWN) return
        val device = event.device ?: return
        if (!GamepadScan.isGamepad(device)) return
        val target = resolve(device.id, event.scanCode, event.keyCode) ?: return
        val pressed = keyPressed.getOrPut(device.id) { mutableSetOf() }
        if (event.action == KeyEvent.ACTION_DOWN) pressed.add(target) else pressed.remove(target)
        update(device) {
            it.copy(pressed = pressed + axisDirections[device.id].orEmpty())
        }
    }

    /**
     * 一个摇杆/扳机的移动。
     *
     * @param deadzone 这只手柄的死区（面板上画的是**游戏收到的那一份**：死区里的偏移算 0）。
     */
    fun onMotion(event: MotionEvent, deadzone: Float = 0f) {
        if (!observing) return
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return
        val device = event.device ?: return
        if (!GamepadScan.isGamepad(device)) return
        val ranges = device.motionRanges.filter { it.isFromSource(InputDevice.SOURCE_JOYSTICK) }
            .associateBy { it.axis }
        fun value(axis: Int): Float = if (axis in ranges) event.getAxisValue(axis).coerceIn(-1f, 1f) else 0f
        fun stick(axis: Int): Float {
            val v = value(axis)
            return if (v > -deadzone && v < deadzone) 0f else v
        }
        val leftX = stick(MotionEvent.AXIS_X)
        val leftY = stick(MotionEvent.AXIS_Y)
        // The right stick: some pads report it as RX/RY, others as Z/RZ (the GameSir-X5 Lite:
        // the kernel says ABS_Z/ABS_RZ, and Android keeps those names). Whichever the device
        // has, the right stick is the right stick.
        val rightX = if (MotionEvent.AXIS_RX in ranges) stick(MotionEvent.AXIS_RX) else stick(MotionEvent.AXIS_Z)
        val rightY = if (MotionEvent.AXIS_RY in ranges) stick(MotionEvent.AXIS_RY) else stick(MotionEvent.AXIS_RZ)
        val hatX = value(MotionEvent.AXIS_HAT_X)
        val hatY = value(MotionEvent.AXIS_HAT_Y)
        val directions = buildSet {
            if (hatX < -0.5f) add("left")
            if (hatX > 0.5f) add("right")
            if (hatY < -0.5f) add("up")
            if (hatY > 0.5f) add("down")
        }
        axisDirections[device.id] = directions
        update(device) {
            it.copy(
                pressed = keyPressed[device.id].orEmpty() + directions,
                leftX = leftX,
                leftY = leftY,
                rightX = rightX,
                rightY = rightY,
            )
        }
    }

    private inline fun update(device: InputDevice, transform: (PadTestState) -> PadTestState) {
        val current = mutableStates.value[device.id] ?: PadTestState(device.id, device.name)
        mutableStates.value = mutableStates.value + (
            device.id to transform(current).copy(name = device.name, eventCount = current.eventCount + 1)
            )
    }
}
