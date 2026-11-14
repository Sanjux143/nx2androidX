package com.nx.android.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The log bundle: what "share the log" sends.
 *
 * A zip with the device's and the app's own facts, the app's log, the porter's stage logs (the build folder's
 * logs: the host's build, the Oodle decoder's run, the extractor's, the shaders') and the build history's
 * logs. The stage logs are where the reason of a failure is: the log the screen shows says what failed, the
 * stage log says why. Without it a failure on a player's phone has to be reproduced to be understood.
 */
object LogBundle {

    /** One stage log's cap: 1 MB of its tail */
    const val STAGE_LIMIT = 1L shl 20

    /** The app's own log's cap: 4 MB of its tail */
    const val MAIN_LIMIT = 4L shl 20

    /** How long a bundle is kept in the cache folder */
    private const val KEEP_MILLIS = 7L * 24 * 60 * 60 * 1000

    /**
     * Packs the bundle into out (a folder in the cache): the zip, or null when there is nothing to send.
     *
     * @param work the porter's work folder: the stage logs are in its per-game folders (one "logs" folder each)
     * @param buildLogs the logs to share under "builds/", named for their entries
     * @param tag what the bundle is about (the game, the build), for the file's name
     */
    fun build(context: Context, work: File, buildLogs: List<Pair<String, File>>, out: File, tag: String = ""): File? =
        build(deviceText(context), AppVersion.of(context), work, buildLogs, out, tag)

    /** The bundle itself: the device's facts as text (the app's own context gives them) */
    fun build(device: String, version: String, work: File, buildLogs: List<Pair<String, File>>, out: File, tag: String = ""): File? {
        val stages = stageLogs(work)
        val main = AppLog.logFile()?.takeIf { it.isFile && it.length() > 0 }   // an empty log is no log
        if (stages.isEmpty() && buildLogs.isEmpty() && main == null) return null
        out.mkdirs()
        out.listFiles()?.filter { it.isFile && it.lastModified() < System.currentTimeMillis() - KEEP_MILLIS }
            ?.forEach { it.delete() }
        val files = mutableListOf<Triple<String, File, Long>>()
        if (main != null) files.add(Triple("log.txt", main, MAIN_LIMIT))
        for ((name, file) in stages) files.add(Triple(name, file, STAGE_LIMIT))
        for ((name, file) in buildLogs) files.add(Triple("builds/$name", file, STAGE_LIMIT))
        return pack(out, fileName(version, tag), listOf("device.txt" to device), files)
    }

    /**
     * The game's runtime log bundle: the device's and the app's own facts, the game's own facts and the
     * host's logs (the last run and the one before it). Nothing when there is no log to share.
     *
     * @param device the device's and the app's own facts as text
     * @param game the game's own facts as text
     * @param logs the host's logs, named for their entries
     * @param out the folder the bundle goes to
     * @param name the bundle's file name
     */
    fun gameBundle(device: String, game: String, logs: List<Pair<String, File>>, out: File, name: String): File? {
        if (logs.isEmpty()) return null
        val files = logs.map { Triple(it.first, it.second, MAIN_LIMIT) }
        return pack(out, name, listOf("device.txt" to device, "game.txt" to game), files)
    }

    /** Writes the zip: the texts as they are, the files capped (a big one gives its tail) */
    private fun pack(out: File, name: String, texts: List<Pair<String, String>>, files: List<Triple<String, File, Long>>): File? {
        out.mkdirs()
        val zip = File(out, name)
        return try {
            ZipOutputStream(zip.outputStream().buffered()).use { zos ->
                for ((n, t) in texts) putText(zos, n, t)
                for ((n, f, limit) in files) put(zos, n, f, limit)
            }
            zip
        } catch (e: Exception) {
            zip.delete()
            null
        }
    }

    /** The bundle's file name: the app's version, the time and what it is about */
    private fun fileName(version: String, tag: String): String {
        val v = version.ifBlank { "unknown" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val t = tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "nx2android-log${if (t.isBlank()) "" else "-$t"}-$v-$stamp.zip"
    }

    /**
     * The device's and the app's own facts: what the phone is, and which build of the app it runs.
     */
    fun deviceText(context: Context): String {
        val b = StringBuilder()
        fun row(key: String, value: String?) {
            if (!value.isNullOrBlank()) b.append(key).append(": ").append(value).append('\n')
        }
        row("phone", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        row("manufacturer", Build.MANUFACTURER)
        row("model", Build.MODEL)
        row("brand", Build.BRAND)
        row("product", Build.PRODUCT)
        row("device", Build.DEVICE)
        row("board", Build.BOARD)
        row("hardware", Build.HARDWARE)
        if (Build.VERSION.SDK_INT >= 31) row("soc", Build.SOC_MODEL)   // the SoC's name (API 31 and later)
        row("abis", Build.SUPPORTED_ABIS.joinToString(", "))
        row("android", "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        row("kernel", System.getProperty("os.version"))
        row("locale", Locale.getDefault().toString())
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am != null) {
            val m = ActivityManager.MemoryInfo()
            am.getMemoryInfo(m)
            row("ram", "${m.totalMem / (1L shl 20)} MB total, ${m.availMem / (1L shl 20)} MB available")
        }
        val dm = context.resources.displayMetrics
        row("screen", "${dm.widthPixels}x${dm.heightPixels} @${dm.densityDpi}dpi")
        // the app's own facts
        row("app", context.packageName)
        val pi = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: Exception) {
            null
        }
        if (pi != null) {
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            row("version", "${pi.versionName} (versionCode $code)")
        } else {
            row("version", AppVersion.of(context))
        }
        row("installed from", if (Build.VERSION.SDK_INT >= 30) try {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } catch (e: Exception) {
            null
        } else null)
        return b.toString()
    }

    /**
     * The stage logs of every game the work folder holds: the porter writes them beside the game's own
     * folder (one "logs" folder per game).
     */
    private fun stageLogs(work: File): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        val folders = work.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: return out
        for (folder in folders) {
            val logs = File(folder, "logs")
            if (!logs.isDirectory) continue
            val files = logs.listFiles()?.filter { it.isFile && it.length() > 0 }?.sortedBy { it.name } ?: continue
            for (f in files) out.add("stage-logs/${folder.name}/${f.name}" to f)
        }
        return out
    }

    /** Writes a text as an entry */
    private fun putText(zos: ZipOutputStream, name: String, text: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(text.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    /**
     * Writes a file as an entry: the whole file, or its last limit bytes when it is bigger (a log's
     * beginning is the least interesting part).
     */
    private fun put(zos: ZipOutputStream, name: String, file: File, limit: Long) {
        if (!file.isFile) return
        val len = file.length()
        zos.putNextEntry(ZipEntry(name))
        if (len <= limit) {
            file.inputStream().use { it.copyTo(zos) }
        } else {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(len - limit)
                val buf = ByteArray(64 * 1024)
                var left = limit
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) break
                    zos.write(buf, 0, n)
                    left -= n
                }
            }
            zos.write("\n... ($len bytes, the first ${len - limit} are cut off)\n".toByteArray(Charsets.UTF_8))
        }
        zos.closeEntry()
    }
}
