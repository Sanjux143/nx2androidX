package com.nx.android.appstate

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nx.android.domain.GameCleanup
import com.nx.android.domain.GameConfig
import com.nx.android.domain.library.GameConfigRepository
import com.nx.android.domain.library.GameLibraryRepository
import com.nx.android.domain.GameEntry
import com.nx.android.domain.GameStatus
import com.nx.android.domain.LibraryLogic
import com.nx.android.domain.PackMover
import com.nx.android.data.AppLog
import com.nx.android.domain.Ported
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The library: the games the player built, what became of each one, and each game's own port options.
 *
 * A card's identity is the game's **title ID**, not a file path: the source file may be moved away or deleted
 * (players do it to free space), and the game stays in the library — playable, with its pack.
 *
 * The card list is the union of two sources, merged by title ID:
 *  - **the data packs** in the app's own folders (the private one and the removable card's), read from their
 *    manifests;
 *  - **the work folders** the porter left behind (`<title ID>_build`) — a build that failed or whose pack was
 *    deleted still has its workspace, so the game stays in the library and the player can clean it up.
 *
 * Two small memories, neither of them an identity:
 *  - **the pack path** each title ID was last seen at — the storage page says "out of reach" for the pack on a
 *    card that is not plugged in;
 *  - **the source file** each title ID was last built from — the rebuild button uses it (the player does not have
 *    to pick the file again when it has not moved).
 *
 * [portsDir] is the app's own ports folder: the data packs the porter writes there, found by their title ID.
 * [workRoot] is the porter's work folder: the unpacked game and the shader work of every build, one folder per
 * title ID. Deleting a game takes both away.
 */
@Singleton
class LibraryState @Inject constructor(private val port: PortState,
                   private val libraryRepository: GameLibraryRepository,
                   private val configRepository: GameConfigRepository,
                   private val operations: LibraryOperationCoordinator,
                   private val profiles: com.nx.android.data.library.ProfileReader) {

    /** 库里的卡：一个游戏一张 */
    var games by mutableStateOf(listOf<GameEntry>()); private set

    var scanning by mutableStateOf(false); private set

    /** 行视图里选中的游戏（hero），随列表伸缩 */
    var heroIndex by mutableStateOf(0); private set

    /** 打开配置面板的游戏 */
    var selected by mutableStateOf<GameEntry?>(null)

    /** 构建页聚焦的游戏：库的「▶ 构建」把它带过去（NX2Apple 的 activeBuildID 对应物） */
    var buildsTarget by mutableStateOf<File?>(null)

    private val scanExecutor = Executors.newSingleThreadExecutor { Thread(it, "library-scan") }
    private val scanCoordinator = LatestScanCoordinator(
        executor = scanExecutor,
        scan = {
            libraryRepository.scan().also { result ->
                runCatching { configRepository.migrateLegacyConfigs(result) }
                    .onFailure { AppLog.line("Library config migration failed: ${it.message}") }
            }
        },
        publish = { games = it },
        setScanning = { scanning = it },
        onFailure = { AppLog.line("Library scan failed: ${it.message}") },
    )

    /** 行视图选中一个游戏（带过滤后的列表，越界收回） */
    fun selectHero(index: Int, count: Int) {
        heroIndex = if (count > 0) index.coerceIn(0, count - 1) else 0
    }

    // ---- 扫库（包与工作区，按 title ID 合成卡） ----------------------------------------------------------------------

    /**
     * 扫一遍：包（App 自己的目录）与工作区（移植器建的），按 title ID 合成卡。
     *
     * 源文件不在了不算没这个游戏：包或工作区还在，卡就在。
     */
    fun rescan() {
        scanCoordinator.request()
    }

    /** 端口目录：私有的一个，加上插着的可移动卡上的那些 */
    fun portsDirs(): List<File> = libraryRepository.portsDirs()

    /** App 私有存储里那个端口目录（包都在这儿，除非玩家把它搬到了卡上） */
    fun internalPortsDir(): File = libraryRepository.internalPortsDir()

    /** 这个游戏的包现在在哪：私有的目录，还是卡上那个（重建跟着它走） */
    fun packDirOf(e: GameEntry): File? = libraryRepository.packDirOf(e)

    /** 构建页拿的是源文件：按它找到那张卡的包目录（重建时新包落在那儿） */
    fun packDirOf(f: File): File? = libraryRepository.packDirOf(f, games)

    fun portFromPack(file: File): Ported? = libraryRepository.portFromPack(file)

    /** 这个游戏在各个卷上的数据包（每个卷可能各有一份） */
    fun packsOf(e: GameEntry): List<File> = libraryRepository.packsOf(e)

    /** 这个游戏需要什么：App 自带的档案（图形接口、联机、设备要求） */
    fun profileOf(titleId: String): com.nx.android.data.library.GameProfile? = profiles.of(titleId)

    /** 这个游戏的状态 */
    fun statusOf(e: GameEntry): GameStatus =
        LibraryLogic.statusOf(e, port.running, port.gameFile, port.startedAt)

    /** 构建页拿的是源文件：按它找到这张卡（找不到就当没构建过） */
    fun statusOf(f: File): GameStatus = entryOf(f)?.let { statusOf(it) } ?: GameStatus.NOT_PORTED

    /** 源文件对应的那张卡（它上次是从这个文件构建的） */
    fun entryOf(f: File): GameEntry? {
        val path = f.absolutePath
        return games.firstOrNull { it.sourcePath == path }
    }

    /** 这个游戏完成的移植（它的包） */
    fun portOf(e: GameEntry): Ported? = e.pack

    /** 构建页拿的是源文件：按它找到那张卡的包 */
    fun portOf(f: File): Ported? = entryOf(f)?.pack

    /**
     * 删掉一个游戏：把它移出游戏库，删掉它的构建数据（数据包、工作区、半成品包）。
     * 游戏源文件是玩家的，不碰。正在构建或正在运行时不动任何东西。
     */
    fun remove(e: GameEntry): GameCleanup.Result {
        val result = operations.remove(e)
        if (result.outcome !in setOf(GameCleanup.Outcome.BUSY_PORTING, GameCleanup.Outcome.BUSY_RUNNING, GameCleanup.Outcome.BUSY_MOVING)) rescan()
        return result
    }

    fun clearCache(e: GameEntry): GameCleanup.Result {
        val result = operations.clearCache(e)
        if (result.outcome !in setOf(GameCleanup.Outcome.BUSY_PORTING, GameCleanup.Outcome.BUSY_RUNNING, GameCleanup.Outcome.BUSY_MOVING)) rescan()
        return result
    }

    fun removalBlocked(e: GameEntry): GameCleanup.Outcome? = operations.removalBlocked(e)

    fun planRemoval(e: GameEntry): GameCleanup.Plan = operations.planRemoval(e)

    fun planCache(e: GameEntry): GameCleanup.Plan = operations.planCache(e)

    fun movePack(e: GameEntry, targetDir: File, onProgress: (PackMover.Progress) -> Unit = {}): PackMover.Result =
        operations.movePack(e, targetDir, onProgress).also { if (it.ok) rescan() }

    fun moveBlocked(e: GameEntry): GameCleanup.Outcome? = operations.moveBlocked(e)

    /** 卡不在时够不着的包：路径还记得，那个卷却不在了（删不到，要说实话） */
    fun unreachablePacks(e: GameEntry): List<File> = libraryRepository.unreachablePacks(e)

    /** 这个游戏的 title ID */
    fun titleIdOf(e: GameEntry): String? = e.titleId

    /** 一个构建成功了：这个游戏是从哪个源文件建的，记下来（重建时用它） */
    fun noteDone(source: String, pack: String) {
        val titleId = libraryRepository.noteDone(source, pack) ?: return
        configRepository.migrateSourceConfig(source, titleId)
        rescan()
    }

    /**
     * 一个构建没成功（失败、取消、进程被杀）：工作区的目录名里带着它的 title ID，学过来。
     *
     * 一次只跑一个构建，所以这次动过的工作区就是它的——但要看准时间窗：构建开始到结束之间动过的
     * 工作区才是它的（后来那次构建的工作区不算）。
     */
    fun noteFailed(source: String) {
        if (source.isEmpty()) return
        val rec = port.history.firstOrNull { it.game == source } ?: return
        val from = rec.startedAt
        val to = rec.startedAt + rec.seconds * 1000 + 10_000   // 留一点余量（目录的 mtime 是最后一次写入）
        val tid = libraryRepository.workFolderIn(from, to) ?: return
        libraryRepository.rememberSource(tid, source)
        rescan()
    }

    /** The port options a game carries: its own, or the old app's globals as the defaults */
    fun configOf(e: GameEntry): GameConfig = configRepository.forTitle(e.titleId)

    /** 构建页拿的是源文件：按它找到那张卡的配置 */
    fun configOf(f: File): GameConfig = configRepository.forSource(entryOf(f)?.sourcePath ?: f.absolutePath,
        entryOf(f)?.titleId)

    fun setConfig(e: GameEntry, c: GameConfig) = configRepository.set(e.titleId, null, c)

    /** 构建页拿的是源文件：还没构建过的按路径记，构建过的是那张卡的（按 title ID） */
    fun setConfig(f: File, c: GameConfig) = configRepository.set(entryOf(f)?.titleId, f.absolutePath, c)

    /** 库的列表（按名字排序） */
    fun visible(): List<GameEntry> = games
}
