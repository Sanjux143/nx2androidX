package com.nx.android.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 导入的包按自己的路径认：同一个游戏可以有两份，各跑各的 */
class PortablePackLibraryTest {

    private fun tmpDir(): File = File.createTempFile("nx2a-imported", "").let { f -> f.delete(); f.mkdirs(); f }

    /** 一个导入的文件夹：清单 + 包里的游戏文件 */
    private fun importedFolder(name: String = "Test Game"): File {
        val dir = tmpDir()
        File(dir, "RomFS/content").mkdirs()
        File(dir, "RomFS/content/a.bin").writeBytes(ByteArray(16))
        File(dir, "nx2a-data.json").writeText(
            JSONObject().put("title_id", "0100000000010000").put("name", name).toString())
        return dir
    }

    /** 一个假的包：包里带着游戏文件，没有宿主程序（运行库在这台手机上编） */
    private fun fakePort(dir: File): Ported = Ported(
        pack = dir, host = File(dir, "host/libdmplay.so"), icon = null,
        titleId = "0100000000010000", name = "Test Game", hostDeclared = false)

    /** 同一个游戏：App 自己的一份与导入的一份并排，谁也不吃掉谁 */
    @Test
    fun `an imported pack sits beside the pack the app made`() {
        val dir = importedFolder()
        val local = GameEntry("0100000000010000", fakePort(dir), null, "/sdcard/Games/Test.nsp")
        val imported = GameEntry("0100000000010000", fakePort(dir), null, null,
            PortablePack.Imported(dir.absolutePath, "0100000000010000", "Test Game"))

        val merged = LibraryLogic.merge(
            mapOf("0100000000010000" to local.pack!!), emptyMap(), emptyMap(), listOf(imported))

        assertEquals(2, merged.size)
        assertEquals(2, merged.map { it.key }.distinct().size)   // 两个身份
        assertTrue(merged.any { it.isImported })
        assertTrue(merged.any { !it.isImported })
        assertNotEquals(merged[0].key, merged[1].key)
    }

    /** 导入的包：运行库由这台手机自己编，游戏第一次跑就行 */
    @Test
    fun `an imported pack with its files inside is a finished game`() {
        val dir = importedFolder()
        val entry = GameEntry("0100000000010000", fakePort(dir), null, null,
            PortablePack.Imported(dir.absolutePath, "0100000000010000", "Test Game"))

        assertTrue(entry.romfsInside)
        assertEquals(GameStatus.DONE,
            LibraryLogic.statusOf(entry, portRunning = false, portGameFile = "", workStartedAt = 0L))
    }

    /** 够不着的导入文件夹（卡不在）：它不是能玩的游戏 */
    @Test
    fun `an imported pack that cannot be reached is not a finished game`() {
        val entry = GameEntry("0100000000010000", null, null, null,
            PortablePack.Imported(tmpDir().absolutePath, "0100000000010000", "Test Game"))

        assertEquals(GameStatus.NEEDS_BUILD,
            LibraryLogic.statusOf(entry, portRunning = false, portGameFile = "", workStartedAt = 0L))
    }

    /** 卡上的名字：登记时记下的那个 */
    @Test
    fun `an imported card carries the name it was registered with`() {
        val dir = importedFolder("Test Game")
        val entry = GameEntry("0100000000010000", null, null, null,
            PortablePack.Imported(dir.absolutePath, "0100000000010000", "Test Game"))

        assertEquals("Test Game", entry.label)
        assertEquals(dir.absolutePath, entry.key)
    }
}
