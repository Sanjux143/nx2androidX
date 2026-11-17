package com.nx.android.domain.library

import com.nx.android.domain.GameEntry
import com.nx.android.domain.PortablePack
import com.nx.android.domain.Ported
import com.nx.android.domain.StorageUsage
import java.io.File

interface GameLibraryRepository {
    fun scan(): List<GameEntry>
    fun portsDirs(): List<File>
    fun internalPortsDir(): File
    fun workRoot(): File
    fun existingWorkspace(titleId: String): File?
    /** 统一目录里这个游戏的运行库文件夹（runtimes/<title ID 小写>）：删游戏时一并清理 */
    fun runtimeDir(titleId: String): File
    fun partialPacks(): List<File>
    fun usageItems(games: List<GameEntry>): List<StorageUsage.Item>
    fun workFolderIn(from: Long, to: Long): String?
    fun portFromPack(pack: File): Ported?
    fun packDirOf(entry: GameEntry): File?
    fun packDirOf(source: File, games: List<GameEntry>): File?
    fun packsOf(entry: GameEntry): List<File>
    fun unreachablePacks(entry: GameEntry): List<File>
    fun rememberPackPath(titleId: String, path: String)
    fun rememberSource(titleId: String, source: String)
    fun noteDone(source: String, pack: String): String?
    fun forget(titleId: String)

    /** 导入的包：登记一个文件夹（路径就是它的身份），返回登记好的那条 */
    fun registerImported(dir: File, validation: PortablePack.Validation): PortablePack.Imported?
    /** 导入的包：忘掉一个文件夹（文件留在原地） */
    fun forgetImported(path: String)
    /** 导入的包：登记过的文件夹 */
    fun importedPacks(): List<PortablePack.Imported>
    /** 导出一个完成的移植：一个文件夹（数据包 + 散装游戏文件） */
    fun exportPack(entry: GameEntry, targetDir: File, onProgress: (PortablePack.Progress) -> Unit): PortablePack.Result
}
