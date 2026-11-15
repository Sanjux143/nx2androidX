package com.nx.android.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import com.nx.android.domain.saves.SAVE_PACKAGE_EXTENSION

/**
 * The save package: the game's save leaves the app as a zip the player can send to someone, and comes back
 * the same way.
 *
 * The live save is the app's own file - the host mounts it at `<internal>/save/<titleId>` (dm_java_start is
 * given the app's files folder and the title ID) - so the panel can work on it directly: no request file for
 * the host, no game start. A package holds the save's files as they are, the layout a console's save tools
 * write and read, under one folder; the device save of a game that mounts one lies beside it.
 *
 * The package travels as a stream: it is written where the player says (the file manager) and read from
 * where he picked it. The stream belongs to the caller of these functions - it is closed when they return.
 */
object SaveBackup {

    /** the package's extension */
    const val EXT = SAVE_PACKAGE_EXTENSION

    /**
     * Why a package was refused. A code, not a sentence: this layer stays free of Android, so the words
     * for it live in the app's own resources (see `ui/Failures.kt`).
     */
    enum class Refusal { TOO_MANY_ENTRIES, BAD_PATH, PATH_ESCAPES, TOO_BIG, NOT_A_PACKAGE }

    /** A package that was refused: the reason, and the entry that caused it when there is one. */
    class Refused(val refusal: Refusal, val entry: String? = null) :
        IOException(if (entry == null) refusal.name else "${refusal.name}: $entry")

    /** how much a package may unpack to (a save is small; a package from elsewhere is not to be trusted) */
    const val MAX_UNPACKED = 512L * 1024 * 1024

    /** how many entries a package may hold */
    const val MAX_ENTRIES = 20000

    /** the live save: `<internal>/save/<titleId>` */
    fun liveDir(internal: File, titleId: String): File = File(internal, "save/$titleId")

    /** the device save: `<internal>/save/<titleId>_device`, beside the save of a game that mounts one */
    fun deviceDir(internal: File, titleId: String): File = File(internal, "save/${titleId}_device")

    /** the package's file name: `<game>-<titleId>-<stamp>.zip` */
    fun packageName(game: String, titleId: String, stamp: String): String =
        "${sanitize(game).ifEmpty { "save" }}-${sanitize(titleId).ifEmpty { "unknown" }}-$stamp.$EXT"

    /** the title ID a package's name carries, or null when it carries none */
    fun titleIdIn(name: String): String? {
        if (!name.endsWith(".$EXT", ignoreCase = true)) return null
        val stem = name.substring(0, name.length - EXT.length - 1)
        val parts = stem.split("-")
        if (parts.size < 3) return null
        val id = parts[parts.size - 3]
        if (id.length != 16 || !id.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
        return id.uppercase()
    }

    /** a name that is safe in a file name and in a zip entry */
    internal fun sanitize(name: String): String =
        name.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }
            .joinToString("").trim('_', '.', ' ').take(60)

    /**
     * The package: the save tree under `treeName/`, the device save under `treeName.device/` when the game has
     * one. The files are copied byte for byte.
     */
    fun writePackage(main: File?, device: File?, treeName: String, out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            if (main != null) writeTree(zip, main, treeName)
            if (device != null) writeTree(zip, device, "$treeName.device")
        }
    }

    private fun writeTree(zip: ZipOutputStream, src: File, top: String) {
        src.walkTopDown().forEach { f ->
            val rel = f.relativeTo(src).path
            val entry = if (rel.isEmpty()) top else "$top/$rel"
            if (f.isDirectory) {
                if (f.listFiles()?.isEmpty() == true) {   // an empty folder is kept, a filled one comes with its files
                    zip.putNextEntry(ZipEntry("$entry/"))
                    zip.closeEntry()
                }
            } else {
                zip.putNextEntry(ZipEntry(entry))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** what a package holds: the save tree, and the device save beside it when it has one */
    class Package(val tree: File, val device: File?)

    /**
     * Unpack a package from `source` into `dest`: the save tree it holds and the device save beside it. A
     * package from elsewhere is not trusted - an entry whose path points out of `dest` is refused, and the
     * unpacked size is capped.
     *
     * A package that holds no file at all is refused as well: it is not a save package (a broken zip, a text
     * file with the right name), and importing it would replace the player's save with nothing.
     */
    fun unpack(source: InputStream, dest: File): Package {
        dest.mkdirs()
        var bytes = 0L
        var entries = 0
        var files = 0
        ZipInputStream(source.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                if (entries > MAX_ENTRIES) throw Refused(Refusal.TOO_MANY_ENTRIES)
                val name = entry.name
                if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains(":")) {
                    throw Refused(Refusal.BAD_PATH, name)
                }
                val parts = name.split("/")
                if (parts.any { it == ".." }) throw Refused(Refusal.PATH_ESCAPES, name)
                val out = File(dest, name)
                if (!out.canonicalPath.startsWith(dest.canonicalPath + File.separator)) {
                    throw Refused(Refusal.PATH_ESCAPES, name)
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                files++
                out.parentFile?.mkdirs()
                bytes += out.outputStream().use { zip.copyTo(it) }
                if (bytes > MAX_UNPACKED) throw Refused(Refusal.TOO_BIG)
            }
        }
        if (files == 0) throw Refused(Refusal.NOT_A_PACKAGE)
        val dirs = dest.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()
        val devices = dirs.filter { it.name.endsWith(".device") }
        val trees = dirs.filter { !it.name.endsWith(".device") }
        return when {
            trees.size == 1 -> Package(trees[0], devices.firstOrNull { it.name == trees[0].name + ".device" })
            trees.isEmpty() -> Package(dest, null)      // the files sit in the package's root
            else -> {                                    // several trees: a save with more than one user in it
                val merged = File(dest, "tree")
                for (t in trees) copyTree(t, File(merged, t.name))
                Package(merged, devices.firstOrNull())
            }
        }
    }

    /**
     * Put a tree in the live save's place: the new tree is copied aside, the old one is renamed out of the
     * way, the new one takes its place, and the old one is removed. The game is closed while this happens.
     */
    fun swapIn(tree: File, device: File?, live: File, liveDevice: File) {
        live.parentFile?.mkdirs()
        val newLive = File(live.parentFile, live.name + ".import")
        val newDevice = File(liveDevice.parentFile, liveDevice.name + ".import")
        newLive.deleteRecursively()
        newDevice.deleteRecursively()
        copyTree(tree, newLive)
        if (device != null && device.isDirectory) copyTree(device, newDevice)
        val oldLive = File(live.parentFile, live.name + ".old")
        val oldDevice = File(liveDevice.parentFile, liveDevice.name + ".old")
        oldLive.deleteRecursively()
        oldDevice.deleteRecursively()
        var movedLive = false
        var movedDevice = false
        var installedLive = false
        var installedDevice = false
        try {
            if (live.exists()) {
                if (!live.renameTo(oldLive)) throw IOException("Could not stage current save")
                movedLive = true
            }
            if (liveDevice.exists()) {
                if (!liveDevice.renameTo(oldDevice)) throw IOException("Could not stage current device save")
                movedDevice = true
            }
            if (!newLive.renameTo(live)) throw IOException("Could not install new save")
            installedLive = true
            if (newDevice.isDirectory) {
                if (!newDevice.renameTo(liveDevice)) throw IOException("Could not install new device save")
                installedDevice = true
            }
        } catch (failure: Throwable) {
            if (installedLive) live.deleteRecursively()
            if (installedDevice) liveDevice.deleteRecursively()
            val rollbackFailures = mutableListOf<String>()
            if (movedLive && oldLive.exists() && !oldLive.renameTo(live)) rollbackFailures += "save"
            if (movedDevice && oldDevice.exists() && !oldDevice.renameTo(liveDevice)) rollbackFailures += "device save"
            newLive.deleteRecursively()
            newDevice.deleteRecursively()
            if (rollbackFailures.isNotEmpty()) {
                throw IOException("Save install failed and rollback could not restore ${rollbackFailures.joinToString()}", failure)
            }
            throw failure
        }
        oldLive.deleteRecursively()
        oldDevice.deleteRecursively()
    }

    /** a directory tree copied file by file, byte for byte (a file that is there is replaced) */
    fun copyTree(src: File, dst: File) {
        if (!src.isDirectory) return
        dst.mkdirs()
        src.walkTopDown().forEach { f ->
            val rel = f.relativeTo(src).path
            if (rel.isEmpty()) return@forEach
            val out = File(dst, rel)
            if (f.isDirectory) {
                out.mkdirs()
            } else {
                out.parentFile?.mkdirs()
                f.inputStream().use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            }
        }
    }

    /**
     * The package of the live save, written into `dest` (the name it gets there is free); null when the game
     * has not saved yet. The tree in the package is named after the package.
     */
    fun export(live: File, device: File?, treeName: String, out: OutputStream): Boolean {
        val main = live.takeIf { it.isDirectory && it.listFiles()?.isNotEmpty() == true }
        val dev = device?.takeIf { it.isDirectory && it.listFiles()?.isNotEmpty() == true }
        if (main == null && dev == null) return false
        writePackage(main, dev, treeName, out)
        return true
    }

    /** a package name that is not taken yet (two exports in the same second would share one name) */
    fun freeName(dest: File): File {
        if (!dest.exists()) return dest
        val stem = dest.name.removeSuffix(".$EXT")
        var k = 2
        while (true) {
            val f = File(dest.parentFile, "$stem-$k.$EXT")
            if (!f.exists()) return f
            k++
        }
    }
}
