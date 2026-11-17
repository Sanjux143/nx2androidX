package com.nx.android.domain

import com.nx.android.data.library.LibraryFileScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The library's ground truth: the title id in a game's name, the data packs of the output folder, its display name */
class FolderScannerTest {

    private fun tmpDir(): File = File.createTempFile("nx2a-scan", "").let { f ->
        f.delete(); f.mkdirs(); f
    }

    @Test
    fun `a title id in the file's name is read`() {
        assertEquals("0100000000010000", GameIdentity.titleIdIn("Zelda [0100000000010000][v0].nsp"))
        assertEquals("0100ABCDEF012345", GameIdentity.titleIdIn("Game [0100abcdef012345].xci"))
        assertNull(GameIdentity.titleIdIn("Hades II.xci"))
        assertNull(GameIdentity.titleIdIn("[not a title id].nsp"))
    }

    @Test
    fun `the output folder indexes every pack with a recognizable manifest`() {
        val out = tmpDir()
        val pack = File(out, "HadesIIData-data")
        File(pack, "host").mkdirs()
        File(pack, "nx2a-data.json").writeText("""{"title_id":"0100000000010000","name":"Hades II","package":"org.nx2a.hades"}""")
        File(pack, "host/libdmplay.so").writeText("ELF")
        File(pack, "icon.png").writeBytes(byteArrayOf(1))
        // another data pack whose host is gone: it remains visible so it can be rebuilt or deleted
        val orphan = File(out, "Gone-data")
        orphan.mkdirs()
        File(orphan, "nx2a-data.json").writeText("""{"title_id":"0100000000019999"}""")

        val idx = LibraryFileScanner.outIndex(out)
        assertEquals(2, idx.size)
        val p = idx["0100000000010000"]!!
        assertEquals("Hades II", p.name)
        assertEquals("0100000000010000", p.titleId)
        assertTrue(p.host.isFile)
        assertTrue(p.icon!!.isFile)
        assertTrue(idx.containsKey("0100000000019999"))
        assertFalse(idx["0100000000019999"]!!.runnable)
        out.deleteRecursively()
    }

    @Test
    fun `the pack's own status is read, so an experimental port says so`() {
        val out = tmpDir()
        val pack = File(out, "DarkSouls-data")
        File(pack, "host").mkdirs()
        File(pack, "nx2a-data.json").writeText("""{"title_id":"01004AB00A260000","name":"DARK SOULS","status":"experimental"}""")
        File(pack, "host/libdmplay.so").writeText("ELF")

        val p = LibraryFileScanner.outIndex(out)["01004AB00A260000"]!!
        assertEquals("experimental", p.status)
        // a pack from before the manifest carried the status: it is not experimental
        val old = File(out, "Old-data")
        File(old, "host").mkdirs()
        File(old, "nx2a-data.json").writeText("""{"title_id":"0100000000010000"}""")
        File(old, "host/libdmplay.so").writeText("ELF")
        assertEquals("", LibraryFileScanner.outIndex(out)["0100000000010000"]!!.status)
        out.deleteRecursively()
    }

    /**
     * 包读的是工作区里的游戏文件（移植时没有复制一份进包）：那个文件夹没了，游戏就跑不起来。
     */
    @Test
    fun `a pack that reads its files from the work folder needs that folder`() {
        val out = tmpDir()
        val work = tmpDir()
        val romfs = File(work, "extract/v1/RomFS").apply { mkdirs() }
        val pack = File(out, "Game-data")
        File(pack, "host").mkdirs()
        File(pack, "nx2a-data.json").writeText(
            """{"title_id":"0100000000010000","host":"host/libdmplay.so","player_api_revision":${PlayerApiRevision.CURRENT},"romfs_at":"${romfs.absolutePath}"}""")
        File(pack, "host/libdmplay.so").writeText("ELF")

        val p = LibraryFileScanner.outIndex(out)["0100000000010000"]!!
        assertEquals(romfs.absolutePath, p.romfsAt)
        assertFalse(p.romfsMissing)
        assertTrue(p.runnable)          // the folder is there

        romfs.delete()                  // the player cleared the build cache
        val gone = LibraryFileScanner.outIndex(out)["0100000000010000"]!!
        assertTrue(gone.romfsMissing)
        assertFalse(gone.runnable)      // the game cannot run without it
        assertFalse(gone.needsRuntimeUpdate)   // the host is fine: it is the game's files that are gone
        out.deleteRecursively(); work.deleteRecursively()
    }

    /** 老的数据包自带 RomFS：没有这一项，删工作区不影响它 */
    @Test
    fun `an old pack carries its own files`() {
        val out = tmpDir()
        val pack = File(out, "Old-data")
        File(pack, "host").mkdirs()
        File(pack, "nx2a-data.json").writeText("""{"title_id":"0100000000010000"}""")
        File(pack, "host/libdmplay.so").writeText("ELF")

        val p = LibraryFileScanner.outIndex(out)["0100000000010000"]!!
        assertEquals("", p.romfsAt)
        assertFalse(p.romfsMissing)
        out.deleteRecursively()
    }

    /**
     * 新格式的包（清单没有 host 字段）：运行库在统一目录里，可跑与否由目录侧的构建标记说了算——
     * 标记在且接口版本对得上就能跑（启动零拷贝）；标记没了或对不上就是「需要重建」。
     */
    @Test
    fun `a new-format pack runs on the runtime folder's build marker`() {
        val out = tmpDir()
        val runtimes = tmpDir()
        val pack = File(out, "Game-data")
        pack.mkdirs()
        File(pack, "nx2a-data.json").writeText(
            """{"title_id":"0100000000010000","player_api_revision":${PlayerApiRevision.CURRENT}}""")

        // 目录里还没有运行库：不能跑，需要重建（也不是只有清单的空壳——新格式以目录为准）
        var p = LibraryFileScanner.outIndex(out, runtimes)["0100000000010000"]!!
        assertFalse(p.runnable)
        assertTrue(p.needsRuntimeUpdate)
        assertFalse(p.hasHost)

        // 目录里一份标记对得上的完整运行库：能跑
        val dir = File(runtimes, "0100000000010000")
        dir.mkdirs()
        File(dir, "libdmplay.so").writeText("ELF")
        for (h in listOf("libhook_impl.so", "libmain_hook.so", "libfile_redirect_hook.so", "libgsl_alloc_hook.so")) {
            File(dir, h).writeText("ELF")
        }
        File(dir, "build.json").writeText(
            """{"player_api_revision":${PlayerApiRevision.CURRENT},"profile_h":"","no_lsfg":false}""")
        p = LibraryFileScanner.outIndex(out, runtimes)["0100000000010000"]!!
        assertTrue(p.runnable)
        assertFalse(p.needsRuntimeUpdate)
        assertTrue(p.hasHost)

        // 接口版本对不上（app 升级了）：要重建
        File(dir, "build.json").writeText(
            """{"player_api_revision":${PlayerApiRevision.CURRENT - 1},"profile_h":"","no_lsfg":false}""")
        p = LibraryFileScanner.outIndex(out, runtimes)["0100000000010000"]!!
        assertFalse(p.runnable)
        assertTrue(p.needsRuntimeUpdate)

        out.deleteRecursively(); runtimes.deleteRecursively()
    }

    @Test
    fun `a display name drops the brackets and the extension`() {
        assertEquals("Zelda", GameIdentity.displayName(File("Zelda [0100000000010000][v0].nsp")))
        assertEquals("Hades II", GameIdentity.displayName(File("Hades II.xci")))
        // nothing left after the brackets: the file's whole name stays
        assertEquals("(demo).nsp", GameIdentity.displayName(File("(demo).nsp")))
    }

    @Test
    fun `the label is the game's own name when the pack carries one`() {
        val out = tmpDir()
        val pack = File(out, "Arceus-data")
        File(pack, "host").mkdirs()
        File(pack, "nx2a-data.json").writeText("""{"title_id":"01001F5010DFA000","name":"Pokémon Legends: Arceus","display":"1.1.1"}""")
        File(pack, "host/libdmplay.so").writeText("ELF")

        val p = LibraryFileScanner.outIndex(out)["01001F5010DFA000"]!!
        // the file the player downloaded says nothing about the game: the pack does
        assertEquals("Pokémon Legends: Arceus", GameIdentity.labelFor(File("/sdcard/Games/[本体v1.0.0]xxxx.nsp"), p))
        // the game's own version comes from the pack too
        assertEquals("1.1.1", p.display)
        out.deleteRecursively()
    }

    @Test
    fun `a game without a pack keeps its file's name as the label`() {
        assertEquals("xxxx", GameIdentity.labelFor(File("/sdcard/Games/[本体v1.0.0]xxxx.nsp"), null))
    }

    @Test
    fun `a pack without a name, or with a blank one, falls back to the file's name`() {
        val out = tmpDir()
        val old = File(out, "Old-data")
        File(old, "host").mkdirs()
        File(old, "nx2a-data.json").writeText("""{"title_id":"0100000000010000"}""")
        File(old, "host/libdmplay.so").writeText("ELF")
        val blank = File(out, "Blank-data")
        File(blank, "host").mkdirs()
        File(blank, "nx2a-data.json").writeText("""{"title_id":"0100000000020000","name":"   "}""")
        File(blank, "host/libdmplay.so").writeText("ELF")

        val f = File("/sdcard/Games/[本体v1.0.0]xxxx.nsp")
        assertEquals("xxxx", GameIdentity.labelFor(f, LibraryFileScanner.outIndex(out)["0100000000010000"]))
        assertEquals("xxxx", GameIdentity.labelFor(f, LibraryFileScanner.outIndex(out)["0100000000020000"]))
        out.deleteRecursively()
    }

    @Test
    fun `a pack without a display version leaves the version empty`() {
        val out = tmpDir()
        val pack = File(out, "Old-data")
        File(pack, "host").mkdirs()
        File(pack, "nx2a-data.json").writeText("""{"title_id":"0100000000010000","name":"Hades II"}""")
        File(pack, "host/libdmplay.so").writeText("ELF")

        assertEquals("", LibraryFileScanner.outIndex(out)["0100000000010000"]!!.display)
        out.deleteRecursively()
    }

    /** 多个卷：每个卷各扫一遍；同一个 title 在两个卷上都有时，私有目录那份说了算 */
    @Test
    fun `every volume is indexed and the private one wins`() {
        val private = tmpDir()
        val card = tmpDir()
        fun pack(dir: File, titleId: String, name: String): File {
            val p = File(dir, "$titleId-data")
            File(p, "host").mkdirs()
            File(p, "nx2a-data.json").writeText("""{"title_id":"$titleId","name":"$name"}""")
            File(p, "host/libdmplay.so").writeText("ELF")
            return p
        }
        val onCard = pack(card, "0100000000010000", "The card one")
        val onPrivate = pack(private, "0100000000010000", "The private one")
        pack(card, "0100000000020000", "Only on the card")

        val byTitle = HashMap<String, com.nx.android.domain.Ported>()
        byTitle.putAll(LibraryFileScanner.outIndex(card))
        byTitle.putAll(LibraryFileScanner.outIndex(private))   // 私有目录后扫：它优先

        assertEquals(2, byTitle.size)
        assertEquals("The private one", byTitle["0100000000010000"]!!.name)
        assertEquals(onPrivate, byTitle["0100000000010000"]!!.pack)
        assertEquals(onCard, LibraryFileScanner.outIndex(card)["0100000000010000"]!!.pack)
        assertEquals("Only on the card", byTitle["0100000000020000"]!!.name)
    }

    /** 卡不在时，那个卷的路径还记得，但卷的根不在了——删游戏时要说实话 */
    @Test
    fun `the volume root of a path on a card is the card itself`() {
        assertEquals(File("/storage/FB9A-DC19"),
                     LibraryFileScanner.volumeRootOf(File("/storage/FB9A-DC19/Android/data/com.nx.android/files/ports/Game-data")))
        assertNull(LibraryFileScanner.volumeRootOf(File("/data/user/0/com.nx.android/files/ports/Game-data")))
    }

    /** 清单里的 title ID：宿主没了也要认（删包时那个包可能已经缺了宿主） */
    @Test
    fun `the manifest's title id is read without the host`() {
        val out = tmpDir()
        val pack = File(out, "Game-data").apply { mkdirs() }
        File(pack, "nx2a-data.json").writeText("""{"title_id":"0100000000010000"}""")

        assertEquals("0100000000010000", LibraryFileScanner.manifestTitleId(pack))
        assertNull(LibraryFileScanner.manifestTitleId(File(out, "nothing")))
        out.deleteRecursively()
    }

    /** 工作区：移植器按 title ID 建的目录，名字就是身份 */
    @Test
    fun `the work folders are indexed by the title id in their name`() {
        val work = tmpDir()
        File(work, "010078D000F88000_build").mkdirs()
        File(work, "0100B99019412000_build").mkdirs()

        val idx = LibraryFileScanner.workIndex(work)
        assertEquals(2, idx.size)
        assertEquals(File(work, "010078D000F88000_build"), idx["010078D000F88000"])
        assertEquals(File(work, "0100B99019412000_build"), idx["0100B99019412000"])
        work.deleteRecursively()
    }

    /** 不是移植器建的目录不算工作区 */
    @Test
    fun `only the porter's own folders are work folders`() {
        assertEquals("010078D000F88000", GameIdentity.titleIdInWork("010078D000F88000_build"))
        assertNull(GameIdentity.titleIdInWork("tmp"))
        assertNull(GameIdentity.titleIdInWork("010078D000F8800_build"))
        assertNull(GameIdentity.titleIdInWork("Hades_build"))
        // 没有工作区目录时，索引是空的
        assertTrue(LibraryFileScanner.workIndex(File(tmpDir(), "nothing")).isEmpty())
    }

    /**
     * 一次构建的时间窗里只有它自己的工作区：后来那次构建的工作区不算（mtime 在窗外）。
     */
    @Test
    fun `the workspace of a build is the one written in that build's time window`() {
        val work = tmpDir()
        val mine = File(work, "010078D000F88000_build").apply { mkdirs() }
        val later = File(work, "0100B99019412000_build").apply { mkdirs() }
        val t0 = 1_700_000_000_000L
        mine.setLastModified(t0 + 10_000)          // 构建期间写的
        later.setLastModified(t0 + 500_000)        // 后来那次构建写的

        assertEquals("010078D000F88000", LibraryFileScanner.workFolderIn(work, t0, t0 + 60_000))
        // 窗外的工作区不算：那次构建什么都没留下
        assertNull(LibraryFileScanner.workFolderIn(work, t0 + 200_000, t0 + 400_000))
        work.deleteRecursively()
    }
}
