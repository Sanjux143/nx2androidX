package com.nx.android.domain

import java.io.File

/**
 * The library card's status, as pure rules (free of Android, so the tests run on the JVM): the port that runs
 * now paints its game yellow, a finished port (pack and host together) makes the game green, a game whose last
 * build failed is red, a game that has nothing left but its build workspace is grey, everything else is grey —
 * and a pack whose host went away falls back to grey at the next look.
 */
object LibraryLogic {

    /**
     * A title ID present in a filename is an explicit identity claim and must agree with the card.
     * Renamed files have no claim here; the porter will confirm their content when the build starts.
     */
    fun sourceMatchesTitle(candidateName: String, expectedTitleId: String): Boolean {
        val claimed = GameIdentity.titleIdIn(candidateName) ?: return true
        return claimed.equals(expectedTitleId, ignoreCase = true)
    }

    /**
     * 这个游戏现在是什么状态：
     * - 正在构建 → 黄；
     * - 包在且宿主、前端都可用 → 绿，能玩；
     * - 卡片存在但包不完整、旧前端或只有工作区 → 灰，需要构建；
     *
     * 源文件在不在与构建历史都不参与状态判定。
     */
    fun statusOf(entry: GameEntry, portRunning: Boolean, portGameFile: String, workStartedAt: Long): GameStatus = when {
        portRunning && building(entry, portGameFile, workStartedAt) -> GameStatus.PORTING
        entry.pack?.runnable == true -> GameStatus.DONE
        // 导入的包：运行库还没有（另一台手机编的运行库不在这里），第一次运行时由这台手机自己编
        entry.isImported && entry.pack != null && entry.romfsInside -> GameStatus.DONE
        else -> GameStatus.NEEDS_BUILD
    }

    /**
     * 这次构建是不是正在动这个游戏：构建用的源文件是它的，或者是它的工作区正在被写（第一次构建时
     * 还没有源文件的记忆，只能看工作区）。
     */
    private fun building(entry: GameEntry, portGameFile: String, workStartedAt: Long): Boolean {
        if (portGameFile.isNotEmpty() && (entry.sourcePath == portGameFile || entry.pack?.pack?.absolutePath == portGameFile)) return true
        val work = entry.work ?: return false
        return workStartedAt > 0 && work.lastModified() >= workStartedAt
    }

    /**
     * 合成库里的卡：数据包与工作区按 title ID 合成一张卡。
     *
     * 一个 title ID 一张卡：有包有工作区都归它。源文件只是附件（[sources] 记着它上次是从哪个文件
     * 构建的），它不在了也不影响这张卡。
     *
     * 导入的包按它自己的路径单列：同一个游戏可以在库里有一份 App 自己做的和一份导入的，
     * 两份不互相取代。
     */
    fun merge(packs: Map<String, Ported>, work: Map<String, File>, sources: Map<String, String>,
              imported: List<GameEntry> = emptyList()): List<GameEntry> {
        val entries = LinkedHashMap<String, GameEntry>()
        for ((tid, pack) in packs) entries[tid] = GameEntry(tid, pack, work[tid], sources[tid])
        for ((tid, w) in work) {
            if (!entries.containsKey(tid)) entries[tid] = GameEntry(tid, null, w, sources[tid])
        }
        val all = entries.values + imported
        return all.sortedBy { it.label.lowercase() }
    }

    /**
     * Choose one visible pack for a title while retaining the other packs for cleanup.
     *
     * 同一个 title ID 可能有两个包（包名取自档案的 ipa 键：游戏先用临时档案构建过，后来换成正式档案，
     * 包名跟着变了，旧包就留在了那里）。库里只显示一个，比的必须是包本身的好坏，不能看扫到的顺序：
     * 有宿主的包优先（只有清单的空壳不算移植）→ 能跑的优先 → 档案状态（正式调通的压过临时档案的）→
     * 新旧 → 手机自己的存储 → 包名。
     */
    fun preferPack(current: Ported?, currentInternal: Boolean,
                   candidate: Ported, candidateInternal: Boolean): Pair<Ported, Boolean> {
        if (current == null) return candidate to candidateInternal
        val better = if (candidate.hasHost != current.hasHost) candidate.hasHost
            else if (candidate.runnable != current.runnable) candidate.runnable
            else comparePacks(candidate, candidateInternal, current, currentInternal) > 0
        return if (better) candidate to candidateInternal else current to currentInternal
    }

    /** 两个包谁更好：档案状态 → 新旧 → 手机自己的存储 → 包名（跨设备、跨扫描都一样） */
    private fun comparePacks(a: Ported, aInternal: Boolean, b: Ported, bInternal: Boolean): Int {
        val status = statusRank(a.status) - statusRank(b.status)
        if (status != 0) return status
        val made = a.pack.lastModified() - b.pack.lastModified()
        if (made != 0L) return if (made > 0) 1 else -1
        if (aInternal != bInternal) return if (aInternal) 1 else -1
        return a.pack.name.compareTo(b.pack.name)
    }

    /** 档案状态的高低：正式调通的（playable / wip / boots）压过临时档案（experimental） */
    fun statusRank(status: String): Int = when (status.lowercase()) {
        "playable" -> 3
        "wip" -> 2
        "boots" -> 1
        else -> 0
    }

    /**
     * 同 title ID 的旧包怎么处理：旧包没有存档就直接删；有存档而新包没有，就把存档搬进新包再删；
     * 两边都有存档就都不动——玩家的数据不静默销毁。
     */
    fun olderPackAction(oldHasSaves: Boolean, newHasSaves: Boolean): OlderPackAction = when {
        !oldHasSaves -> OlderPackAction.REMOVE
        newHasSaves -> OlderPackAction.KEEP
        else -> OlderPackAction.MOVE_SAVES_AND_REMOVE
    }

    /**
     * 门的两层决策（解析在外层做完，进来的是已解析出的游戏）：账本里有没有游戏在跑？
     * 没在跑 → 启动；跑的正是这一个 → 带回前台（不重启）；跑的是别的 → 拒绝（执行方把在跑的
     * 那个带回前台并说明）。账本没有身份（冷启动后没读回名字）时按「有别的游戏在跑」处理。
     */
    fun launchDecision(running: Boolean, runningTitleId: String, requestedTitleId: String): LaunchDecision = when {
        !running -> LaunchDecision.START
        runningTitleId.equals(requestedTitleId, ignoreCase = true) -> LaunchDecision.RESUME
        else -> LaunchDecision.REFUSE
    }

    /**
     * 这个游戏现在能不能删：正在被移植，或正在运行，都不能删——返回原因；能删返回 null。
     *
     * 正在运行的那个游戏，包里的存档正被写着，删了会坏掉正在玩的这一局。
     */
    fun removalBlocked(portRunning: Boolean, portGameFile: String, gamePath: String?,
                       titleId: String?,
                       packPath: String? = null): GameCleanup.Outcome? = when {
        portRunning && (portGameFile == gamePath || portGameFile == packPath) -> GameCleanup.Outcome.BUSY_PORTING
        else -> null
    }

    /**
     * 这个游戏现在能不能搬它的数据包：正在构建、正在运行、正在搬，都不能搬——返回原因；能搬返回 null。
     *
     * 搬移期间这个游戏的包正在被写：再搬一次或删掉它，都会把那次搬移弄坏。
     */
    fun moveBlocked(portRunning: Boolean, portGameFile: String, gamePath: String?,
                    titleId: String?,
                    moving: Boolean, packPath: String? = null): GameCleanup.Outcome? = when {
        moving -> GameCleanup.Outcome.BUSY_MOVING
        portRunning && (portGameFile == gamePath || portGameFile == packPath) -> GameCleanup.Outcome.BUSY_PORTING
        else -> null
    }
}

/** 点「运行」时门给出的决定：全新启动，还是回到在跑的那个，还是拒绝（有别的游戏在跑） */
enum class LaunchDecision { START, RESUME, REFUSE }

/** 同 title ID 的旧包的处理办法 */
enum class OlderPackAction { REMOVE, MOVE_SAVES_AND_REMOVE, KEEP }
