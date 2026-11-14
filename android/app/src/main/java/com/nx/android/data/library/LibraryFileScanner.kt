package com.nx.android.data.library

import com.nx.android.domain.GameIdentity
import com.nx.android.domain.LibraryLogic
import com.nx.android.domain.Ported
import java.io.File

/**
 * What the game files and the finished data packs say about a game: its title ID, its display name, the
 * pack it was ported into. Free of Android, so the tests run on the JVM.
 */
object LibraryFileScanner {

    /** Half-written packs left by an interrupted archive operation. */
    fun partials(portsDir: File): List<File> =
        portsDir.listFiles()
            ?.filter { it.isDirectory && it.name.endsWith(".partial") }
            ?.sortedBy { it.name }
            ?: emptyList()

    /**
     * The title ID of every work folder the porter left behind: `<title ID>_build`, one per game.
     *
     * The porter names it by title ID (`pipeline.py`), so the name is the game's identity — a renamed
     * source file still finds its own workspace this way.
     */
    fun workIndex(workRoot: File): Map<String, File> {
        if (!workRoot.isDirectory) return emptyMap()
        val out = HashMap<String, File>()
        for (d in workRoot.listFiles() ?: return emptyMap()) {
            if (!d.isDirectory) continue
            val tid = GameIdentity.titleIdInWork(d.name) ?: continue
            out[tid] = d
        }
        return out
    }

    /**
     * The title ID of the work folder a build wrote during that build (the time window from..to).
     *
     * One build at a time, so there is exactly one such folder — a later build's folder is outside the
     * window and is not confused with it. Nothing in the window (the build died before creating it), or
     * more than one (should not happen), means we learn nothing.
     */
    fun workFolderIn(workRoot: File, from: Long, to: Long): String? {
        val dirs = workRoot.listFiles()?.filter { d ->
            d.isDirectory && GameIdentity.titleIdInWork(d.name) != null
        } ?: return null
        val hit = dirs.filter { it.lastModified() >= from && it.lastModified() <= to }
        return if (hit.size == 1) GameIdentity.titleIdInWork(hit[0].name) else null
    }

    /**
     * The title ID of every recognizable pack in the ports folder. A missing host does not make the
     * pack disappear: the library must keep a card so the player can rebuild or delete it.
     */
    fun outIndex(outDir: File): Map<String, Ported> {
        if (!outDir.isDirectory) return emptyMap()
        val out = HashMap<String, Ported>()
        for (d in outDir.listFiles() ?: return emptyMap()) {
            if (!d.isDirectory || !d.name.endsWith("-data")) continue
            portFromPack(d)?.let {
                val id = it.titleId.uppercase()
                val chosen = LibraryLogic.preferPack(out[id], currentInternal = true, it, candidateInternal = true)
                out[id] = chosen.first
            }
        }
        return out
    }

    /**
     * The port metadata a data pack holds, or null when its manifest cannot identify a title. The host may
     * be missing; [Ported.runnable] is the separate run-readiness check.
     */
    fun portFromPack(pack: File): Ported? {
        val man = try {
            org.json.JSONObject(File(pack, "nx2a-data.json").readText())
        } catch (e: Exception) {
            return null
        }
        val id = man.optString("title_id")
        if (!id.matches(Regex("[0-9A-Fa-f]{16}"))) return null
        val host = File(pack, man.optString("host", "host/libdmplay.so"))
        if (!host.canonicalFile.toPath().startsWith(pack.canonicalFile.toPath())) return null
        return Ported(pack = pack, host = host,
                      icon = File(pack, "icon.png").takeIf { it.isFile },
                      titleId = id, name = man.optString("name"),
                      status = man.optString("status"),
                      playerApiRevision = (man.opt("player_api_revision") as? Int)?.takeIf { it > 0 } ?: 0,
                      display = man.optString("display"),
                      romfsAt = man.optString("romfs_at"))
    }

    /**
     * The title ID in a pack's manifest (the manifest alone: the host may be gone, and a pack without a host
     * is still a pack that has to be deleted).
     */
    fun manifestTitleId(pack: File): String? = try {
        org.json.JSONObject(File(pack, "nx2a-data.json").readText())
            .optString("title_id").takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    /**
     * The volume a path is on: `/storage/<卷 ID>/…` → `/storage/<卷 ID>`（the removable card's root）。
     * The card is gone when that folder is not there.
     */
    fun volumeRootOf(path: File): File? {
        val p = path.absolutePath
        if (!p.startsWith("/storage/")) return null
        val rest = p.removePrefix("/storage/")
        val id = rest.substringBefore('/')
        if (id.isEmpty()) return null
        return File("/storage/$id")
    }

}
