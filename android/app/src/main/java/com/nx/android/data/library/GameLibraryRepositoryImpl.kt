package com.nx.android.data.library

import android.content.Context
import android.util.Log
import com.nx.android.core.storage.AppPaths
import com.nx.android.data.PrefsStore
import com.nx.android.data.PrefsStore.Companion.LIB_IMPORTED
import com.nx.android.data.PrefsStore.Companion.LIB_MEMORIES_MIGRATED
import com.nx.android.data.PrefsStore.Companion.LIB_PACKS
import com.nx.android.data.PrefsStore.Companion.LIB_PORTS
import com.nx.android.data.PrefsStore.Companion.LIB_SOURCES
import com.nx.android.data.StorageVolumes
import com.nx.android.domain.GameIdentity
import com.nx.android.domain.GameEntry
import com.nx.android.domain.LibraryLogic
import com.nx.android.domain.PortablePack
import com.nx.android.domain.Ported
import com.nx.android.domain.GameCleanup
import com.nx.android.domain.StorageUsage
import com.nx.android.domain.library.GameLibraryRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

@Singleton
class GameLibraryRepositoryImpl @Inject constructor(
    private val prefs: PrefsStore,
    private val paths: AppPaths,
    @ApplicationContext private val context: Context,
) : GameLibraryRepository {
    private var packPaths = HashMap<String, String>()
    private var sources = HashMap<String, String>()
    private var imported = LinkedHashMap<String, PortablePack.Imported>()

    init { loadMemories() }

    @Synchronized
    override fun scan(): List<GameEntry> {
        val started = System.currentTimeMillis()
        val byTitle = HashMap<String, Ported>()
        val byTitleInternal = HashMap<String, Boolean>()
        val removable = StorageVolumes.removablePortsDirs(context).map { it.second }
        for (dir in removable) {
        for ((titleId, candidate) in LibraryFileScanner.outIndex(dir, paths.runtimes)) {
                val chosen = LibraryLogic.preferPack(byTitle[titleId], byTitleInternal[titleId] == true,
                    candidate, candidateInternal = false)
                byTitle[titleId] = chosen.first
                byTitleInternal[titleId] = chosen.second
            }
        }
        for ((titleId, candidate) in LibraryFileScanner.outIndex(paths.ports, paths.runtimes)) {
            val chosen = LibraryLogic.preferPack(byTitle[titleId], byTitleInternal[titleId] == true,
                candidate, candidateInternal = true)
            byTitle[titleId] = chosen.first
            byTitleInternal[titleId] = chosen.second
        }
        val work = LibraryFileScanner.workIndex(paths.work)
        val result = LibraryLogic.merge(byTitle, work, sources, importedEntries())
        byTitle.forEach { (titleId, pack) -> packPaths[titleId] = pack.pack.absolutePath }
        savePackPaths()
        Log.i("nx2a", "library scan: ${System.currentTimeMillis() - started} ms, " +
            "${byTitle.size} packs on ${portsDirs().size} volumes, ${result.size} games")
        return result
    }

    /**
     * The imported folders as library cards: each one is its own card (the path is its identity),
     * even when the game is in the library already. A folder that cannot be reached (the card is
     * not plugged in) keeps its card: the player sees that it is out of reach.
     */
    private fun importedEntries(): List<GameEntry> = imported.values.mapNotNull { record ->
        val dir = File(record.path)
        val ported = LibraryFileScanner.portFromPack(dir, paths.runtimes)
        if (ported != null) GameEntry(ported.titleId, ported, null, null, record)
        else GameEntry(record.titleId, null, null, null, record)
    }

    override fun portsDirs(): List<File> = listOf(paths.ports) + StorageVolumes.removablePortsDirs(context).map { it.second }

    /**
     * 库里每个游戏的占用来源：工作区与它的数据包。
     *
     * 占用统计照着这个清单去量（见 `appstate/StorageUsageState.kt`）：一项 = 一个游戏，
     * 项里若干段 = 若干目录（工作区 / 数据包）。每个卷上可能各有一份数据包，够不着的
     * 那份（卡不在）标成「够不着」，不当成 0。
     */
    @Synchronized
    override fun usageItems(games: List<GameEntry>): List<StorageUsage.Item> {
        val items = ArrayList<StorageUsage.Item>()
        val claimedPacks = HashSet<String>()
        val claimedWork = HashSet<String>()

        for (e in games) {
            val parts = ArrayList<StorageUsage.Part>()
            val work = e.work ?: GameCleanup.workspaceOf(e.titleId, workRoot())
            if (work != null) {
                parts += StorageUsage.Part(StorageUsage.Role.WORKSPACE, work.absolutePath)
                claimedWork += work.absolutePath
            }
            for (p in packsOf(e)) {
                parts += StorageUsage.Part(StorageUsage.Role.PACK, p.absolutePath)
                claimedPacks += p.absolutePath
            }
            for (p in unreachablePacks(e)) {
                parts += StorageUsage.Part(StorageUsage.Role.PACK, p.absolutePath, state = StorageUsage.State.UNKNOWN)
            }
            // 导入的包：文件在 App 之外（玩家自己挑的目录），App 不拥有那些字节，也不去量它
            if (e.isImported && e.imported != null) {
                parts += StorageUsage.Part(StorageUsage.Role.OUTSIDE, e.imported.path, state = StorageUsage.State.UNKNOWN)
            }
            items += StorageUsage.Item(
                id = e.key,
                name = e.label,
                icon = e.pack?.icon?.absolutePath.orEmpty(),
                // 快照要是不变的值：这里给一份拷贝，别把还在拼的列表挂出去
                parts = parts.toList(),
            )
        }

        // 兜底：占了地方却不对应库里任何游戏的东西（认不出是哪个游戏的工作区）。它们没人管，数字也该有人报。
        val orphans = ArrayList<StorageUsage.Part>()
        for (d in portsDirs()) {
            for (p in d.listFiles() ?: emptyArray()) {
                if (!p.isDirectory || p.absolutePath in claimedPacks) continue
                orphans += StorageUsage.Part(StorageUsage.Role.PACK, p.absolutePath)
            }
        }
        for (w in workRoot().listFiles() ?: emptyArray()) {
            if (!w.isDirectory || w.absolutePath in claimedWork) continue
            orphans += StorageUsage.Part(StorageUsage.Role.WORKSPACE, w.absolutePath)
        }
        if (orphans.isNotEmpty()) {
            items += StorageUsage.Item(id = StorageUsage.ORPHAN_ID, name = "", parts = orphans.toList())
        }
        return items
    }


    override fun internalPortsDir(): File = paths.ports
    override fun workRoot(): File = paths.work
    override fun existingWorkspace(titleId: String): File? =
        com.nx.android.domain.GameCleanup.workspaceOf(titleId, paths.work)?.takeIf(File::exists)
    override fun runtimeDir(titleId: String): File = File(paths.runtimes, titleId.lowercase())
    override fun partialPacks(): List<File> = portsDirs().flatMap(LibraryFileScanner::partials)
    override fun workFolderIn(from: Long, to: Long): String? = LibraryFileScanner.workFolderIn(paths.work, from, to)
    override fun portFromPack(pack: File): Ported? = LibraryFileScanner.portFromPack(pack, paths.runtimes)
    override fun packDirOf(entry: GameEntry): File? = entry.pack?.pack?.parentFile
    override fun packDirOf(source: File, games: List<GameEntry>): File? =
        games.firstOrNull { it.sourcePath == source.absolutePath }?.pack?.pack?.parentFile

    override fun packsOf(entry: GameEntry): List<File> {
        // 导入的包不在 App 自己的目录里：它不在这些卷上（它的位置是玩家挑的那个文件夹）
        if (entry.isImported) return emptyList()
        val packs = ArrayList<File>()
        for (dir in portsDirs()) {
            for (pack in dir.listFiles() ?: emptyArray()) {
                if (!pack.isDirectory || !pack.name.endsWith("-data")) continue
                val titleId = LibraryFileScanner.manifestTitleId(pack) ?: continue
                if (titleId.equals(entry.titleId, ignoreCase = true)) packs += pack
            }
        }
        return packs
    }

    @Synchronized
    override fun unreachablePacks(entry: GameEntry): List<File> {
        // 导入的包够不着就是够不着（卡不在）：它不在这份记忆里
        if (entry.isImported) return emptyList()
        val path = packPaths[entry.titleId] ?: return emptyList()
        val pack = File(path)
        if (pack.exists()) return emptyList()
        val volumeRoot = LibraryFileScanner.volumeRootOf(pack) ?: return emptyList()
        return if (volumeRoot.isDirectory) emptyList() else listOf(pack)
    }

    @Synchronized
    override fun rememberPackPath(titleId: String, path: String) {
        packPaths[titleId] = path
        savePackPaths()
    }

    @Synchronized
    override fun rememberSource(titleId: String, source: String) {
        sources[titleId] = source
        saveSources()
    }

    @Synchronized
    override fun noteDone(source: String, pack: String): String? {
        if (source.isEmpty() || pack.isEmpty()) return null
        val ported = LibraryFileScanner.portFromPack(File(pack)) ?: return null
        sources[ported.titleId] = source
        packPaths[ported.titleId] = ported.pack.absolutePath
        saveSources()
        savePackPaths()
        // the same game under another pack name (the pack's name is the profile's ipa): the older one goes, its saves come along
        OlderPackCleanup.removeOlderPacks(ported.pack, portsDirs()).forEach { Log.i("nx2a", it) }
        return ported.titleId
    }

    @Synchronized
    override fun forget(titleId: String) {
        sources.remove(titleId)
        packPaths.remove(titleId)
        saveSources()
        savePackPaths()
    }

    /**
     * 登记一个导入的文件夹：路径就是它的身份。
     *
     * 同一个文件夹不登记两次（它已经在库里了）；游戏的数据一个字节都不动，只是把 DLC 与系统
     * 数据的行指到它现在的位置。
     */
    @Synchronized
    override fun registerImported(dir: File, validation: PortablePack.Validation): PortablePack.Imported? {
        val path = dir.absolutePath
        if (imported.containsKey(path)) return null
        val record = PortablePack.Imported(path, validation.titleId, validation.name)
        imported[path] = record
        saveImported()
        PortablePackFiles.placeEnv(dir)
        return record
    }

    /** 忘掉一个导入的文件夹：文件留在原地（那是玩家的文件）。 */
    @Synchronized
    override fun forgetImported(path: String) {
        if (imported.remove(path) != null) saveImported()
    }

    @Synchronized
    override fun importedPacks(): List<PortablePack.Imported> = imported.values.toList()

    /**
     * 导出一个完成的移植：一个文件夹（数据包 + 散装的游戏文件）。
     *
     * 导出不动 App 里的任何东西：游戏照常能玩。
     *
     * 这里不加锁：导出要拷十几 GB，锁住这段时间会把扫库与存储统计一起堵住。它只读参数里的包，
     * 不碰这份记忆。
     */
    override fun exportPack(entry: GameEntry, targetDir: File,
                            onProgress: (PortablePack.Progress) -> Unit): PortablePack.Result {
        val pack = entry.pack ?: return PortablePack.Result.fail("no_pack")
        return PortablePackFiles.export(pack.pack, pack.romfsAt, targetDir, entry.label, onProgress)
    }

    private fun loadMemories() {
        packPaths = readMap(LIB_PACKS)
        sources = readMap(LIB_SOURCES)
        imported = readImported()
        if (!prefs.bool(LIB_MEMORIES_MIGRATED)) {
            try {
                val legacy = prefs.json(LIB_PORTS)
                for (source in legacy.keys()) {
                    val value = legacy.get(source)
                    val titleId = (value as? JSONObject)?.optString("title").orEmpty()
                    val path = (value as? JSONObject)?.optString("path") ?: (value as? String).orEmpty()
                    if (titleId.isEmpty()) continue
                    if (path.isNotEmpty() && packPaths[titleId] == null) packPaths[titleId] = path
                    if (sources[titleId] == null) sources[titleId] = source
                }
            } catch (_: Exception) { }
            prefs.put(LIB_MEMORIES_MIGRATED, true)
        }
        saveSources()
        savePackPaths()
    }

    private fun readMap(key: String): HashMap<String, String> = try {
        val map = HashMap<String, String>()
        val json = prefs.json(key)
        for (titleId in json.keys()) json.optString(titleId).takeIf { it.isNotEmpty() }?.let { map[titleId] = it }
        map
    } catch (_: Exception) { HashMap() }

    private fun savePackPaths() = saveMap(LIB_PACKS, packPaths)
    private fun saveSources() = saveMap(LIB_SOURCES, sources)

    private fun saveMap(key: String, values: Map<String, String>) {
        val json = JSONObject()
        values.forEach { (titleId, path) -> json.put(titleId, path) }
        prefs.putJson(key, json)
    }

    /**
     * 登记过的导入文件夹：路径 → 它是什么游戏。
     *
     * 记着游戏的名字与 title ID 是为了文件夹够不着的时候（卡没插）卡还在库里。
     */
    private fun readImported(): LinkedHashMap<String, PortablePack.Imported> {
        val out = LinkedHashMap<String, PortablePack.Imported>()
        try {
            val json = prefs.json(LIB_IMPORTED)
            for (path in json.keys()) {
                val o = json.optJSONObject(path) ?: continue
                val titleId = o.optString("title_id")
                if (titleId.isEmpty()) continue
                out[path] = PortablePack.Imported(path, titleId, o.optString("name"))
            }
        } catch (_: Exception) { }
        return out
    }

    private fun saveImported() {
        val json = JSONObject()
        imported.forEach { (path, record) ->
            json.put(path, JSONObject().put("title_id", record.titleId).put("name", record.name))
        }
        prefs.putJson(LIB_IMPORTED, json)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class GameLibraryDataModule {
    @Binds
    @Singleton
    abstract fun bindGameLibraryRepository(impl: GameLibraryRepositoryImpl): GameLibraryRepository
}
