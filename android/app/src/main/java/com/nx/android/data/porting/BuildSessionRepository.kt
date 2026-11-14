package com.nx.android.data.porting

import android.content.Context
import com.nx.android.data.PrefsStore
import com.nx.android.domain.GameConfig
import com.nx.android.domain.porting.BuildSession
import com.nx.android.domain.porting.PortHistoryRepository
import com.nx.android.domain.porting.PortPhase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Each accepted build attempt has its own metadata and append-only log. */
@Singleton
class BuildSessionRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val prefs: PrefsStore,
    private val legacy: PortHistoryRepository,
) {
    private val root = File(File(context.filesDir, "nx2a"), "builds")
    private val mutableSessions = MutableStateFlow<List<BuildSession>>(emptyList())
    val sessions: StateFlow<List<BuildSession>> = mutableSessions.asStateFlow()

    @Synchronized
    fun load(): List<BuildSession> {
        root.mkdirs()
        var all = root.listFiles()?.filter { it.isDirectory }?.mapNotNull(::readRecord) ?: emptyList()
        if (!prefs.bool(MIGRATED)) {
            val existing = all.mapTo(HashSet()) { it.id }
            val imported = legacy.load().mapIndexedNotNull { i, old ->
                val id = "legacy-${old.startedAt}-${i}-${old.game.hashCode().toUInt().toString(16)}"
                if (id in existing) null else BuildSession(
                    id, old.game, runCatching { PortPhase.valueOf(old.phase) }.getOrDefault(PortPhase.FAILED),
                    old.out, old.startedAt, old.seconds, legacy = true, logAvailable = false,
                ).also { writeRecord(it) }
            }
            all = all + imported
            prefs.put(MIGRATED, true)
        }
        mutableSessions.value = all.sortedWith(order).let { list ->
            list.filterNot { it.terminal } + list.filter { it.terminal }.take(KEEP)
        }
        cleanup()
        return mutableSessions.value
    }

    @Synchronized
    fun create(game: String, config: GameConfig): BuildSession {
        if (mutableSessions.value.isEmpty()) load()
        check(mutableSessions.value.none { !it.terminal }) { "A build is already active" }
        val s = BuildSession(UUID.randomUUID().toString(), game, PortPhase.RUNNING, "", System.currentTimeMillis(), config = config)
        require(dir(s.id).mkdirs() || dir(s.id).isDirectory) { "Cannot create build history" }
        File(dir(s.id), LOG).createNewFile()
        writeRecord(s)
        publish(s)
        return s
    }

    @Synchronized
    fun recoverLegacyInterrupted(game: String, startedAt: Long): BuildSession {
        if (mutableSessions.value.isEmpty()) load()
        val id = "legacy-active-$startedAt-${game.hashCode().toUInt().toString(16)}"
        mutableSessions.value.firstOrNull { it.id == id }?.let { return it }
        val s = BuildSession(id, game, PortPhase.INTERRUPTED, "", startedAt, 0,
            legacy = true, logAvailable = false)
        dir(id).mkdirs()
        writeRecord(s)
        publish(s)
        return s
    }

    @Synchronized
    fun update(id: String, transform: (BuildSession) -> BuildSession): BuildSession? {
        val current = mutableSessions.value.firstOrNull { it.id == id } ?: readRecord(dir(id)) ?: return null
        if (current.terminal) return current
        val next = transform(current)
        writeRecord(next)
        publish(next)
        return next
    }

    @Synchronized
    fun append(id: String, line: String): Boolean {
        val s = mutableSessions.value.firstOrNull { it.id == id } ?: return false
        if (s.terminal || !s.logAvailable) return false
        return try {
            File(dir(id), LOG).appendText(line + "\n", Charsets.UTF_8)
            true
        } catch (_: Exception) {
            val failed = s.copy(logAvailable = false)
            mutableSessions.value = mutableSessions.value.map { if (it.id == id) failed else it }
            runCatching { writeRecord(failed) }
            false
        }
    }

    fun logFile(id: String): File? {
        val s = mutableSessions.value.firstOrNull { it.id == id } ?: return null
        return File(dir(id), LOG).takeIf { s.logAvailable && it.isFile }
    }

    fun logLength(id: String): Long = logFile(id)?.length() ?: 0L

    fun tail(id: String, maxLines: Int = 30): List<String> {
        val f = logFile(id) ?: return emptyList()
        return try {
            RandomAccessFile(f, "r").use { raf ->
                val start = (raf.length() - 16 * 1024).coerceAtLeast(0)
                raf.seek(start)
                val bytes = ByteArray((raf.length() - start).toInt())
                raf.readFully(bytes)
                String(bytes, Charsets.UTF_8).lines().takeLast(maxLines)
            }
        } catch (_: Exception) { emptyList() }
    }

    @Synchronized
    fun copyLogSnapshot(id: String, destination: File): Boolean = try {
        val source = logFile(id) ?: return false
        destination.parentFile?.mkdirs()
        source.copyTo(destination, overwrite = true)
        true
    } catch (_: Exception) { false }

    /** The build history's logs, named for the log bundle's entries (the game and the build) */
    fun logFiles(): List<Pair<String, File>> = mutableSessions.value.filter { it.logAvailable }.mapNotNull { s ->
        logFile(s.id)?.let { s to it }
    }.map { (s, f) ->
        "${safeName(s.game)}-${s.id.take(8)}.log" to f
    }

    /** Reads a bounded byte window so viewers can page through long logs. */
    fun readLog(id: String, offset: Long = 0, length: Int = 64 * 1024): String {
        val f = logFile(id) ?: return ""
        return try {
            RandomAccessFile(f, "r").use { raf ->
                raf.seek(offset.coerceIn(0, raf.length()))
                val size = minOf(length.coerceAtLeast(0).toLong(), raf.length() - raf.filePointer).toInt()
                val bytes = ByteArray(size)
                raf.readFully(bytes)
                String(bytes, Charsets.UTF_8)
            }
        } catch (_: Exception) { "" }
    }

    @Synchronized
    private fun publish(s: BuildSession) {
        mutableSessions.value = (mutableSessions.value.filterNot { it.id == s.id } + s).sortedWith(order)
        cleanup()
    }

    private fun cleanup() {
        val sorted = mutableSessions.value.sortedWith(order)
        val active = sorted.filterNot { it.terminal }
        val finished = sorted.filter { it.terminal }.take(KEEP)
        val keep = (active + finished).mapTo(HashSet()) { it.id }
        sorted.filter { it.id !in keep }.forEach { dir(it.id).deleteRecursively() }
        mutableSessions.value = active + finished
        root.mkdirs()
        atomicWrite(File(root, INDEX), JSONArray().also { a -> mutableSessions.value.forEach { a.put(it.id) } }.toString())
    }

    private fun readRecord(dir: File): BuildSession? = try {
        val o = JSONObject(File(dir, RECORD).readText())
        val c = o.optJSONObject("config")
        BuildSession(
            o.getString("id"), o.getString("game"), PortPhase.valueOf(o.getString("phase")), o.optString("out"),
            o.getLong("startedAt"), o.optLong("seconds"), c?.let {
                GameConfig(it.optBoolean("touch"), it.optBoolean("fps60"), it.optBoolean("experimental"), it.optInt("alias", -1))
            }, o.optString("stage"), o.optDouble("progress").toFloat(), o.optBoolean("legacy"), o.optBoolean("logAvailable", true),
        )
    } catch (_: Exception) { null }

    private fun writeRecord(s: BuildSession) {
        val c = s.config?.let { JSONObject().put("touch", it.touch).put("fps60", it.fps60).put("experimental", it.experimental).put("alias", it.aliasHeadroom) }
        val o = JSONObject().put("schemaVersion", 1).put("id", s.id).put("game", s.game).put("phase", s.phase.name)
            .put("out", s.output).put("startedAt", s.startedAt).put("seconds", s.seconds).put("stage", s.stage)
            .put("progress", s.progress).put("legacy", s.legacy).put("logAvailable", s.logAvailable)
        if (c != null) o.put("config", c)
        check(atomicWrite(File(dir(s.id), RECORD), o.toString())) { "Cannot save build history" }
    }

    private fun atomicWrite(file: File, text: String): Boolean = try {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (tmp.renameTo(file)) true else { tmp.delete(); false }
    } catch (_: Exception) { false }

    private fun dir(id: String) = File(root, id.replace(Regex("[^A-Za-z0-9_-]"), "_"))

    companion object {
        const val KEEP = 20
        private const val MIGRATED = "build.sessions.migrated"
        private const val INDEX = "index.json"
        private const val RECORD = "record.json"
        private const val LOG = "log.txt"
        private val order = compareByDescending<BuildSession> { it.startedAt }.thenByDescending { it.id }

        /** The game file's name, safe as a file name (the bundle's entries) */
        fun safeName(game: String): String = File(game).nameWithoutExtension.replace(Regex("[^A-Za-z0-9_-]"), "_")
    }
}
