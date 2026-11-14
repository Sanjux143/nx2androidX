package com.nx.android.data.library

import android.content.Context
import android.util.Log
import com.nx.android.core.storage.AppPaths
import com.nx.android.data.PrefsStore
import com.nx.android.data.PrefsStore.Companion.LIB_MEMORIES_MIGRATED
import com.nx.android.data.PrefsStore.Companion.LIB_PACKS
import com.nx.android.data.PrefsStore.Companion.LIB_PORTS
import com.nx.android.data.PrefsStore.Companion.LIB_SOURCES
import com.nx.android.data.StorageVolumes
import com.nx.android.domain.GameIdentity
import com.nx.android.domain.GameEntry
import com.nx.android.domain.LibraryLogic
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

    init { loadMemories() }

    @Synchronized
    override fun scan(): List<GameEntry> {
        val started = System.currentTimeMillis()
        val byTitle = HashMap<String, Ported>()
        val byTitleInternal = HashMap<String, Boolean>()
        val removable = StorageVolumes.removablePortsDirs(context).map { it.second }
        for (dir in removable) {
        for ((titleId, candidate) in LibraryFileScanner.outIndex(dir)) {
                val chosen = LibraryLogic.preferPack(byTitle[titleId], byTitleInternal[titleId] == true,
                    candidate, candidateInternal = false)
                byTitle[titleId] = chosen.first
                byTitleInternal[titleId] = chosen.second
            }
        }
        for ((titleId, candidate) in LibraryFileScanner.outIndex(paths.ports)) {
            val chosen = LibraryLogic.preferPack(byTitle[titleId], byTitleInternal[titleId] == true,
                candidate, candidateInternal = true)
            byTitle[titleId] = chosen.first
            byTitleInternal[titleId] = chosen.second
        }
        val work = LibraryFileScanner.workIndex(paths.work)
        val result = LibraryLogic.merge(byTitle, work, sources)
        byTitle.forEach { (titleId, pack) -> packPaths[titleId] = pack.pack.absolutePath }
        savePackPaths()
        Log.i("nx2a", "library scan: ${System.currentTimeMillis() - started} ms, " +
            "${byTitle.size} packs on ${portsDirs().size} volumes, ${result.size} games")
        return result
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
            items += StorageUsage.Item(
                id = e.titleId,
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
    override fun partialPacks(): List<File> = portsDirs().flatMap(LibraryFileScanner::partials)
    override fun workFolderIn(from: Long, to: Long): String? = LibraryFileScanner.workFolderIn(paths.work, from, to)
    override fun portFromPack(pack: File): Ported? = LibraryFileScanner.portFromPack(pack)
    override fun packDirOf(entry: GameEntry): File? = entry.pack?.pack?.parentFile
    override fun packDirOf(source: File, games: List<GameEntry>): File? =
        games.firstOrNull { it.sourcePath == source.absolutePath }?.pack?.pack?.parentFile

    override fun packsOf(entry: GameEntry): List<File> {
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

    private fun loadMemories() {
        packPaths = readMap(LIB_PACKS)
        sources = readMap(LIB_SOURCES)
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
}

@Module
@InstallIn(SingletonComponent::class)
abstract class GameLibraryDataModule {
    @Binds
    @Singleton
    abstract fun bindGameLibraryRepository(impl: GameLibraryRepositoryImpl): GameLibraryRepository
}
