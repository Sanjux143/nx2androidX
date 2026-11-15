package com.nx.android.game.overlay

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 声音输出的设备：认得出种类才列出来（说不上来的设备不列） */
class AudioOutputsTest {

    @Test
    fun `the phone's own speaker is a speaker`() {
        assertEquals(AudioOutputKind.SPEAKER, audioOutputKind(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
    }

    @Test
    fun `a bluetooth headset is bluetooth`() {
        assertEquals(AudioOutputKind.BLUETOOTH, audioOutputKind(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals(AudioOutputKind.BLUETOOTH, audioOutputKind(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(AudioOutputKind.BLUETOOTH, audioOutputKind(AudioDeviceInfo.TYPE_BLE_SPEAKER))
    }

    @Test
    fun `a wired or usb headset is wired, a dongle is external`() {
        assertEquals(AudioOutputKind.WIRED, audioOutputKind(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(AudioOutputKind.WIRED, audioOutputKind(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(AudioOutputKind.EXTERNAL, audioOutputKind(AudioDeviceInfo.TYPE_HDMI))
        assertEquals(AudioOutputKind.EXTERNAL, audioOutputKind(AudioDeviceInfo.TYPE_USB_DEVICE))
    }

    @Test
    fun `a device we cannot name is not offered`() {
        assertNull(audioOutputKind(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertNull(audioOutputKind(-1))
    }
}
