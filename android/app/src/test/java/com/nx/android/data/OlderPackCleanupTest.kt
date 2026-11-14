package com.nx.android.data

import com.nx.android.data.library.OlderPackCleanup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 同 title ID 的旧包：新包落盘后旧的那个收走（存档先搬过去），别的游戏不碰 */
class OlderPackCleanupTest {

    private fun tmpDir(): File = File.createTempFile("nx2a-packs", "").let { f -> f.delete(); f.mkdirs(); f }

    private fun pack(ports: File, name: String, titleId: String, saves: Boolean = false): File {
        val dir = File(ports, name).apply { mkdirs() }
        File(dir, "nx2a-data.json").writeText("""{"title_id":"$titleId","name":"Game","status":"boots"}""")
        if (saves) {
            val dir2 = File(dir, "saves").apply { mkdirs() }
            File(dir2, "slot0.bin").writeText("save")
        }
        return dir
    }

    @Test
    fun `the older pack of the same game is removed`() {
        val ports = tmpDir()
        val old = pack(ports, "Game-data", "0100000000010000")
        val new = pack(ports, "GameAsian-data", "0100000000010000")
        val lines = OlderPackCleanup.removeOlderPacks(new, listOf(ports))
        assertFalse(old.exists())
        assertTrue(new.exists())
        assertTrue(lines.any { it.contains("removed the older pack") })
    }

    @Test
    fun `the older pack's saves move into the new pack`() {
        val ports = tmpDir()
        val old = pack(ports, "Game-data", "0100000000010000", saves = true)
        val new = pack(ports, "GameAsian-data", "0100000000010000")
        OlderPackCleanup.removeOlderPacks(new, listOf(ports))
        assertFalse(old.exists())
        assertEquals("save", File(new, "saves/slot0.bin").readText())
    }

    /** 两边都有存档：都不动，玩家的数据不静默销毁 */
    @Test
    fun `two packs with saves stay`() {
        val ports = tmpDir()
        val old = pack(ports, "Game-data", "0100000000010000", saves = true)
        val new = pack(ports, "GameAsian-data", "0100000000010000", saves = true)
        val lines = OlderPackCleanup.removeOlderPacks(new, listOf(ports))
        assertTrue(old.exists())
        assertTrue(new.exists())
        assertTrue(lines.any { it.contains("delete it by hand") })
    }

    /** 另一个游戏（区域版有自己的 title ID）：不碰 */
    @Test
    fun `another game is untouched`() {
        val ports = tmpDir()
        val other = pack(ports, "Other-data", "0100000000020000")
        val new = pack(ports, "Game-data", "0100000000010000")
        OlderPackCleanup.removeOlderPacks(new, listOf(ports))
        assertTrue(other.exists())
    }

    /** 不是包的目录（没有清单）：不碰 */
    @Test
    fun `a folder without a manifest is untouched`() {
        val ports = tmpDir()
        val stray = File(ports, "Stray-data").apply { mkdirs() }
        val new = pack(ports, "Game-data", "0100000000010000")
        OlderPackCleanup.removeOlderPacks(new, listOf(ports))
        assertTrue(stray.exists())
    }
}
