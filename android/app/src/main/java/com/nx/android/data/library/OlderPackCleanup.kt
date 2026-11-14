package com.nx.android.data.library

import com.nx.android.domain.LibraryLogic
import com.nx.android.domain.OlderPackAction
import java.io.File

/**
 * 同一个游戏、同一个 title ID，却有两个包：包名取自档案的 ipa 键，游戏先用临时档案构建过
 * （包名随档案的 ipa 变），后来换成正式档案，旧包就留在了那里。库里只显示一个包（按 title ID
 * 归并），旧的那个玩家看不见；删除又是按 title ID 的，会把两个一起删。所以新包落盘之后，
 * 把旧包收走：没有存档就直接删，有存档就先搬进新包。两边都有存档就都不动——
 * 玩家的数据不静默销毁。
 */
object OlderPackCleanup {

    /**
     * 新包落盘之后，把同 title ID 的旧包收走。→ 给玩家看的每一句话。
     *
     * 旧包在哪个卷上都算（玩家把包搬到卡上时，新旧两个可能在不同的卷）。
     */
    fun removeOlderPacks(newPack: File, portsDirs: List<File>): List<String> {
        val out = ArrayList<String>()
        val titleId = LibraryFileScanner.manifestTitleId(newPack) ?: return out
        val newSaves = savesIn(newPack)
        val seen = HashSet<String>()
        for (dir in portsDirs) {
            for (old in dir.listFiles() ?: emptyArray()) {
                if (!old.isDirectory || old.name == newPack.name) continue
                val key = old.absolutePath
                if (!seen.add(key)) continue
                val oldTitle = LibraryFileScanner.manifestTitleId(old) ?: continue   // not a pack (no manifest): leave it alone
                if (!oldTitle.equals(titleId, ignoreCase = true)) continue   // another game (a regional release has its own title ID)
                when (LibraryLogic.olderPackAction(savesIn(old), newSaves)) {
                    OlderPackAction.KEEP ->
                        out.add("the older pack of this game (${old.name}) holds saves as well: delete it by hand if you do not want it")
                    OlderPackAction.MOVE_SAVES_AND_REMOVE -> {
                        if (!moveSaves(old, newPack)) {   // the saves did not move: the old pack stays
                            out.add("the saves of the older pack of this game (${old.name}) could not be moved: it stays")
                            continue
                        }
                        out.add("the saves of the older pack of this game (${old.name}) moved to the new pack")
                        if (delete(old)) out.add("removed the older pack of this game: ${old.name}")
                    }
                    OlderPackAction.REMOVE ->
                        if (delete(old)) out.add("removed the older pack of this game: ${old.name}")
                }
            }
        }
        return out
    }

    /** The pack's saves (the folder the save manager looks in): a pack without one has nothing to keep */
    private fun savesIn(pack: File): Boolean {
        val saves = File(pack, "saves")
        return saves.isDirectory && (saves.listFiles()?.isNotEmpty() == true)
    }

    /** The older pack's saves go into the new pack, where the save manager looks for them */
    private fun moveSaves(old: File, newPack: File): Boolean {
        val from = File(old, "saves")
        val to = File(newPack, "saves")
        return try {
            if (to.isDirectory) to.deleteRecursively()   // an empty folder: the move wants the name
            if (from.renameTo(to)) return true
            to.mkdirs()
            from.copyRecursively(to, overwrite = true)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun delete(pack: File): Boolean = try { !pack.exists() || pack.deleteRecursively() } catch (e: Exception) { false }
}
