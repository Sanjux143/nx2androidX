package com.nx.android.data.library

import com.nx.android.domain.PortablePack
import java.io.File
import java.io.IOException

/**
 * The file work of an export: the pack's own files, the game's files as plain files, and the two
 * management files rewritten for the folder's new place.
 *
 * The rules (the same ones the porter uses when it builds a pack):
 * - the folder is written as `<name>.partial` and renamed when it is complete: a half-written folder
 *   is never mistaken for a finished one;
 * - the player's exported save backups (`saves/`) stay behind: they are the player's, not the port's;
 * - the manifest loses the pointer at the exporting phone's build workspace, and `env.txt`'s rows
 *   that point inside the pack follow the folder;
 * - anything that goes wrong (a card pulled out, a full disk) leaves nothing behind.
 *
 * Pure file work: no Android, so the tests run on the JVM.
 */
object PortablePackFiles {

    /** How often the progress is reported (every few megabytes): less is too coarse, more is noise. */
    private const val CHUNK_REPORT = 4L * 1024 * 1024

    /** Files and bytes. */
    private data class Count(var files: Int = 0, var bytes: Long = 0L)

    /**
     * Writes the port into a folder under [targetDir].
     *
     * @param pack the data pack to export
     * @param romfsAt where the game's files are (the build workspace); empty when the pack has them
     * @param name the game's name: the folder is named after it
     */
    fun export(pack: File, romfsAt: String, targetDir: File, name: String,
               onProgress: (PortablePack.Progress) -> Unit = {}): PortablePack.Result {
        if (!pack.isDirectory) return PortablePack.Result.fail("no_pack")
        val folder = File(targetDir, PortablePack.folderName(name))
        if (folder.exists()) return PortablePack.Result.fail("exists")
        val manifest = PortablePack.readManifest(pack) ?: return PortablePack.Result.fail("no_manifest")
        // the game's files: the workspace's RomFS (an app-mode pack does not carry them), or the
        // pack's own RomFS/ (an old pack has them already)
        val romfs = if (romfsAt.isNotEmpty()) File(romfsAt) else File(pack, PortablePack.ROMFS)
        val romfsInsidePack = romfs.parentFile?.absolutePath == pack.absolutePath
        // 游戏文件不在了：导出一个跑不起来的文件夹没有意义
        if (!romfs.isDirectory) return PortablePack.Result.fail("no_romfs")
        val tmp = File(targetDir, folder.name + ".partial")
        try {
            targetDir.mkdirs()
            if (!targetDir.isDirectory) return PortablePack.Result.fail("no_target")
            tmp.deleteRecursively()
            val total = Count()
            countTree(pack, setOf(PortablePack.SAVES, PortablePack.MANIFEST, PortablePack.ENV), total)
            if (!romfsInsidePack) countTree(romfs, emptySet(), total)
            onProgress(PortablePack.Progress(0, total.files, 0L, total.bytes))
            val copied = Count()
            if (!copyTree(pack, tmp, setOf(PortablePack.SAVES, PortablePack.MANIFEST, PortablePack.ENV), copied, total, onProgress))
                return failPartial(tmp, "copy_failed")
            if (!romfsInsidePack) {
                if (!copyTree(romfs, File(tmp, PortablePack.ROMFS), emptySet(), copied, total, onProgress))
                    return failPartial(tmp, "copy_failed")
            }
            // the two management files: written for the folder's place, not copied
            File(tmp, PortablePack.MANIFEST).writeText(PortablePack.manifestForExport(manifest).toString(2))
            val env = File(pack, PortablePack.ENV).takeIf { it.isFile }?.readText().orEmpty()
            File(tmp, PortablePack.ENV).writeText(PortablePack.envForFolder(env, folder))
            copied.files += 2
            if (!tmp.renameTo(folder)) return failPartial(tmp, "cannot_place")
            return PortablePack.Result.ok(folder.absolutePath, copied.files, copied.bytes)
        } catch (e: Exception) {
            tmp.deleteRecursively()
            return PortablePack.Result.fail(e.message ?: e.javaClass.simpleName)
        }
    }

    /** A half-written folder is not left behind. */
    private fun failPartial(tmp: File, why: String): PortablePack.Result {
        tmp.deleteRecursively()
        return PortablePack.Result.fail(why)
    }

    /**
     * Points a folder's `env.txt` at its own place: the DLC and the system data travel with the
     * folder, so the rows that name them follow it.
     *
     * Best effort: a folder that cannot be written (a read-only card) still runs, it just has no
     * add-on content.
     */
    fun placeEnv(dir: File): Boolean {
        val env = File(dir, PortablePack.ENV)
        if (!env.isFile) return false
        return try {
            val text = env.readText()
            val placed = PortablePack.envForFolder(text, dir)
            if (placed == text) true else { env.writeText(placed); true }
        } catch (e: Exception) {
            false
        }
    }

    /** How big a tree is (only stat: files and bytes, no content read). */
    private fun countTree(src: File, exclude: Set<String>, into: Count) {
        val children = src.listFiles() ?: return
        for (child in children) {
            if (child.name in exclude) continue
            if (child.isDirectory) countTree(child, emptySet(), into)
            else {
                into.files++
                into.bytes += child.length()
            }
        }
    }

    /**
     * Copies a tree into [dst]. The progress is reported per chunk: a game's files can be one huge
     * file, and a progress bar that only moves per file would sit at zero the whole time.
     */
    private fun copyTree(src: File, dst: File, exclude: Set<String>, copied: Count, total: Count,
                         onProgress: (PortablePack.Progress) -> Unit): Boolean {
        if (!dst.isDirectory && !dst.mkdirs()) return false
        val children = src.listFiles() ?: return true
        for (child in children) {
            if (child.name in exclude) continue
            val out = File(dst, child.name)
            if (child.isDirectory) {
                if (!copyTree(child, out, emptySet(), copied, total, onProgress)) return false
            } else {
                var inFile = 0L
                val ok = copyFile(child, out) { done ->
                    inFile = done
                    onProgress(PortablePack.Progress(copied.files, total.files, copied.bytes + inFile, total.bytes))
                }
                if (!ok) return false
                copied.files++
                copied.bytes += out.length()
                onProgress(PortablePack.Progress(copied.files, total.files, copied.bytes, total.bytes))
            }
        }
        return true
    }

    /** Copies one file and checks the length afterwards. */
    private fun copyFile(src: File, dst: File, onChunk: (Long) -> Unit = {}): Boolean {
        try {
            var copied = 0L
            var reported = 0L
            src.inputStream().use { input ->
                dst.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        copied += n
                        if (copied - reported >= CHUNK_REPORT) {
                            reported = copied
                            onChunk(copied)
                        }
                    }
                }
            }
            onChunk(copied)
            return src.length() == dst.length()
        } catch (e: IOException) {
            return false
        }
    }
}
