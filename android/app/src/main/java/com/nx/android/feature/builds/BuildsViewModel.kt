package com.nx.android.feature.builds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.snapshotFlow
import com.nx.android.appstate.LibraryState
import com.nx.android.appstate.PortState
import com.nx.android.appstate.EnvCheckState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import com.nx.android.domain.porting.PortPhase
import com.nx.android.domain.BuildOptions
import com.nx.android.domain.GameConfig
import com.nx.android.domain.GameInfo
import com.nx.android.domain.GameInspector
import com.nx.android.data.PrefsStore
import com.nx.android.data.porting.BuildSessionRepository
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BuildsUiState(
    val target: String? = null,
    val phase: PortPhase = PortPhase.IDLE,
    val progress: Float = 0f,
    val gameFile: String = "",
    val output: String = "",
    val startedAt: Long = 0L,
    val stageOf: Int = 0,
    val stages: Int = 0,
    val stageName: String = "",
    val stageStep: String = "",
    val log: List<String> = emptyList(),
    val history: List<PortState.Record> = emptyList(),
    val running: Boolean = false,
)

/** What the page has to say out loud by itself. */
enum class BuildsNotice {
    /** The game has no profile for Android: the experimental port is ticked and the player is told why. */
    EXPERIMENTAL_AUTO,
}

@HiltViewModel
class BuildsViewModel @Inject constructor(
    val library: LibraryState,
    val port: PortState,
    private val sessions: BuildSessionRepository,
    inspector: GameInspector,
    private val prefs: PrefsStore,
    private val envCheck: EnvCheckState,
) : ViewModel() {
    /** The game profiles the player imported (the app does not carry them) */
    val profilesCount: Int get() = envCheck.profilesCount

    /** The imported profiles that are ported to Android (the rest are upstream's iOS ones) */
    val profilesPorted: Int get() = envCheck.profilesPorted

    /** Reads the imported profiles again: a package may have been imported since the page was last on screen */
    fun refreshProfiles() = envCheck.refreshProfiles()
    var selectedBuildId by mutableStateOf("")
        private set
    var preparedConfig by mutableStateOf<GameConfig?>(null)
        private set
    var preparedConfigWasMissing by mutableStateOf(false)
        private set
    var preparing by mutableStateOf(false)
        private set

    /** What the selected game file holds (the porter's own reading of it) */
    val gameInfo = GameInfoReader(inspector, viewModelScope)

    /** A message for the screen: the counter gives every notice its own, so the same one can come twice. */
    var noticeSeq by mutableStateOf(0)
        private set
    var notice by mutableStateOf<BuildsNotice?>(null)
        private set

    // Eagerly：这一页不在眼前时也跟着一份状态（切回来时第一帧就是选中的那个游戏，不是空的那一页）
    val uiState: StateFlow<BuildsUiState> = snapshotFlow {
        BuildsUiState(
            target = library.buildsTarget?.absolutePath,
            phase = port.phase,
            progress = port.progress,
            gameFile = port.gameFile,
            output = port.outPath,
            startedAt = port.startedAt,
            stageOf = port.stageOf,
            stages = port.stages,
            stageName = port.stageName,
            stageStep = port.stageStep,
            log = port.log,
            history = port.history,
            running = port.running,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BuildsUiState())

    init {
        // 每读完一次游戏信息就照着它做一次决定（读到的答案属于当前选中的那个文件）
        viewModelScope.launch {
            snapshotFlow { gameInfo.reads }.collect { applyProfile(gameInfo.info) }
        }
    }

    fun setTarget(file: File?, config: GameConfig? = null, savedConfigMissing: Boolean = false) {
        selectedBuildId = ""
        preparedConfig = config
        preparedConfigWasMissing = savedConfigMissing
        preparing = file != null
        library.buildsTarget = file
        if (file == null) gameInfo.clear() else gameInfo.read(file, prefs.keys)
    }

    fun buildStarted() { preparing = false }

    fun openBuild(id: String) { selectedBuildId = id }
    fun closeBuild() { selectedBuildId = "" }
    fun logTail(id: String): List<String> = if (id == port.buildId) port.log else sessions.tail(id)
    fun fullLog(id: String): String = runCatching {
        val file = sessions.logFile(id) ?: return ""
        file.readText(Charsets.UTF_8)
    }.getOrDefault("")

    fun clearLog() = port.clearLog()

    /** The screen has said the notice out loud. */
    fun clearNotice() { notice = null }

    /**
     * The game the porter has no profile for: the port can only run as an experimental one, so the option is
     * ticked for the player and the reason is given. The choice is written to the game's own options, so the
     * library's panel and the next build say the same.
     */
    private fun applyProfile(info: GameInfo?) {
        if (info == null) return
        val file = library.buildsTarget ?: return
        val config = preparedConfig ?: library.configOf(file)
        val updated = BuildOptions.withExperimental(config, info) ?: return
        library.setConfig(file, updated)
        preparedConfig = updated
        notice = BuildsNotice.EXPERIMENTAL_AUTO
        noticeSeq++
    }
}
