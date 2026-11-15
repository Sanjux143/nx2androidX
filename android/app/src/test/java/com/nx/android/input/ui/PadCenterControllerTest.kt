package com.nx.android.input.ui

import com.nx.android.input.GamepadScan
import com.nx.android.input.PadCalibration
import com.nx.android.input.PadMapping
import com.nx.android.input.PadProfileStore
import com.nx.android.input.PadSources
import com.nx.android.input.PadTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 一只接在手机上的手柄（GameSir-X5 Lite）。 */
private fun device(id: Int = 7, key: String = "device:abc") = GamepadScan.Info(
    id = id,
    name = "GameSir-X5 Lite",
    vibration = true,
    descriptor = "GameSir-X5 Lite",
    vendorId = 48879,
    productId = 57005,
    axes = setOf(0, 1),
    profileKey = key,
    profilePersistent = true,
    capabilitySignature = "sig",
    modelTemplateKey = null,
    requiresProfileConfirmation = false,
)

/**
 * 手柄中心的状态：抓键、撞车、拔掉的手柄。
 *
 * 中心在首页与游戏里共用一份状态：抓键抓到的源绑上去，撞车时问玩家（替换 / 交换 / 取消），
 * 手柄拔了，抓它的会话作废。
 */
class PadCenterControllerTest {

    private class Fixture {
        val dir: File = Files.createTempDirectory("nx2a-pad-center").toFile()
        val store = PadProfileStore(File(dir, PadProfileStore.FILE_NAME))
        var clock = 1000L
        var devices = listOf(device())
        val controller: PadCenterController
        var changes = 0

        init {
            controller = PadCenterController(
                store = store,
                onChanged = { changes++ },
                devices = { devices },
                now = { clock },
            )
        }

        fun info() = devices.first()

        /** 按下一个物理键（扫描码优先）。 */
        fun press(scanCode: Int, keyCode: Int = 0, deviceId: Int = 7): Boolean =
            controller.offerKey(deviceId, true, down = true, up = false, repeatCount = 0, scanCode = scanCode, keyCode = keyCode)

        fun release(scanCode: Int, keyCode: Int = 0, deviceId: Int = 7): Boolean =
            controller.offerKey(deviceId, true, down = false, up = true, repeatCount = 0, scanCode = scanCode, keyCode = keyCode)
    }

    @Test
    fun `a captured key is bound to the target`() {
        val f = Fixture()
        f.controller.capture(7, "a")
        assertEquals("a", f.controller.capturing)

        f.press(PadMapping.Scan.EAST)
        f.release(PadMapping.Scan.EAST)

        assertNull(f.controller.capturing)
        val profile = f.controller.profile(f.info())
        assertEquals("a", profile.bindings[PadSources.scan(PadMapping.Scan.EAST)])
        assertEquals(1, f.changes)
    }

    @Test
    fun `a conflict waits for the player`() {
        val f = Fixture()
        f.controller.capture(7, "a")
        f.press(PadMapping.Scan.EAST)
        f.release(PadMapping.Scan.EAST)

        // 另一颗键也要 "a"：撞车了，等玩家选
        f.controller.capture(7, "a")
        f.press(PadMapping.Scan.NORTH)
        f.release(PadMapping.Scan.NORTH)

        val hit = f.controller.conflict
        assertNotNull(hit)
        assertEquals("a", hit!!.target)
        assertEquals(PadSources.scan(PadMapping.Scan.EAST), hit.otherSource)
    }

    @Test
    fun `replace gives the target to the new key`() {
        val f = Fixture()
        f.controller.capture(7, "a")
        f.press(PadMapping.Scan.EAST)
        f.release(PadMapping.Scan.EAST)

        f.controller.capture(7, "a")
        f.press(PadMapping.Scan.NORTH)
        f.release(PadMapping.Scan.NORTH)

        f.controller.resolveConflict(replace = true, swap = false)
        val bindings = f.controller.profile(f.info()).bindings
        assertEquals("a", bindings[PadSources.scan(PadMapping.Scan.NORTH)])
        assertNull(bindings[PadSources.scan(PadMapping.Scan.EAST)])
        assertNull(f.controller.conflict)
    }

    @Test
    fun `swap gives the old target to the other key`() {
        val f = Fixture()
        // 这颗键原来绑着 "x"
        f.controller.capture(7, "x")
        f.press(PadMapping.Scan.NORTH)
        f.release(PadMapping.Scan.NORTH)
        // 另一颗键占着 "a"
        f.controller.capture(7, "a")
        f.press(PadMapping.Scan.EAST)
        f.release(PadMapping.Scan.EAST)

        // 这颗键（NORTH）现在要 "a"：两个对调
        f.controller.capture(7, "a")
        f.press(PadMapping.Scan.NORTH)
        f.release(PadMapping.Scan.NORTH)

        f.controller.resolveConflict(replace = false, swap = true)
        val bindings = f.controller.profile(f.info()).bindings
        assertEquals("a", bindings[PadSources.scan(PadMapping.Scan.NORTH)])
        assertEquals("x", bindings[PadSources.scan(PadMapping.Scan.EAST)])
    }

    @Test
    fun `cancel leaves the bindings alone`() {
        val f = Fixture()
        f.controller.capture(7, "a")
        f.press(PadMapping.Scan.EAST)
        f.release(PadMapping.Scan.EAST)

        f.controller.capture(7, "a")
        f.press(PadMapping.Scan.NORTH)
        f.release(PadMapping.Scan.NORTH)

        f.controller.resolveConflict(replace = false, swap = false)
        val bindings = f.controller.profile(f.info()).bindings
        assertEquals("a", bindings[PadSources.scan(PadMapping.Scan.EAST)])
        assertEquals(1, bindings.size)
    }

    @Test
    fun `a controller that goes away cancels the capture`() {
        val f = Fixture()
        f.controller.capture(7, "a")
        assertEquals("a", f.controller.capturing)

        f.devices = emptyList()
        f.controller.devicesChanged(f.devices)

        assertNull(f.controller.capturing)
        // 拔掉了以后按键不再被吃掉
        assertTrue(!f.press(PadMapping.Scan.EAST))
    }

    @Test
    fun `the xbox print preset binds the four face buttons`() {
        val f = Fixture()
        f.controller.applyXboxPrint(f.info())

        val bindings = f.controller.profile(f.info()).bindings
        assertEquals("a", bindings[PadSources.scan(PadMapping.Scan.SOUTH)])
        assertEquals("b", bindings[PadSources.scan(PadMapping.Scan.EAST)])
        assertEquals("y", bindings[PadSources.scan(PadMapping.Scan.NORTH)])
        assertEquals("x", bindings[PadSources.scan(PadMapping.Scan.WEST)])
        // and the game gets them
        assertEquals("a", f.controller.resolveTarget(7, PadMapping.Scan.SOUTH, PadMapping.Key.BUTTON_A))
        assertEquals("b", f.controller.resolveTarget(7, PadMapping.Scan.EAST, PadMapping.Key.BUTTON_B))
        assertEquals("y", f.controller.resolveTarget(7, PadMapping.Scan.NORTH, PadMapping.Key.BUTTON_X))
        assertEquals("x", f.controller.resolveTarget(7, PadMapping.Scan.WEST, PadMapping.Key.BUTTON_Y))
    }

    @Test
    fun `the preset replaces the face buttons it writes and keeps the rest`() {
        val f = Fixture()
        f.controller.capture(7, "a")
        f.press(PadMapping.Scan.SOUTH)
        f.release(PadMapping.Scan.SOUTH)
        f.controller.capture(7, "l")
        f.press(PadMapping.Scan.L1)
        f.release(PadMapping.Scan.L1)

        f.controller.applyXboxPrint(f.info())

        val bindings = f.controller.profile(f.info()).bindings
        assertEquals("a", bindings[PadSources.scan(PadMapping.Scan.SOUTH)])
        assertEquals("l", bindings[PadSources.scan(PadMapping.Scan.L1)])   // the rest is kept
        assertEquals(5, bindings.size)
    }

    @Test
    fun `the calibration belongs to the controller`() {
        val f = Fixture()
        f.controller.setCalibration(f.info(), PadCalibration(deadzone = 0.25f, trigger = 0.6f))

        val profile = f.controller.profile(f.info())
        assertEquals(0.25f, profile.calibration.deadzone, 0.0001f)
        assertEquals(0.6f, profile.calibration.trigger, 0.0001f)
    }

    @Test
    fun `the old labels flag is read but not used`() {
        // 老档案里存过的 labels（面键按位置还是按标签的老开关）：读得出来，也不作数
        val f = Fixture()
        val profile = f.controller.profile(f.info())
        assertTrue(!profile.labels)
        assertEquals("b", f.controller.resolveTarget(7, 0, PadMapping.Key.BUTTON_A))
    }

    @Test
    fun `the profiles are written where the game reads them`() {
        val f = Fixture()
        f.controller.capture(7, "a")
        f.press(PadMapping.Scan.EAST)
        f.release(PadMapping.Scan.EAST)

        val reloaded = PadProfileStore(File(f.dir, PadProfileStore.FILE_NAME)).load().profiles
        val profile = reloaded[f.info().profileKey]
        assertNotNull(profile)
        assertEquals("a", profile!!.bindings[PadSources.scan(PadMapping.Scan.EAST)])
    }

    @Test
    fun `the resolved target is the game key the player sees`() {
        val f = Fixture()
        // 出厂字典按位置：下面那颗是游戏的 B，右边那颗是游戏的 A
        assertEquals("b", f.controller.resolveTarget(7, 0, PadMapping.Key.BUTTON_A))
        assertEquals("a", f.controller.resolveTarget(7, 0, PadMapping.Key.BUTTON_B))
        assertEquals("x", f.controller.resolveTarget(7, PadMapping.Scan.NORTH, 0))
        assertEquals("y", f.controller.resolveTarget(7, PadMapping.Scan.WEST, 0))
    }

    @Test
    fun `the position wins over the key code's name`() {
        val f = Fixture()
        // 任天堂的手柄把上面那颗叫 BUTTON_Y（keyCode 100），把左边那颗叫 BUTTON_X：
        // Android 的名字各家的手柄不一样，位置才是说了算的
        assertEquals("x", f.controller.resolveTarget(7, PadMapping.Scan.NORTH, PadMapping.Key.BUTTON_Y))
        assertEquals("y", f.controller.resolveTarget(7, PadMapping.Scan.WEST, PadMapping.Key.BUTTON_X))
        // 通用键位表的手柄：名字与位置本来就对得上
        assertEquals("x", f.controller.resolveTarget(7, PadMapping.Scan.NORTH, PadMapping.Key.BUTTON_X))
        assertEquals("b", f.controller.resolveTarget(7, PadMapping.Scan.SOUTH, PadMapping.Key.BUTTON_A))
    }

    @Test
    fun `a target the player cleared falls back to the default`() {
        val f = Fixture()
        f.controller.capture(7, "a")
        f.press(PadMapping.Scan.EAST)
        f.release(PadMapping.Scan.EAST)
        f.controller.clearBinding(f.info(), PadSources.scan(PadMapping.Scan.EAST))

        val bindings = f.controller.profile(f.info()).bindings
        assertTrue(bindings.isEmpty())
        // 落回出厂字典
        assertEquals("b", f.controller.resolveTarget(7, 0, PadMapping.Key.BUTTON_A))
        assertTrue(PadTargets.isValid("a"))
    }
}
