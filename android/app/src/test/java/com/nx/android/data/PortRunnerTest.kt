package com.nx.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 移植的命令行：没有输出目录（数据包落在 App 自己的目录里），多出来的「额外地址空间」只在玩家选了数值时才有（Auto 不带这个参数） */
class PortRunnerTest {

    private fun args(aliasHeadroom: Int, touch: Boolean = false, fps60: Boolean = false,
                     experimental: Boolean = false, noLsfg: Boolean = false,
                     sharedFont: String = "", firmware: String = "", mtReplay: Boolean = false): List<String> =
        PortRunner.portArgs("/sdcard/game.nsp", "/keys/prod.keys", touch, fps60,
            experimental, aliasHeadroom, noLsfg, sharedFont, firmware, mtReplay)

    @Test
    fun `Auto passes no address space at all`() {
        val a = args(-1)
        assertFalse("no --alias-headroom on Auto", a.contains("--alias-headroom"))
        assertEquals(listOf("-m", "porter", "port", "/sdcard/game.nsp", "-k", "/keys/prod.keys",
            "--progress", "json", "--app"), a)
    }

    @Test
    fun `the command line has no output folder`() {
        val a = args(-1)
        assertFalse("no -o on the command line", a.contains("-o"))
        assertFalse("no --out on the command line", a.contains("--out"))
    }

    @Test
    fun `a manual value goes on the command line`() {
        val a = args(16)
        val i = a.indexOf("--alias-headroom")
        assertTrue("the option is there", i >= 0)
        assertEquals("16", a[i + 1])
    }

    @Test
    fun `zero is a value of its own, not Auto`() {
        val a = args(0)
        val i = a.indexOf("--alias-headroom")
        assertTrue("the option is there", i >= 0)
        assertEquals("0", a[i + 1])
    }

    @Test
    fun `the other options keep their places`() {
        val a = args(-1, touch = true, fps60 = true, experimental = true, noLsfg = true)
        assertTrue(a.contains("--touch"))
        assertTrue(a.contains("--fps60"))
        assertTrue(a.contains("--experimental"))
        assertTrue(a.contains("--no-lsfg"))
    }

    /** 玩家的覆盖：自己的系统字体、整机那一份固件、多线程回放 */
    @Test
    fun `the player's own font and the firmware folder go on the command line`() {
        val a = args(-1, sharedFont = "/sdcard/font.ttf", firmware = "/data/user/0/com.nx.android/files/firmware")
        val i = a.indexOf("--shared-font")
        assertTrue("the font is there", i >= 0)
        assertEquals("/sdcard/font.ttf", a[i + 1])
        val j = a.indexOf("--firmware")
        assertTrue("the firmware folder is there", j >= 0)
        assertEquals("/data/user/0/com.nx.android/files/firmware", a[j + 1])
    }

    @Test
    fun `no font and no firmware means no options at all`() {
        val a = args(-1)
        assertFalse("no --shared-font without one", a.contains("--shared-font"))
        assertFalse("no --firmware without one", a.contains("--firmware"))
        assertFalse("no --mt-replay without it", a.contains("--mt-replay"))
    }

    @Test
    fun `the multithread replay is a switch`() {
        val a = args(-1, mtReplay = true)
        assertTrue(a.contains("--mt-replay"))
    }
}
