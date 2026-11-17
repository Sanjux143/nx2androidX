package com.nx.android.domain

import org.json.JSONObject
import java.io.File

/**
 * A finished port that can leave the phone as one folder, and be taken in by another phone in place.
 *
 * The folder is a data pack plus the game's files as plain files (`RomFS/`): the host reads
 * `$DM_ROOT/RomFS` when nothing points it elsewhere (`nxnative/dm/android/front_player.c`), so the
 * folder runs where it lies — no image format, no host change, no copy into the app's own storage.
 *
 * Two management files carry paths of the phone that wrote them, so they follow the folder:
 * the manifest's `romfs_at` (the build workspace, which does not travel) is dropped, and the rows in
 * `env.txt` that point inside the pack are rewritten for the folder's place.
 */
object PortablePack {

    /** The manifest inside a pack. */
    const val MANIFEST = "nx2a-data.json"

    /** The host's switches. */
    const val ENV = "env.txt"

    /** The game's files, as plain files. */
    const val ROMFS = "RomFS"

    /** The player's own exported save backups: they stay behind. */
    const val SAVES = "saves"

    /**
     * A folder the player imported: the path is its identity, and the record survives the folder
     * going away (a card that is not plugged in).
     */
    data class Imported(val path: String, val titleId: String, val name: String)

    /** The outcome of an export. */
    data class Result(
        val ok: Boolean,
        val failure: String? = null,
        val files: Int = 0,
        val bytes: Long = 0L,
        val folder: String = "",
    ) {
        companion object {
            fun ok(folder: String, files: Int = 0, bytes: Long = 0L) = Result(true, null, files, bytes, folder)
            fun fail(why: String) = Result(false, why)
        }
    }

    /** How far an export has come. */
    data class Progress(val files: Int, val totalFiles: Int, val bytes: Long, val totalBytes: Long) {
        val fraction: Float
            get() = if (totalBytes <= 0L) 0f else (bytes.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
    }

    /** What an import found in the folder the player picked. */
    data class Validation(
        val ok: Boolean,
        /** Why not, as a code the interface turns into a sentence. */
        val failure: String? = null,
        val titleId: String = "",
        val name: String = "",
    )

    /**
     * The paths inside a pack that travel with it: an env row whose value ends with one of these
     * points into the pack, so it follows the folder wherever the folder is.
     */
    private val PACK_PATHS = listOf("/aoc", "/sysdata", "/standard.ttf", "/nintendo_ext.ttf")

    /** The characters a file system refuses in a name. */
    private val ILLEGAL = Regex("""[\\/:*?"<>|]""")

    /**
     * The folder's name: the game's name, cleaned of the characters a file system refuses.
     * A name that cleans away to nothing becomes "port".
     */
    fun folderName(name: String): String {
        val cleaned = name.trim().replace(ILLEGAL, "_").trim('.', ' ').trim()
        return cleaned.ifEmpty { "port" }.take(80)
    }

    /** The manifest a folder carries: the pointer at the exporting phone's workspace is gone. */
    fun manifestForExport(manifest: JSONObject): JSONObject {
        val out = JSONObject(manifest.toString())
        out.remove("romfs_at")
        return out
    }

    /**
     * The env.txt a folder in [folder] carries: the row that pointed at the exporting phone's
     * workspace is gone, and the rows that pointed inside the pack follow the folder.
     */
    fun envForFolder(env: String, folder: File): String {
        val base = folder.absolutePath.trimEnd('/')
        val kept = env.lineSequence().mapNotNull { line ->
            if (line.trimStart().startsWith("#") || !line.contains('=')) return@mapNotNull line
            val key = line.substringBefore('=', "")
            // the build workspace does not travel: the game's files are inside the folder now
            if (key.trim() == "DM_ROMFS") return@mapNotNull null
            val value = line.substringAfter('=')
            val suffix = PACK_PATHS.firstOrNull { value.endsWith(it) && value.length > it.length } ?: return@mapNotNull line
            val head = value.dropLast(suffix.length)
            if (!head.startsWith("/")) return@mapNotNull line
            "$key=$base$suffix"
        }.toList()
        return kept.joinToString("\n").trimEnd('\n') + "\n"
    }

    /** The manifest inside a folder, or null when there is none to read. */
    fun readManifest(dir: File): JSONObject? = try {
        val file = File(dir, MANIFEST)
        if (!file.isFile) null else JSONObject(file.readText())
    } catch (e: Exception) {
        null
    }

    /**
     * What a folder the player picked holds: a manifest, a title ID and the game's files.
     * A folder that misses one of them is not a portable pack.
     */
    fun validate(dir: File): Validation {
        if (!dir.isDirectory) return Validation(false, "not_a_folder")
        val manifest = readManifest(dir) ?: return Validation(false, "no_manifest")
        val titleId = manifest.optString("title_id")
        if (!titleId.matches(Regex("[0-9A-Fa-f]{16}"))) return Validation(false, "bad_title_id")
        if (!File(dir, ROMFS).isDirectory) return Validation(false, "no_romfs")
        return Validation(true, null, titleId.uppercase(), manifest.optString("name"))
    }
}
