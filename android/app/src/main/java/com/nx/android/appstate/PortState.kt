package com.nx.android.appstate

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nx.android.R
import com.nx.android.data.AppLocale
import com.nx.android.data.AppLog
import com.nx.android.data.PrefsStore
import com.nx.android.data.PrefsStore.Companion.PORT_RUNNING
import com.nx.android.data.PrefsStore.Companion.PORT_STARTED_AT
import com.nx.android.domain.PortEvents
import com.nx.android.domain.porting.PortPhase
import com.nx.android.domain.porting.PortPhaseTransitions
import com.nx.android.domain.GameConfig
import com.nx.android.domain.porting.BuildSession
import com.nx.android.data.porting.BuildSessionRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The port that runs now and the ones that ran before: the one source the activity bar, the notification, the
 * builds page and the library's cards all read, so the places cannot disagree. The writes come from the runner's
 * thread; Compose's snapshot state takes writes from any thread. One application-scoped instance lives in Hilt — the
 * service and the UI share it.
 */
@Singleton
class PortState @Inject constructor(
    private val prefs: PrefsStore,
    private val locale: AppLocale,
    private val sessions: BuildSessionRepository,
) {

    /** A finished (or interrupted) port, for the builds page's history */
    data class Record(
        val game: String,      // the source file's name
        val phase: PortPhase,  // DONE / FAILED / CANCELLED / INTERRUPTED
        val out: String,       // the artifact the port made (the data pack; '' when none)
        val startedAt: Long,   // wall clock of the start
        val seconds: Long,     // how long it took
        val id: String = "",
        val config: GameConfig? = null,
        val legacy: Boolean = false,
        val logAvailable: Boolean = true,
    )

    var phase by mutableStateOf(PortPhase.IDLE); private set
    var gameFile by mutableStateOf(""); private set
    var stageOf by mutableStateOf(0); private set
    var stages by mutableStateOf(0); private set
    var stageName by mutableStateOf(""); private set
    var stageStep by mutableStateOf(""); private set
    var stageNote by mutableStateOf(""); private set
    var progress by mutableStateOf(0f); private set
    var apkPath by mutableStateOf(""); private set   // the PC flow's APK ('' in the app's mode: the pack is the only artifact)
    var dataPath by mutableStateOf(""); private set  // the data pack, both modes' artifact
    var startedAt by mutableStateOf(0L); private set
    var log by mutableStateOf(listOf<String>()); private set
    var history by mutableStateOf(listOf<Record>()); private set
    var buildId by mutableStateOf(""); private set

    val running: Boolean get() = phase == PortPhase.RUNNING

    /** The port's artifact (the pack in the app's mode, the APK on the PC flow); '' while it runs */
    val outPath: String get() = apkPath.ifEmpty { dataPath }

    /** Loads the history and answers the question "was a port interrupted?": the marker is still set */
    fun attach() {
        history = sessions.load().map(::toRecord)
        if (phase != PortPhase.RUNNING &&
            (prefs.str(PORT_RUNNING).isNotEmpty() || sessions.sessions.value.any { !it.terminal })) markInterrupted()
    }

    /** The port begins: fresh state, and the marker that survives the process (the interrupted case reads it) */
    fun begin(game: String, config: GameConfig = GameConfig()) {
        val session = sessions.create(game, config)
        history = sessions.load().map(::toRecord)
        phase = PortPhase.RUNNING
        buildId = session.id
        gameFile = game
        stageOf = 0; stages = 0; stageName = ""; stageStep = ""; stageNote = ""
        progress = 0f
        apkPath = ""
        dataPath = ""
        log = listOf()
        startedAt = session.startedAt
        prefs.put(PORT_RUNNING, game)
        // 进程被杀时，靠这个记着构建是什么时候开始的（工作区是那次构建留下的）
        prefs.put(PORT_STARTED_AT, startedAt.toString())
        say(locale.str(R.string.port_begin, game))
    }

    /** One line of the porter's output: an event moves the state, plain text goes into the log */
    fun onLine(line: String) {
        val ev = PortEvents.parse(line)
        if (ev == null) { say(line); return }
        when (ev.kind) {
            "say" -> say(ev.message)
            "stage" -> {
                stageOf = ev.stage; stages = ev.stages
                stageName = ev.name; stageStep = ""; stageNote = ""
                sessions.update(buildId) { it.copy(stage = stageName) }
                say(locale.str(R.string.port_log_stage, ev.stage, ev.stages, ev.name))
            }
            "progress" -> {
                if (ev.frac >= 0) progress = ev.frac
                if (ev.name.isNotEmpty()) stageName = ev.name
                if (ev.step.isNotEmpty() && ev.step != stageStep) {
                    stageStep = ev.step
                    say("  · $stageName $stageStep")
                }
                // the stage's own work (the rewriter's progress): a long stage is not a silence
                if (ev.note.isNotEmpty() && ev.note != stageNote) {
                    stageNote = ev.note
                    say("  · $stageName $stageNote")
                }
            }
            "result" -> {
                apkPath = ev.apk
                dataPath = ev.data
                progress = 1f
                sessions.update(buildId) { it.copy(output = outPath, progress = 1f) }
            }
        }
    }

    /** The porter ended on its own: an APK means done, none means failed */
    fun finish() {
        val terminal = PortPhaseTransitions.finish(phase, outPath.isNotEmpty())
        if (terminal == phase) return   // a cancel already marked it
        end(terminal)
    }

    /** The player stopped it: the porter and the tools it started are already dying */
    fun cancel() {
        val terminal = PortPhaseTransitions.cancel(phase)
        if (terminal == phase) return
        end(terminal)
    }

    /** The system killed the port (the marker is set, nothing runs): shown as interrupted, restartable */
    fun markInterrupted() {
        val active = sessions.sessions.value.firstOrNull { !it.terminal }
        val game = prefs.str(PORT_RUNNING).ifEmpty { active?.game.orEmpty() }
        if (game.isEmpty()) return
        if (PortPhaseTransitions.interrupted(phase, markerPresent = true) == phase && phase != PortPhase.IDLE) return
        active?.let { active ->
            buildId = active.id
            gameFile = active.game
            dataPath = active.output
            startedAt = active.startedAt
        }
        // 那次构建开始的时间还在 prefs 里：恢复它，历史与工作区才对得上
        prefs.str(PORT_STARTED_AT).toLongOrNull()?.let { startedAt = it }
        if (buildId.isEmpty()) {
            val legacy = sessions.recoverLegacyInterrupted(game, startedAt)
            buildId = legacy.id
            gameFile = game
        }
        end(PortPhase.INTERRUPTED, gameOverride = game)
    }

    private fun end(p: PortPhase, gameOverride: String = "") {
        val secs = if (startedAt > 0) (System.currentTimeMillis() - startedAt) / 1000 else 0
        val game = gameOverride.ifEmpty { gameFile }
        phase = p
        startedAt = 0
        prefs.put(PORT_RUNNING, null)
        prefs.put(PORT_STARTED_AT, null)
        say(
            when (p) {
                PortPhase.DONE -> locale.str(R.string.port_done_path, outPath)
                PortPhase.FAILED -> locale.str(R.string.port_failed_no_artifact)
                PortPhase.CANCELLED -> locale.str(R.string.common_cancelled)
                PortPhase.INTERRUPTED -> locale.str(R.string.port_interrupted, game)
                else -> return
            }
        )
        if (buildId.isNotEmpty()) {
            sessions.update(buildId) { it.copy(phase = p, output = outPath, seconds = secs) }
            history = sessions.load().map(::toRecord)
        }
        if (game.isNotEmpty()) {
            if (history.none { it.id == buildId }) {
                history = (listOf(Record(game, p, outPath, System.currentTimeMillis() - secs * 1000, secs, buildId)) + history).take(20)
            }
        }
        buildId = ""
    }

    fun say(s: String) {
        log = (log + s).takeLast(400)
        if (buildId.isNotEmpty()) sessions.append(buildId, s) else AppLog.line(s)
    }

    /** The player cleared the log: the tail goes too, so the page shows what the file has (nothing) */
    fun clearLog() {
        log = listOf()
    }

    private fun toRecord(s: BuildSession) = Record(s.game, s.phase, s.output, s.startedAt, s.seconds,
        s.id, s.config, s.legacy, s.logAvailable)
}
