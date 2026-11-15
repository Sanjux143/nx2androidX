package com.nx.android.game.overlay

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * 声音输出的设备：游戏的声音走哪一个（蓝牙耳机、USB 声卡……）。
 *
 * 设备由系统给（AudioManager），宿主那边换过去即刻生效（Native.audioSet：共享的音频后端把在播的流停掉、
 * 在新设备上重启），不用重启游戏。这里的 0 是「跟随系统」——宿主那边也是 0（它不指定设备，让系统自己选）。
 */

/** 跟随系统：系统自己选声音走哪个设备。 */
const val AUDIO_FOLLOW_SYSTEM = 0

/** 一个输出设备：系统的 id（宿主认这个 id）、它是什么种类、系统给的名字。 */
data class AudioOutput(val id: Int, val kind: AudioOutputKind, val productName: String?)

/** 设备的种类：宿主只管换设备，词是 App 的事。 */
enum class AudioOutputKind { SPEAKER, WIRED, BLUETOOTH, EXTERNAL }

/**
 * 一个输出设备是什么种类：认不出来的不给（免得列一个说了也没用的设备）。
 * 上游的规则（AudioChoices.java）：扬声器、蓝牙（带产品名）、外接（HDMI / USB）。
 */
fun audioOutputKind(type: Int): AudioOutputKind? = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioOutputKind.SPEAKER
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_USB_HEADSET -> AudioOutputKind.WIRED
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER -> AudioOutputKind.BLUETOOTH
    AudioDeviceInfo.TYPE_HDMI,
    AudioDeviceInfo.TYPE_HDMI_ARC,
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> AudioOutputKind.EXTERNAL
    else -> null
}

/** 系统现在有的输出设备（插上耳机就多一个，所以每次打开抽屉都重读）。 */
fun audioOutputDevices(context: Context): List<AudioOutput> {
    val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return emptyList()
    return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .mapNotNull { d ->
            val kind = audioOutputKind(d.type) ?: return@mapNotNull null
            AudioOutput(d.id, kind, d.productName?.toString()?.trim()?.takeIf { it.isNotEmpty() })
        }
        .distinctBy { it.id }
}
