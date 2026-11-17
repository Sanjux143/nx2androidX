package com.nx.android.appstate

import com.nx.android.data.library.GameCleanupFiles
import com.nx.android.data.library.LibraryFileScanner
import com.nx.android.data.library.PackMoverFiles
import com.nx.android.domain.GameCleanup
import com.nx.android.domain.GameEntry
import com.nx.android.domain.GameConfig
import com.nx.android.domain.LibraryLogic
import com.nx.android.domain.PackMover
import com.nx.android.domain.PortablePack
import com.nx.android.domain.library.GameLibraryRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Serializes destructive library actions and rechecks task/runtime guards at the IO boundary. */
@Singleton
class LibraryOperationCoordinator @Inject constructor(
    private val port: PortState,
    private val repository: GameLibraryRepository,
) {
    private val moving = HashSet<String>()

    fun beginBuild(game: String, config: GameConfig): Boolean = synchronized(moving) {
        if (moving.isNotEmpty() || port.running) return@synchronized false
        port.begin(game, config)
        true
    }

    fun remove(entry: GameEntry): GameCleanup.Result = synchronized(moving) {
        // 导入的游戏：那个文件夹是玩家的文件，删它等于替玩家删东西 —— 只忘路径
        if (entry.isImported) return@synchronized removeImported(entry)
        removalBlockedLocked(entry)?.let { return@synchronized GameCleanup.Result(it) }
        val plan = planRemoval(entry)
        removalBlockedLocked(entry)?.let { return@synchronized GameCleanup.Result(it) }
        val unreachable = repository.unreachablePacks(entry)
        val deleted = GameCleanupFiles.delete(plan)
        val clean = deleted.failed.isEmpty() && unreachable.isEmpty()
        if (clean) repository.forget(entry.titleId)
        GameCleanup.Result(
            outcome = if (clean) GameCleanup.Outcome.DONE else GameCleanup.Outcome.FAILED,
            workspaceKnown = plan.workspaceKnown,
            failed = deleted.failed + unreachable,
        )
    }

    /**
     * 移出一个导入的游戏：只忘掉那个路径。
     *
     * 那个文件夹是玩家的文件（可能在卡上），App 不删它、也不碰它 —— 它只是不再出现在库里。
     */
    fun removeImported(entry: GameEntry): GameCleanup.Result = synchronized(moving) {
        removalBlockedLocked(entry)?.let { return@synchronized GameCleanup.Result(it) }
        val path = entry.imported?.path ?: return@synchronized GameCleanup.Result(GameCleanup.Outcome.FAILED)
        repository.forgetImported(path)
        GameCleanup.Result(outcome = GameCleanup.Outcome.DONE, workspaceKnown = true)
    }

    fun clearCache(entry: GameEntry): GameCleanup.Result = synchronized(moving) {
        removalBlockedLocked(entry)?.let { return@synchronized GameCleanup.Result(it) }
        val plan = planCache(entry)
        removalBlockedLocked(entry)?.let { return@synchronized GameCleanup.Result(it) }
        // 数据包读的是工作区里的 RomFS：单独删工作区，游戏就跑不起来了（要删就整个删）
        if (plan.workspaceInUse) return@synchronized GameCleanup.Result(GameCleanup.Outcome.WORKSPACE_IN_USE)
        val deleted = GameCleanupFiles.delete(plan)
        GameCleanup.Result(
            outcome = if (deleted.failed.isEmpty()) GameCleanup.Outcome.DONE else GameCleanup.Outcome.FAILED,
            workspaceKnown = plan.workspaceKnown,
            failed = deleted.failed,
        )
    }

    fun movePack(entry: GameEntry, targetDir: File, onProgress: (PackMover.Progress) -> Unit): PackMover.Result {
        moveBlocked(entry)?.let { return PackMover.Result.fail(it.name.lowercase()) }
        val pack = entry.pack?.pack ?: return PackMover.Result.fail("the game has no pack")
        if (!synchronized(moving) { moving.add(entry.titleId) }) return PackMover.Result.fail("the pack is being moved already")
        try {
            moveBlocked(entry, includeMoving = false)?.let { return PackMover.Result.fail(it.name.lowercase()) }
            val result = PackMoverFiles.move(pack, targetDir, onProgress)
            if (result.ok) repository.rememberPackPath(entry.titleId, File(targetDir, pack.name).absolutePath)
            return result
        } finally {
            synchronized(moving) { moving.remove(entry.titleId) }
        }
    }

    fun removalBlocked(entry: GameEntry): GameCleanup.Outcome? = synchronized(moving) { removalBlockedLocked(entry) }

    fun moveBlocked(entry: GameEntry): GameCleanup.Outcome? = moveBlocked(entry, includeMoving = true)

    /** 导出一个完成的移植：一个文件夹（数据包 + 散装的游戏文件） */
    fun exportPack(entry: GameEntry, targetDir: File, onProgress: (PortablePack.Progress) -> Unit): PortablePack.Result =
        repository.exportPack(entry, targetDir, onProgress)

    /** 登记一个导入的文件夹（路径就是它的身份） */
    fun registerImported(dir: File, validation: PortablePack.Validation): PortablePack.Imported? =
        repository.registerImported(dir, validation)

    /** 忘掉一个导入的文件夹（文件留在原地） */
    fun forgetImported(path: String) = repository.forgetImported(path)

    fun planRemoval(entry: GameEntry): GameCleanup.Plan {
        val packs = repository.packsOf(entry)
        val workspace = entry.work ?: repository.existingWorkspace(entry.titleId)
        val partials = if (port.running) emptyList() else repository.partialPacks()
        // 统一目录里那份运行库是唯一正本：删游戏不删它，它就成了永久孤儿（清构建缓存不删它——那是能再生的东西之外的正本）
        val runtime = repository.runtimeDir(entry.titleId)
        return GameCleanup.plan(entry.titleId, packs, workspace, partials, runtime = runtime)
    }

    fun planCache(entry: GameEntry): GameCleanup.Plan {
        val workspace = entry.work ?: repository.existingWorkspace(entry.titleId)
        val partials = if (port.running) emptyList() else repository.partialPacks()
        return GameCleanup.plan(entry.titleId, emptyList(), workspace, partials, workspaceInUse = workspaceInUse(entry, workspace))
    }

    /**
     * 数据包读的是工作区里的 RomFS（移植时没有复制一份进包，省一份游戏的体积）：
     * 那个文件夹是游戏的一部分，不能单独删。
     */
    private fun workspaceInUse(entry: GameEntry, workspace: File?): Boolean {
        if (workspace == null) return false
        return repository.packsOf(entry).any { pack ->
            val at = LibraryFileScanner.portFromPack(pack)?.romfsAt.orEmpty()
            GameCleanup.packReadsWorkspace(at, workspace)
        }
    }

    private fun removalBlockedLocked(entry: GameEntry): GameCleanup.Outcome? {
        if (entry.titleId in moving) return GameCleanup.Outcome.BUSY_MOVING
        return LibraryLogic.removalBlocked(
            portRunning = port.running, portGameFile = port.gameFile, gamePath = entry.sourcePath,
            titleId = entry.titleId, packPath = entry.pack?.pack?.absolutePath,
        )
    }

    private fun moveBlocked(entry: GameEntry, includeMoving: Boolean): GameCleanup.Outcome? = synchronized(moving) {
        LibraryLogic.moveBlocked(
            portRunning = port.running, portGameFile = port.gameFile, gamePath = entry.sourcePath,
            titleId = entry.titleId, packPath = entry.pack?.pack?.absolutePath,
            moving = includeMoving && entry.titleId in moving,
        )
    }
}
