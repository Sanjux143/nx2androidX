package com.nx.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The card's status: the running build, the data pack (its host inside makes the game done), and what is left of a failed build */
class LibraryLogicTest {

    @Test
    fun `runtime update marks its pack as building without a source association`() {
        val pack = port(hostExists = true)
        assertEquals(GameStatus.PORTING, LibraryLogic.statusOf(entry(pack = pack), true, pack.pack.absolutePath, 0))
    }

    @Test
    fun `runtime updates block deletion and movement of their pack without source files`() {
        val path = "/ports/Game-data"
        assertEquals(GameCleanup.Outcome.BUSY_PORTING,
            LibraryLogic.removalBlocked(true, path, null, "0100000000010000", packPath = path))
        assertEquals(GameCleanup.Outcome.BUSY_PORTING,
            LibraryLogic.moveBlocked(true, path, null, "0100000000010000", false, packPath = path))
    }

    private fun tmpDir(): File = File.createTempFile("nx2a-port", "").let { f -> f.delete(); f.mkdirs(); f }

    private fun port(hostExists: Boolean): Ported {
        val pack = tmpDir()
        File(pack, "host").mkdirs()
        val host = File(pack, "host/libdmplay.so")
        if (hostExists) host.writeText("ELF")
        return Ported(pack, host, null, "0100000000010000", "Hades II", playerApiRevision = PlayerApiRevision.CURRENT)
    }

    private fun entry(pack: Ported? = null, work: File? = null, source: String? = null) =
        GameEntry("0100000000010000", pack, work, source)

    private fun packWith(name: String, runnable: Boolean): Ported {
        val p = port(hostExists = runnable)
        return p.copy(playerApiRevision = if (runnable) PlayerApiRevision.CURRENT else 0, name = name)
    }

    @Test
    fun `a finished port (pack and host together) makes the game done`() {
        assertEquals(GameStatus.DONE,
                     LibraryLogic.statusOf(entry(pack = port(hostExists = true)),
                                          portRunning = false, portGameFile = "", workStartedAt = 0L))
    }

    /** 源文件在不在都一样：跑起来只需要数据包 */
    @Test
    fun `the game is done whether its source file is there or not`() {
        assertEquals(GameStatus.DONE,
                     LibraryLogic.statusOf(entry(pack = port(hostExists = true), source = "/sdcard/Games/Game.nsp"),
                                          portRunning = false, portGameFile = "", workStartedAt = 0L))
        assertEquals(GameStatus.DONE,
                     LibraryLogic.statusOf(entry(pack = port(hostExists = true), source = null),
                                          portRunning = false, portGameFile = "", workStartedAt = 0L))
    }

    /** 包在但宿主没了：卡片仍在，但需要构建 */
    @Test
    fun `a pack whose host went away is not done any more`() {
        assertEquals(GameStatus.NEEDS_BUILD,
                     LibraryLogic.statusOf(entry(pack = port(hostExists = false)),
                                          portRunning = false, portGameFile = "", workStartedAt = 0L))
    }

    @Test
    fun `a missing or different player interface revision requires a runtime update`() {
        val current = port(hostExists = true)
        assertTrue(current.runnable)
        assertTrue(current.copy(playerApiRevision = 0).needsRuntimeUpdate)
        assertTrue(current.copy(playerApiRevision = PlayerApiRevision.CURRENT + 1).needsRuntimeUpdate)
    }

    /** 正在构建的那个游戏：构建中 */
    @Test
    fun `the game that is being built now is porting`() {
        val path = "/sdcard/Games/Game.nsp"
        assertEquals(GameStatus.PORTING,
                     LibraryLogic.statusOf(entry(work = tmpDir(), source = path),
                                          portRunning = true, portGameFile = path, workStartedAt = 0L))
    }

    /** 第一次构建还没有源文件的记忆：工作区正在被写就是它 */
    @Test
    fun `the workspace being written means the build is running`() {
        val work = tmpDir()
        val started = System.currentTimeMillis() - 1000
        work.setLastModified(System.currentTimeMillis())
        assertEquals(GameStatus.PORTING,
                     LibraryLogic.statusOf(entry(work = work),
                                          portRunning = true, portGameFile = "/sdcard/Games/Game.nsp",
                                          workStartedAt = started))
    }

    /** 源文件在不在不改变卡片状态：没有可运行包就是需要构建。 */
    @Test
    fun `a game without a runnable pack needs a build whether source exists or not`() {
        val source = File.createTempFile("nx2a-source", ".xci").apply { writeText("x") }
        assertEquals(GameStatus.NEEDS_BUILD,
                     LibraryLogic.statusOf(entry(work = tmpDir(), source = source.absolutePath),
                                          portRunning = false, portGameFile = "", workStartedAt = 0L))
        assertEquals(GameStatus.NEEDS_BUILD,
                     LibraryLogic.statusOf(entry(work = tmpDir(), source = "/sdcard/Games/Game.nsp"),
                                          portRunning = false, portGameFile = "", workStartedAt = 0L))
        source.delete()
    }

    /** 重新构建失败但旧包还在：游戏照样能玩 */
    @Test
    fun `a finished port wins over a failed build`() {
        assertEquals(GameStatus.DONE,
                     LibraryLogic.statusOf(entry(pack = port(hostExists = true), work = tmpDir()),
                                          portRunning = false, portGameFile = "", workStartedAt = 0L))
    }

    /** 正在构建的永远是构建中 */
    @Test
    fun `the running build wins over everything else`() {
        assertEquals(GameStatus.PORTING,
                     LibraryLogic.statusOf(entry(pack = port(hostExists = true), source = "/sdcard/Games/Game.nsp"),
                                          portRunning = true, portGameFile = "/sdcard/Games/Game.nsp",
                                          workStartedAt = 0L))
    }

    /** 合成：同一个 title ID 的包与工作区合成一张卡 */
    @Test
    fun `a pack and a workspace of the same game make one card`() {
        val pack = port(hostExists = true)
        val work = tmpDir()
        val cards = LibraryLogic.merge(
            mapOf("0100000000010000" to pack),
            mapOf("0100000000010000" to work),
            mapOf("0100000000010000" to "/sdcard/Games/Game.nsp"),
        )
        assertEquals(1, cards.size)
        val card = cards[0]
        assertEquals("0100000000010000", card.titleId)
        assertEquals(pack.pack, card.pack!!.pack)
        assertEquals(work, card.work)
        assertEquals("/sdcard/Games/Game.nsp", card.sourcePath)
    }

    /** 只有工作区（构建失败或包被删了）：它也是一张卡 */
    @Test
    fun `a game with only a workspace still has a card`() {
        val work = tmpDir()
        val cards = LibraryLogic.merge(mapOf(), mapOf("0100000000010000" to work), mapOf())
        assertEquals(1, cards.size)
        assertNull(cards[0].pack)
        assertEquals(work, cards[0].work)
    }

    /** 什么也没有：库里没有卡 */
    @Test
    fun `nothing on the disk means no card`() {
        assertTrue(LibraryLogic.merge(mapOf(), mapOf(), mapOf()).isEmpty())
    }

    @Test
    fun `a runnable card pack wins over an incomplete internal candidate`() {
        val card = packWith("Card", runnable = true)
        val internal = packWith("Internal partial", runnable = false)
        val selected = LibraryLogic.preferPack(card, currentInternal = false, internal, candidateInternal = true)
        assertEquals(card.pack, selected.first.pack)
        assertTrue(selected.first.runnable)
        val internalRunnable = packWith("Internal", runnable = true)
        assertEquals(internalRunnable.pack,
            LibraryLogic.preferPack(card, false, internalRunnable, true).first.pack)
    }

    /** 同一个 title ID 的两个包：正式档案的那个（boots）压过临时档案的那个（experimental），与扫到的顺序无关 */
    @Test
    fun `a pack of a real profile wins over the provisional one`() {
        val provisional = packWith("Provisional", runnable = true).copy(status = "experimental")
        val real = packWith("Real", runnable = true).copy(status = "boots")
        assertEquals(real.pack, LibraryLogic.preferPack(provisional, true, real, true).first.pack)
        assertEquals(real.pack, LibraryLogic.preferPack(real, true, provisional, true).first.pack)
    }

    /** 两个包一样时：新的那个赢（和目录名的先后无关） */
    @Test
    fun `the newer pack wins when the two are alike`() {
        val older = packWith("A pack", runnable = true).copy(status = "boots")
        val newer = packWith("Z pack", runnable = true).copy(status = "boots")
        older.pack.setLastModified(1_000_000L)
        newer.pack.setLastModified(2_000_000L)
        assertEquals(newer.pack, LibraryLogic.preferPack(older, true, newer, true).first.pack)
        assertEquals(newer.pack, LibraryLogic.preferPack(newer, true, older, true).first.pack)
    }

    /** 旧包的处理：没存档就删，有存档而新包没有就搬过去，两边都有就都不动 */
    @Test
    fun `an older pack is removed, its saves move, and two packs with saves stay`() {
        assertEquals(OlderPackAction.REMOVE, LibraryLogic.olderPackAction(oldHasSaves = false, newHasSaves = false))
        assertEquals(OlderPackAction.MOVE_SAVES_AND_REMOVE, LibraryLogic.olderPackAction(oldHasSaves = true, newHasSaves = false))
        assertEquals(OlderPackAction.KEEP, LibraryLogic.olderPackAction(oldHasSaves = true, newHasSaves = true))
        assertEquals(OlderPackAction.REMOVE, LibraryLogic.olderPackAction(oldHasSaves = false, newHasSaves = true))
    }

    /** 只有清单的空壳不是移植：不管它多新，都比不过有宿主的那个包（卡片得显示真的那个） */
    @Test
    fun `a pack without a host never wins over the port it sits beside`() {
        val port = port(hostExists = true).copy(playerApiRevision = 0, name = "Real")   // 有宿主，前端旧了
        val stub = packWith("Stub", runnable = false)                                   // 只有清单，没有宿主
        stub.pack.setLastModified(2_000_000L)
        port.pack.setLastModified(1_000_000L)
        assertEquals(port.pack, LibraryLogic.preferPack(port, true, stub, true).first.pack)
        assertEquals(port.pack, LibraryLogic.preferPack(stub, true, port, true).first.pack)
    }

    @Test
    fun `a remembered source path supplies the label even when the file is gone`() {
        val card = entry(source = "/sdcard/Games/Hades II [0100000000010000].nsp")
        assertEquals("Hades II", card.label)
    }

    @Test
    fun `a filename title claim cannot bind another game but renamed files are accepted`() {
        assertTrue(LibraryLogic.sourceMatchesTitle("Game [0100000000010000].nsp", "0100000000010000"))
        assertTrue(!LibraryLogic.sourceMatchesTitle("Other [0100000000020000].nsp", "0100000000010000"))
        assertTrue(LibraryLogic.sourceMatchesTitle("downloaded-game.nsp", "0100000000010000"))
    }

    /** 正在构建的游戏不能删，别的游戏能删（库页可见时本来就没有游戏在跑） */
    @Test
    fun `a game that is being ported cannot be deleted`() {
        val path = "/sdcard/Games/Game.nsp"
        assertEquals(GameCleanup.Outcome.BUSY_PORTING,
                     LibraryLogic.removalBlocked(portRunning = true, portGameFile = path, gamePath = path,
                                                titleId = "0100000000010000"))
        // 别的游戏在构建：这个游戏照样能删
        assertEquals(null,
                     LibraryLogic.removalBlocked(portRunning = true, portGameFile = "/sdcard/Games/Other.nsp",
                                                gamePath = path, titleId = "0100000000010000"))
        // 什么都没在跑：能删
        assertEquals(null,
                     LibraryLogic.removalBlocked(portRunning = false, portGameFile = "", gamePath = path,
                                                titleId = "0100000000010000"))
    }

    /**
     * 门的两层决策（解析在外层）：账本里没游戏在跑 → 启动；跑的正是这一个 → 带回前台；
     * 跑的是别的（含账本丢了身份的冷启动）→ 拒绝。进程的事不进决策：那是账本的唯一事实。
     */
    @Test
    fun `the gate starts a game when nothing runs`() {
        assertEquals(LaunchDecision.START,
                     LibraryLogic.launchDecision(running = false, runningTitleId = "",
                                                requestedTitleId = "0100000000010000"))
    }

    @Test
    fun `the gate brings the running game back when it is the one asked for`() {
        assertEquals(LaunchDecision.RESUME,
                     LibraryLogic.launchDecision(running = true, runningTitleId = "0100000000010000",
                                                requestedTitleId = "0100000000010000"))
        // title ID 大小写不敏感：卡上的 ID 与包清单里的写法一致才算同一个游戏
        assertEquals(LaunchDecision.RESUME,
                     LibraryLogic.launchDecision(running = true, runningTitleId = "0100000000010000",
                                                requestedTitleId = "0100000000010000".lowercase()))
    }

    @Test
    fun `the gate refuses another game while one is running`() {
        assertEquals(LaunchDecision.REFUSE,
                     LibraryLogic.launchDecision(running = true, runningTitleId = "0100000000020000",
                                                requestedTitleId = "0100000000010000"))
    }

    @Test
    fun `a running game without a known identity refuses like any other game`() {
        // 冷启动没读回身份（玩家刚升级 App）：任何启动请求都按「有别的游戏在跑」处理
        assertEquals(LaunchDecision.REFUSE,
                     LibraryLogic.launchDecision(running = true, runningTitleId = "",
                                                requestedTitleId = "0100000000010000"))
    }

    /** 搬移：正在构建 / 正在搬，都不能搬 */
    @Test
    fun `a pack cannot be moved while the game is busy`() {
        val path = "/games/Game [0100000000010000].nsp"
        assertEquals(GameCleanup.Outcome.BUSY_MOVING,
                     LibraryLogic.moveBlocked(portRunning = false, portGameFile = "", gamePath = path,
                                              titleId = "0100000000010000", moving = true))
        assertEquals(GameCleanup.Outcome.BUSY_PORTING,
                     LibraryLogic.moveBlocked(portRunning = true, portGameFile = path, gamePath = path,
                                              titleId = "0100000000010000", moving = false))
        assertNull(
            LibraryLogic.moveBlocked(portRunning = false, portGameFile = "", gamePath = path,
                                     titleId = "0100000000010000", moving = false))
    }
}
