package com.nx.android.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.view.InputDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 接在手机上的手柄：枚举与热插拔。
 *
 * 判据：不是虚拟设备，而且来源是手柄或摇杆（`SOURCE_GAMEPAD` / `SOURCE_JOYSTICK`）。
 * 虚拟设备要排掉 —— 小米手机上常驻一个 `uinput-xiaomi` 的虚拟输入设备，把它算成手柄，
 * 虚拟手柄就永远不出现。
 *
 * 每一只还带一个档案键（PadProfileKey）：同一只手柄在换手机后还是这只。
 */
object GamepadScan {

    /** 一只手柄。 */
    data class Info(
        val id: Int,
        val name: String,
        val vibration: Boolean,
        val descriptor: String,
        val vendorId: Int,
        val productId: Int,
        val axes: Set<Int>,
        val profileKey: String,
        val profilePersistent: Boolean,
        val capabilitySignature: String,
        val modelTemplateKey: String?,
        val requiresProfileConfirmation: Boolean,
    )

    private val mutableDevices = MutableStateFlow<List<Info>>(emptyList())
    val devices: StateFlow<List<Info>> = mutableDevices.asStateFlow()
    private var inputManager: InputManager? = null
    private var listener: InputManager.InputDeviceListener? = null

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) = refresh()
        override fun onInputDeviceRemoved(id: Int) = refresh()
        override fun onInputDeviceChanged(id: Int) = refresh()
    }

    /** 开始盯着（游戏的进程里开一次）。 */
    @Synchronized
    fun start(ctx: Context) {
        if (listener != null) return
        val manager = ctx.applicationContext.getSystemService(InputManager::class.java) ?: return
        inputManager = manager
        listener = deviceListener
        manager.registerInputDeviceListener(deviceListener, null)
        refresh()
    }

    @Synchronized
    fun stop() {
        val manager = inputManager
        val activeListener = listener
        if (manager != null && activeListener != null) {
            manager.unregisterInputDeviceListener(activeListener)
        }
        inputManager = null
        listener = null
        mutableDevices.value = emptyList()
    }

    @Synchronized
    private fun refresh() {
        val manager = inputManager ?: return
        mutableDevices.value = scan(manager)
    }

    /**
     * 列表可能落后于系统（手柄重连以后设备 id 变了，而热插拔的回调没赶上）：
     * 有一个事件的设备不在列表里，就重扫一遍，把新列表发出去。
     */
    @Synchronized
    fun refreshNow(ctx: Context) {
        val manager = inputManager
            ?: ctx.applicationContext.getSystemService(InputManager::class.java)
            ?: return
        inputManager = manager
        mutableDevices.value = scan(manager)
    }

    /** 现在接着的手柄（直接扫一次，不靠监听）。 */
    fun scan(ctx: Context): List<Info> {
        val im = ctx.getSystemService(InputManager::class.java) ?: return emptyList()
        return scan(im)
    }

    private fun scan(im: InputManager): List<Info> {
        val gamepads = im.inputDeviceIds.toList().mapNotNull { id ->
            InputDevice.getDevice(id)?.takeIf { isGamepad(it) }
        }
        return gamepads.map { d ->
            val axes = d.motionRanges.filter { it.isFromSource(InputDevice.SOURCE_JOYSTICK) }
                .mapTo(linkedSetOf()) { it.axis }
            val identity = PadProfileKey.forDevice(d, gamepads)
            Info(
                id = d.id,
                name = d.name,
                vibration = hasVibration(d),
                descriptor = d.descriptor,
                vendorId = d.vendorId,
                productId = d.productId,
                axes = axes,
                profileKey = identity.key,
                profilePersistent = identity.persistent,
                capabilitySignature = identity.capabilitySignature,
                modelTemplateKey = identity.modelTemplateKey,
                requiresProfileConfirmation = identity.requiresConfirmation,
            )
        }
    }

    /** 这只设备是手柄吗（不是虚拟设备，来源是手柄或摇杆）。 */
    fun isGamepad(device: InputDevice): Boolean =
        !device.isVirtual &&
            (device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)

    /** 系统给这只设备配了马达吗（蓝牙手柄常常没有，机身兜底）。 */
    fun hasVibration(d: InputDevice): Boolean = try {
        if (Build.VERSION.SDK_INT >= 31) {
            d.vibratorManager.defaultVibrator.hasVibrator()
        } else {
            @Suppress("DEPRECATION")
            d.vibrator?.hasVibrator() == true
        }
    } catch (_: Exception) {
        false
    }
}
