package com.nx.android.data.library

import com.nx.android.domain.PortablePack
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 导出一个完成的移植：数据包 + 散装的游戏文件；导入只认这种文件夹 */
class PortablePackTest {

    private fun tmpDir(name: String): File =
        File.createTempFile("nx2a-portable", "").let { f -> f.delete(); f.mkdirs(); f }

    /**
     * 一个假的数据包（app 模式）：清单指着自己机器上的工作区，env.txt 里也是那条本机路径。
     */
    private fun fakePack(dir: File, workspace: File, name: String = "Game-data"): File {
        val pack = File(dir, name).apply { mkdirs() }
        File(pack, "mods").mkdirs()
        File(pack, "mods/main.nso").writeBytes(ByteArray(512))
        File(pack, "profile/0100000000010000").mkdirs()
        File(pack, "profile/0100000000010000/android.tsv").writeText("name\tTest Game\n")
        File(pack, "shaders.dmsv").writeBytes(ByteArray(128))
        File(pack, "saves").mkdirs()
        File(pack, "saves/backup.zip").writeText("the player's backup")
        val man = JSONObject()
            .put("title_id", "0100000000010000")
            .put("name", "Test Game")
            .put("romfs_at", File(workspace, "RomFS").absolutePath)
        File(pack, "nx2a-data.json").writeText(man.toString())
        File(pack, "env.txt").writeText(
            "# Written by nx2android's porter\n" +
                "DM_LANG=en-US\n" +
                "DM_ROMFS=${File(workspace, "RomFS").absolutePath}\n" +
                "DM_AOC_DIR=${pack.absolutePath}/aoc\n" +
                "DM_SYSDATA=${pack.absolutePath}/sysdata\n"
        )
        return pack
    }

    /** 工作区里的游戏文件 */
    private fun fakeWorkspace(root: File): File {
        val work = File(root, "0100000000010000_build").apply { mkdirs() }
        File(work, "RomFS/content").mkdirs()
        File(work, "RomFS/content/a.bin").writeBytes(ByteArray(1024))
        File(work, "RomFS/content/b.bin").writeBytes(ByteArray(2048))
        return work
    }

    @Test
    fun `the export holds the pack's files and the game's files as plain files`() {
        val root = tmpDir("root"); val dst = tmpDir("dst")
        val work = fakeWorkspace(root)
        val pack = fakePack(root, work)

        val r = PortablePackFiles.export(pack, File(work, "RomFS").absolutePath, dst, "Test Game")

        assertTrue(r.failure ?: "", r.ok)
        val out = File(dst, "Test Game")
        assertTrue(out.isDirectory)
        assertTrue(File(out, "mods/main.nso").isFile)          // 包自己的文件
        assertTrue(File(out, "profile/0100000000010000/android.tsv").isFile)
        assertTrue(File(out, "shaders.dmsv").isFile)
        assertTrue(File(out, "RomFS/content/a.bin").isFile)    // 游戏文件：散文件
        assertTrue(File(out, "RomFS/content/b.bin").isFile)
        assertFalse(File(out, "RomFS/content").listFiles()?.any { it.length() > 2048 } == true)
    }

    /** 导出的文件夹不带本机专属的指针：它得能在别的机器上跑 */
    @Test
    fun `the export carries no path of the exporting phone`() {
        val root = tmpDir("root"); val dst = tmpDir("dst")
        val work = fakeWorkspace(root)
        val pack = fakePack(root, work)

        PortablePackFiles.export(pack, File(work, "RomFS").absolutePath, dst, "Test Game")
        val out = File(dst, "Test Game")

        val manifest = JSONObject(File(out, "nx2a-data.json").readText())
        assertFalse("the workspace pointer must not travel", manifest.has("romfs_at"))
        assertEquals("0100000000010000", manifest.getString("title_id"))
        val env = File(out, "env.txt").readText()
        assertFalse("the workspace row must not travel", env.contains("DM_ROMFS="))
        assertFalse(env.contains(work.absolutePath))
        // 指向包自己的行跟着文件夹走
        assertTrue(env.contains("DM_AOC_DIR=${out.absolutePath}/aoc"))
        assertTrue(env.contains("DM_SYSDATA=${out.absolutePath}/sysdata"))
    }

    /** 导出方玩家自己的存档备份不是移植的一部分 */
    @Test
    fun `the export leaves the player's exported saves behind`() {
        val root = tmpDir("root"); val dst = tmpDir("dst")
        val work = fakeWorkspace(root)
        val pack = fakePack(root, work)

        PortablePackFiles.export(pack, File(work, "RomFS").absolutePath, dst, "Test Game")

        assertFalse(File(dst, "Test Game/saves").exists())
        assertTrue(File(pack, "saves/backup.zip").isFile)   // 源包里的还在
    }

    @Test
    fun `the export refuses when the folder is already there`() {
        val root = tmpDir("root"); val dst = tmpDir("dst")
        val work = fakeWorkspace(root)
        val pack = fakePack(root, work)
        File(dst, "Test Game").mkdirs()

        val r = PortablePackFiles.export(pack, File(work, "RomFS").absolutePath, dst, "Test Game")

        assertFalse(r.ok)
        assertEquals("exists", r.failure)
    }

    /** 写坏了的文件夹不留半成品 */
    @Test
    fun `a failed export leaves nothing behind`() {
        val root = tmpDir("root"); val dst = tmpDir("dst")
        val work = fakeWorkspace(root)
        val pack = fakePack(root, work)
        // 工作区不在了：游戏文件拷不成
        File(work, "RomFS").deleteRecursively()

        val r = PortablePackFiles.export(pack, File(work, "RomFS").absolutePath, dst, "Test Game")

        assertFalse(r.ok)
        assertFalse(File(dst, "Test Game").exists())
        assertFalse(File(dst, "Test Game.partial").exists())
    }

    /** 导入只认自己导出的那种文件夹 */
    @Test
    fun `the import checks what the folder holds`() {
        val root = tmpDir("root")
        val good = File(root, "good").apply { mkdirs() }
        File(good, "nx2a-data.json").writeText(JSONObject().put("title_id", "0100000000010000").toString())
        File(good, "RomFS").mkdirs()

        val v = PortablePack.validate(good)
        assertTrue(v.failure ?: "", v.ok)
        assertEquals("0100000000010000", v.titleId)

        val noManifest = File(root, "no-manifest").apply { mkdirs() }
        assertEquals("no_manifest", PortablePack.validate(noManifest).failure)

        val noRomfs = File(root, "no-romfs").apply { mkdirs() }
        File(noRomfs, "nx2a-data.json").writeText(JSONObject().put("title_id", "0100000000010000").toString())
        assertEquals("no_romfs", PortablePack.validate(noRomfs).failure)

        val badId = File(root, "bad-id").apply { mkdirs() }
        File(badId, "nx2a-data.json").writeText(JSONObject().put("title_id", "nope").toString())
        File(badId, "RomFS").mkdirs()
        assertEquals("bad_title_id", PortablePack.validate(badId).failure)

        assertEquals("not_a_folder", PortablePack.validate(File(root, "nothing")).failure)
    }

    /** 导入时把包自己的行指到它现在的位置（DLC 与系统数据跟着文件夹走） */
    @Test
    fun `the import points the pack's own rows at its new place`() {
        val root = tmpDir("root")
        val folder = File(root, "from-somewhere-else").apply { mkdirs() }
        File(folder, "env.txt").writeText(
            "DM_LANG=ja\n" +
                "DM_AOC_DIR=/storage/1234-5678/Games/port/aoc\n" +
                "DM_SYSDATA=/storage/1234-5678/Games/port/sysdata\n" +
                "DM_SHARED_FONT=/storage/1234-5678/Games/port/standard.ttf\n"
        )

        val ok = PortablePackFiles.placeEnv(folder)

        assertTrue(ok)
        val env = File(folder, "env.txt").readText()
        assertTrue(env.contains("DM_AOC_DIR=${folder.absolutePath}/aoc"))
        assertTrue(env.contains("DM_SYSDATA=${folder.absolutePath}/sysdata"))
        assertTrue(env.contains("DM_SHARED_FONT=${folder.absolutePath}/standard.ttf"))
        assertTrue(env.contains("DM_LANG=ja"))            // 别的行不动
    }

    /** 文件夹的名字：游戏名，文件系统不收的字符换掉 */
    @Test
    fun `the folder is named after the game`() {
        assertEquals("Test Game", PortablePack.folderName("Test Game"))
        assertEquals("A_B_C", PortablePack.folderName("A/B:C"))
        assertEquals("port", PortablePack.folderName("   "))
    }
}
