package com.nx.android.feature.builds

import com.nx.android.domain.porting.PortPhase

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nx.android.R
import com.nx.android.appstate.LibraryState
import com.nx.android.appstate.PortState
import com.nx.android.data.AppLog
import com.nx.android.domain.ALIAS_AUTO
import com.nx.android.domain.GameFile
import com.nx.android.domain.GameIdentity
import com.nx.android.domain.GameInfo
import com.nx.android.domain.GameProfileStatus
import com.nx.android.domain.GAME_FILE_EXTENSIONS
import com.nx.android.domain.GameStatus
import com.nx.android.domain.LibraryLogic
import com.nx.android.domain.Ported
import com.nx.android.app.AppActions
import com.nx.android.core.ui.LocalAppLocale
import com.nx.android.core.ui.components.CapsuleButton
import com.nx.android.core.ui.components.Hint
import com.nx.android.feature.filepicker.PickerMode
import com.nx.android.feature.filepicker.PickerRequest
import com.nx.android.core.ui.components.PickRow
import com.nx.android.core.ui.components.QuietButton
import com.nx.android.core.ui.components.Section
import com.nx.android.core.ui.components.StatusDot
import com.nx.android.core.ui.components.rememberPageScroll
import com.nx.android.core.ui.elapsedSecs
import com.nx.android.core.ui.theme.GlassPanel
import com.nx.android.core.ui.theme.NxPalette
import java.io.File

/** A filename that includes a title ID must agree with the card being rebuilt. Renamed files are allowed. */
private fun sourceMatchesTarget(target: File, candidate: File): Boolean {
    val expected = GameIdentity.titleIdIn(target.name) ?: return true
    return LibraryLogic.sourceMatchesTitle(candidate.name, expected)
}

/**
 * 构建页：发起与跟踪移植的工作台，按移植的顺序排卡——目标（游戏与它的参数）、进度与取消、产物、日志、历史。
 * 游戏库里点「▶ 构建」把游戏带进来（buildsTarget）；开始按钮在这里，前提的检查与说明也在这里。
 */
@Composable
fun BuildsRoute(viewModel: BuildsViewModel, keys: String, envReady: Boolean,
                actions: AppActions, onRequest: (PickerRequest) -> Unit, onLog: (String) -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val locale = LocalAppLocale.current
    LaunchedEffect(uiState.running) { if (uiState.running) viewModel.buildStarted() }
    // 页面自己要说的话（没有档案的游戏被勾上了实验性移植）：屏幕把它念出来
    LaunchedEffect(viewModel.noticeSeq) {
        when (viewModel.notice) {
            BuildsNotice.EXPERIMENTAL_AUTO -> {
                viewModel.clearNotice()
                actions.onToast(locale.str(R.string.builds_experimental_auto))
            }
            null -> Unit
        }
    }
    BuildsScreen(viewModel.library, viewModel, uiState, keys, envReady, actions, onRequest, onLog)
}

@Composable
private fun BuildsScreen(lib: LibraryState, viewModel: BuildsViewModel, uiState: BuildsUiState,
                         keys: String, envReady: Boolean, actions: AppActions,
                         onRequest: (PickerRequest) -> Unit, onLog: (String) -> Unit) {
    val locale = LocalAppLocale.current
    val running = uiState.running
    val target = uiState.target?.let(::File) ?: uiState.gameFile.takeIf { running }?.let { File(it) }
    val selected = uiState.history.firstOrNull { it.id == viewModel.selectedBuildId }
    val current = uiState.history.firstOrNull { it.id == viewModel.port.buildId }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberPageScroll()).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            selected != null -> {
                TaskDetail(lib, selected, viewModel, actions, onLog, active = selected.id == viewModel.port.buildId && running)
                QuietButton(stringResource(R.string.common_back), { viewModel.closeBuild() })
            }
            target != null && viewModel.preparing && !running ->
                TargetCard(lib, uiState, viewModel, keys, envReady, target, actions, onRequest)
            current != null -> TaskDetail(lib, current, viewModel, actions, onLog, active = running)
            running -> ProgressCard(uiState, actions)
            else -> {
                Section(stringResource(R.string.builds_start_title)) {
                    Text(stringResource(R.string.builds_start_hint), style = MaterialTheme.typography.bodyMedium)
                    CapsuleButton(stringResource(R.string.builds_choose_game_file), onClick = {
                        onRequest(PickerRequest(PickerMode.FILE, GAME_FILE_EXTENSIONS, File("/storage"),
                            locale.str(R.string.builds_choose_game_file), onPicked = { viewModel.setTarget(it) }))
                    })
                }
                if (uiState.history.isNotEmpty()) History(uiState, viewModel)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun TaskDetail(lib: LibraryState, record: PortState.Record, viewModel: BuildsViewModel,
                       actions: AppActions, onLog: (String) -> Unit, active: Boolean) {
    val lines = viewModel.logTail(record.id)
    // 进行中的这一轮：秒表从它的起点算（record.seconds 是结束时才写下的时长，进行中永远是 0）
    val secs = if (active) elapsedSecs(record.startedAt) else record.seconds
    Section(stringResource(R.string.builds_result)) {
        Text(File(record.game).name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.builds_history_seconds, (secs / 60).toString(), (secs % 60).toString()))
        PhaseChip(if (active) PortPhase.RUNNING else record.phase)
        if (active && viewModel.port.stageName.isNotEmpty()) {
            Text(viewModel.port.stageName, style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { viewModel.port.progress }, modifier = Modifier.fillMaxWidth().height(6.dp))
            QuietButton(stringResource(R.string.builds_cancel_port), actions.onCancelPort, modifier = Modifier.fillMaxWidth())
        }
        record.config?.let { config ->
            val options = listOfNotNull(
                stringResource(R.string.builds_touch_game).takeIf { config.touch },
                stringResource(R.string.builds_60fps).takeIf { config.fps60 },
                stringResource(R.string.builds_experimental).takeIf { config.experimental },
                if (config.aliasHeadroom >= 0) stringResource(R.string.builds_alias_short, config.aliasHeadroom) else null,
            ).ifEmpty { listOf(stringResource(R.string.builds_default_options)) }
            Hint(stringResource(R.string.builds_used_options, options.joinToString(" · ")))
        }
        if (record.legacy) Text(stringResource(R.string.builds_log_legacy), style = MaterialTheme.typography.bodySmall)
        else if (!record.logAvailable) Text(stringResource(R.string.builds_log_unavailable), style = MaterialTheme.typography.bodySmall,
            color = NxPalette.Danger)
        LogTail(lines)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onLog(record.id) }) { Text(stringResource(R.string.builds_log_view)) }
            if (record.logAvailable && lines.isNotEmpty()) TextButton(onClick = { actions.onShareBuildLog(record.id) }) {
                Text(stringResource(R.string.builds_log_share))
            }
        }
        if (!active && File(record.game).isFile()) {
            QuietButton(stringResource(R.string.builds_build_again), {
                viewModel.setTarget(File(record.game), record.config, savedConfigMissing = record.config == null)
            })
        }
        if (!active && !File(record.game).isFile()) QuietButton(stringResource(R.string.builds_build_again), {
            viewModel.setTarget(File(record.game), record.config, savedConfigMissing = record.config == null)
        })
        if (record.phase == PortPhase.DONE && record.out.isNotEmpty()) {
            val currentPack = lib.portFromPack(File(record.out))
            if (currentPack != null) QuietButton(stringResource(R.string.builds_run_action), {
                actions.onRun(currentPack.pack, GameIdentity.labelFor(File(record.game), currentPack))
            })
            else Text(stringResource(R.string.lib_artifact_missing_state), style = MaterialTheme.typography.bodySmall,
                color = NxPalette.Danger)
        }
        if (record.phase != PortPhase.DONE && lib.portOf(File(record.game)) != null)
            Hint(stringResource(R.string.builds_old_pack_still_usable))
    }
}

// ---- 目标卡（未运行时）：游戏与参数，开始按钮 ------------------------------------------------------------------

@Composable
private fun TargetCard(lib: LibraryState, port: BuildsUiState, viewModel: BuildsViewModel, keys: String, envReady: Boolean,
                       f: File, actions: AppActions, onRequest: (PickerRequest) -> Unit) {
    val locale = LocalAppLocale.current
    val status = lib.statusOf(f)
    var cfg by remember(f.absolutePath, viewModel.preparedConfig) {
        mutableStateOf(viewModel.preparedConfig ?: lib.configOf(f))
    }
    // 配置默认展开：选中游戏后玩家一眼看到当前选项
    var configOpen by remember(f.absolutePath) { mutableStateOf(true) }
    val storageOk = actions.storageOk()
    val canStart = f.isFile && f.canRead() && envReady && storageOk && !port.running && keys.isNotBlank() && status != GameStatus.PORTING
    val info = viewModel.gameInfo.info

    // 三张卡，一件事一张：目标（是哪个游戏、文件在哪）· 识别到的文件 · 构建选项
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section(stringResource(R.string.builds_target)) {
            // 身份块：状态徽章、游戏名、title ID、库的状态
            TargetIdentity(f, info, status, lib.portOf(f))

            // 来源行：文件在哪（整条路径，省略中间的目录；按一下看全的）＋ 更换 / 清除
            // 名字就是文件名的时候（没有档案），路径只给到文件夹——文件名上面已经写着
            val source = if (info?.name?.isNotEmpty() == true) f.absolutePath else (f.parentFile?.path ?: f.absolutePath)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.builds_source, shortPath(source)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clickable { actions.onToast(f.absolutePath) },
                )
                QuietButton(stringResource(R.string.builds_change), onClick = {
                    onRequest(PickerRequest(PickerMode.FILE, GAME_FILE_EXTENSIONS,
                        File("/storage"), locale.str(R.string.builds_choose_game_file),
                        onPicked = { nf ->
                            if (sourceMatchesTarget(f, nf)) viewModel.setTarget(nf)
                            else actions.onToast(locale.str(R.string.lib_source_mismatch))
                        }))
                })
                QuietButton(stringResource(R.string.builds_clear), onClick = { viewModel.setTarget(null) })
            }
            if (viewModel.preparedConfigWasMissing) Hint(stringResource(R.string.builds_config_missing_saved))
        }

        // 识别到的文件：移植器认出了什么、从哪认出的、这一轮打哪个版本，以及文件该怎么放
        if (info != null || viewModel.gameInfo.reading) {
            Section(stringResource(R.string.builds_files_title)) {
                if (info == null) {
                    Hint(stringResource(R.string.builds_files_reading))
                } else {
                    if (info.base.isNotEmpty()) {
                        FileLine(stringResource(R.string.builds_files_base), info.base,
                            onClick = { actions.onToast(info.base) })
                    }
                    FileList(stringResource(R.string.builds_files_update), info.updates, actions)
                    FileList(stringResource(R.string.builds_files_dlc), info.dlc, actions)
                    if (info.packVersion.isNotEmpty()) {
                        Text(stringResource(R.string.builds_info_pack, info.packVersion),
                            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)
                    }
                    Hint(stringResource(R.string.builds_files_hint), size = 11.sp)
                }
            }
        }

        // 构建选项：选项（可收起）与开始按钮
        OptionsCard(open = configOpen, onToggle = { configOpen = !configOpen }) {
            if (configOpen) {
                ConfigRow(stringResource(R.string.builds_touch_game), cfg.touch) { cfg = cfg.copy(touch = it); lib.setConfig(f, cfg) }
                ConfigRow(stringResource(R.string.builds_60fps), cfg.fps60) { cfg = cfg.copy(fps60 = it); lib.setConfig(f, cfg) }
                ConfigRow(stringResource(R.string.builds_experimental), cfg.experimental) { cfg = cfg.copy(experimental = it); lib.setConfig(f, cfg) }
                AliasRow(cfg.aliasHeadroom) { cfg = cfg.copy(aliasHeadroom = it); lib.setConfig(f, cfg) }
                Hint(stringResource(R.string.builds_alias_hint), size = 11.sp)
            }

            if (!canStart) {
                Text(
                    when {
                        !envReady -> stringResource(R.string.builds_need_env)
                        !storageOk -> stringResource(R.string.builds_need_storage)
                        keys.isBlank() -> stringResource(R.string.builds_need_keys)
                        !f.isFile -> stringResource(R.string.builds_source_missing)
                        !f.canRead() -> stringResource(R.string.builds_needs_storage)
                        port.running -> stringResource(R.string.builds_one_at_a_time, File(port.gameFile).name)
                        else -> ""
                    },
                    color = NxPalette.Building, style = MaterialTheme.typography.bodySmall,
                )
            }
            if (status != GameStatus.PORTING) {
                CapsuleButton(stringResource(R.string.builds_start_action), {
                    actions.onStartPort(f, cfg)
                }, enabled = canStart, modifier = Modifier.fillMaxWidth())
            } else {
                Hint(stringResource(R.string.builds_porting))
            }
        }
    }
}

/** 构建选项卡：标题行点一下收起/展开选项，下面永远是开始构建的按钮 */
@Composable
private fun OptionsCard(open: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    GlassPanel(modifier = Modifier.fillMaxWidth(), contentPadding = 16.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { onToggle() },
        ) {
            Text(stringResource(R.string.builds_options_title),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

/** 认到的文件的一行：分类标签 + 文件名（+ 它在哪个子文件夹里） */
@Composable
private fun FileLine(label: String, name: String, folder: String = "", onClick: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().let { if (onClick == null) it else it.clickable { onClick() } },
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp, modifier = Modifier.width(32.dp))
        Text(name, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (folder.isNotEmpty()) {
            Text(stringResource(R.string.builds_files_folder, folder),
                style = MaterialTheme.typography.bodySmall, color = NxPalette.Brand, fontSize = 11.sp)
        }
    }
}

/** 一类文件：本体旁边的更新或 DLC。多了只列前几个，其余的说明还有多少（点一下全列出来） */
@Composable
private fun FileList(label: String, files: List<GameFile>, actions: AppActions) {
    if (files.isEmpty()) return
    val shown = files.take(2)
    for (f in shown) {
        FileLine(label, f.name, f.folder, onClick = { actions.onToast(f.detail()) })
    }
    if (files.size > shown.size) {
        Text(stringResource(R.string.builds_files_more, files.size - shown.size),
            style = MaterialTheme.typography.bodySmall, color = NxPalette.Brand, fontSize = 11.sp,
            modifier = Modifier
                .padding(start = 40.dp)
                .clickable { actions.onToast(files.joinToString("\n") { it.detail() }) })
    }
}

/** 一个文件说全了：名字、版本与它所在的子文件夹 */
private fun GameFile.detail(): String =
    listOf(name, version.takeIf { it.isNotEmpty() }?.let { "($it)" }, folder.takeIf { it.isNotEmpty() }?.let { "$it/" })
        .filterNotNull().joinToString("  ")

/**
 * 目标卡的身份块：这是哪个游戏（状态徽章 + 名字）、它的 title ID、这一轮会打包哪个版本，
 * 以及同一文件夹里有多少更新与 DLC。
 *
 * 名字与 title ID 各只在这里出现一次；源文件的路径另起一行。
 * 移植器的读数没回来之前不显示猜测出来的东西：徽章、计数与打包版本都等它。
 */
@Composable
private fun TargetIdentity(f: File, info: GameInfo?, status: GameStatus, ported: Ported?) {
    // 名字：读数回来后用档案里的（没有就用文件名的）
    val name = info?.name?.takeIf { it.isNotEmpty() } ?: GameIdentity.labelFor(f, ported)
    val titleId = info?.titleId?.takeIf { it.isNotEmpty() } ?: GameIdentity.titleIdIn(f.name)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (info != null) ProfileChip(info.status)
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        if (titleId != null || status != GameStatus.NOT_PORTED) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (titleId != null) {
                    Text(titleId, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
                // 库的状态：只有非常态才出现（"未移植" 是默认态，不占位）
                if (status != GameStatus.NOT_PORTED) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        StatusDot(statusColor(status), 8.dp)
                        Text(statusLabel(status), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

/** 档案状态：一种状态一个颜色，与游戏库那张卡上的是同一套 */
@Composable
private fun ProfileChip(status: GameProfileStatus) {
    val (label, color) = when (status) {
        GameProfileStatus.PLAYABLE -> stringResource(R.string.builds_info_playable) to NxPalette.Ready
        GameProfileStatus.WIP -> stringResource(R.string.builds_info_wip) to NxPalette.Building
        GameProfileStatus.BOOTS -> stringResource(R.string.builds_info_boots) to NxPalette.Building
        GameProfileStatus.EXPERIMENTAL -> stringResource(R.string.builds_info_experimental) to NxPalette.Unbuilt
        GameProfileStatus.IOS_ONLY -> stringResource(R.string.builds_info_ios_only) to NxPalette.Unbuilt
        GameProfileStatus.UNSUPPORTED -> stringResource(R.string.builds_info_unsupported) to NxPalette.Brand
    }
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.18f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusDot(color, 8.dp)
            Text(label, fontSize = 12.sp, color = color, fontWeight = FontWeight.Bold)
        }
    }
}

/** 路径省略中间：头一段与最后两段留着，中间用省略号（文件在哪，一眼看得出；全路径按一下就看全了） */
internal fun shortPath(path: String): String {
    val parts = path.split('/').filter { it.isNotEmpty() }
    if (parts.size <= 4) return path
    return "/" + parts[0] + "/…/" + parts.takeLast(2).joinToString("/")
}

// ---- 进度卡（运行时） -------------------------------------------------------------------------------------------

@Composable
private fun ProgressCard(port: BuildsUiState, actions: AppActions) {
    val secs = elapsedSecs(port.startedAt)
    Section(stringResource(R.string.builds_port_action)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PhaseChip(port.phase)
            Text(
                File(port.gameFile).name,
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f), maxLines = 2,
            )
            if (port.startedAt > 0) {
                Text(stringResource(R.string.builds_minutes_seconds, secs / 60, secs % 60),
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (port.progress > 0f || port.stageName.isNotEmpty()) {
            LinearProgressIndicator(
                progress = { port.progress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = NxPalette.Building,
            )
            if (port.stageName.isNotEmpty()) {
                Text(
                    stringResource(R.string.port_stage_of, port.stageOf, port.stages, port.stageName) +
                        (if (port.stageStep.isEmpty()) "" else " · ${port.stageStep}"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (port.phase == PortPhase.RUNNING) {
            QuietButton(stringResource(R.string.builds_cancel_port), { actions.onCancelPort() }, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ---- 日志模块（尾部 + 展开 + 查看/分享/清空） -----------------------------------------------

/**
 * 日志模块：工具说过的话的尾部，加上把它带走的三个动作——查看全部、分享日志、清空日志。
 *
 * 屏幕上给的是内存里的那一份（移植进行中它最新）；App 重启之后内存里空了，就退回文件里那一份
 * （上一轮留下的）。「查看全部」与「分享日志」给的始终是文件里的内容，不只是屏幕上这些行。
 */
@Composable
private fun LogCard(port: BuildsUiState, actions: AppActions, onView: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    // 内存里没有输出时（App 重启之后），读文件里那一份
    var fileTail by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(port.log.isEmpty()) {
        if (port.log.isEmpty()) fileTail = AppLog.text(400).lines()
    }
    val lines = if (port.log.isNotEmpty()) port.log else fileTail
    Section(stringResource(R.string.common_log)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.builds_log_hint), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            QuietButton(if (open) stringResource(R.string.builds_collapse) else stringResource(R.string.builds_expand), { open = !open })
        }
        LogTail(lines, if (open) 320.dp else 160.dp, if (open) 200 else 30)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onView) { Text(stringResource(R.string.builds_log_view)) }
            TextButton(onClick = { actions.onShareLog() }) { Text(stringResource(R.string.builds_log_share)) }
            // 清空：文件与屏幕上这份一起清（动作里清的是内存那份，这里清的是文件那份的读出）
            TextButton(onClick = { actions.onClearLog(); fileTail = listOf() }) { Text(stringResource(R.string.builds_log_clear)) }
        }
    }
}

/** The log's last lines (mono, the failures red) */
@Composable
fun LogTail(log: List<String>, height: Dp = 160.dp, lines: Int = 30) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val scroll = rememberScrollState()
        LaunchedEffect(log.size) { scroll.scrollTo(log.size * 1000) }
        Box(
            Modifier.fillMaxWidth().heightIn(min = 60.dp, max = height)
                .verticalScroll(scroll).padding(10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val shown = log.takeLast(lines)
                if (shown.isEmpty()) {
                    Text(stringResource(R.string.builds_no_output), style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    for (line in shown) {
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = if (line.startsWith("[RED]")) NxPalette.Danger else MaterialTheme.colorScheme.onSurface,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

// ---- 产物卡（已完成）/ 结果 -------------------------------------------------------------------------------------

@Composable
private fun OutcomeCard(lib: LibraryState, port: BuildsUiState, f: File, actions: AppActions) {
    val locale = LocalAppLocale.current
    val status = lib.statusOf(f)
    when (status) {
        GameStatus.DONE -> {
            val ported = lib.portOf(f) ?: return
            Section(locale.str(R.string.builds_artifact)) {
                Hint(locale.str(R.string.builds_artifact_path, ported.pack.absolutePath), size = 11.sp)
                CapsuleButton(locale.str(R.string.builds_run_action), { actions.onRun(ported.pack, GameIdentity.labelFor(f, ported)) }, modifier = Modifier.fillMaxWidth())
            }
        }
        else -> {
            val last = port.history.firstOrNull { it.game == f.absolutePath }
            if (last != null && last.phase != PortPhase.DONE) {
                Section(locale.str(R.string.builds_result)) {
                    Text(
                        when (last.phase) {
                            PortPhase.FAILED -> locale.str(R.string.builds_prev_failed)
                            PortPhase.CANCELLED -> locale.str(R.string.builds_prev_cancelled)
                            PortPhase.INTERRUPTED -> locale.str(R.string.builds_prev_interrupted)
                            else -> return@Section
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = NxPalette.Building,
                    )
                }
            }
        }
    }
}

/** One color per outcome, the same the library's cards and the settings wear */
@Composable
fun PhaseChip(p: PortPhase) {
    val (label, color) = when (p) {
        PortPhase.RUNNING -> stringResource(R.string.common_porting) to NxPalette.Building
        PortPhase.DONE -> stringResource(R.string.common_done) to NxPalette.Ready
        PortPhase.FAILED -> stringResource(R.string.common_failed) to NxPalette.Danger
        PortPhase.CANCELLED -> stringResource(R.string.common_cancelled) to NxPalette.Unbuilt
        PortPhase.INTERRUPTED -> stringResource(R.string.common_interrupted) to NxPalette.Danger
        PortPhase.IDLE -> stringResource(R.string.common_idle) to NxPalette.Unbuilt
    }
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.18f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusDot(color, 8.dp)
            Text(label, fontSize = 12.sp, color = color, fontWeight = FontWeight.Bold)
        }
    }
}

private fun statusColor(s: GameStatus) = when (s) {
    GameStatus.NOT_PORTED -> NxPalette.Unbuilt
    GameStatus.PORTING -> NxPalette.Building
    GameStatus.DONE -> NxPalette.Ready
    GameStatus.NEEDS_BUILD -> NxPalette.Unbuilt
}

@Composable
private fun statusLabel(s: GameStatus): String = when (s) {
    GameStatus.NOT_PORTED -> stringResource(R.string.common_not_ported)
    GameStatus.PORTING -> stringResource(R.string.common_porting)
    GameStatus.DONE -> stringResource(R.string.common_done)
    GameStatus.NEEDS_BUILD -> stringResource(R.string.lib_needs_build_status)
}

@Composable
private fun ConfigRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 额外地址空间（别名区）：Auto 或玩家手动指定的 GB 数。Auto 不带数值，移植器自己决定
 * （档案调通过的版本不加，没调通过的加 8 GB）；它编译进宿主的内存布局，改了要重新构建。
 */
private val ALIAS_CHOICES = listOf(ALIAS_AUTO, 0, 8, 16, 24, 32, 48)

@Composable
private fun AliasRow(headroom: Int, onChange: (Int) -> Unit) {
    val auto = stringResource(R.string.builds_alias_auto)
    PickRow(
        label = stringResource(R.string.builds_alias_headroom),
        options = ALIAS_CHOICES.map { it.toString() to (if (it < 0) auto else "$it GB") },
        current = headroom.toString(),
        onSelect = { onChange(it.toIntOrNull() ?: ALIAS_AUTO) },
        fallback = auto,
    )
}

/** Recent attempts: one row opens the result and log belonging to that attempt. */
@Composable
private fun History(port: BuildsUiState, viewModel: BuildsViewModel) {
    Section(stringResource(R.string.builds_history)) {
        for (r in port.history) {
            Column(Modifier.fillMaxWidth().clickable { viewModel.openBuild(r.id) }.padding(vertical = 8.dp)) {
                Text(File(r.game).name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, fontWeight = FontWeight.Bold)
                Hint(stringResource(R.string.builds_history_seconds, r.seconds / 60, r.seconds % 60) + " · " + when (r.phase) {
                    PortPhase.DONE -> stringResource(R.string.common_complete)
                    PortPhase.FAILED -> stringResource(R.string.common_failed)
                    PortPhase.CANCELLED -> stringResource(R.string.common_cancelled)
                    PortPhase.INTERRUPTED -> stringResource(R.string.common_interrupted)
                    else -> ""
                })
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
        Hint(stringResource(R.string.builds_history_retention))
    }
}
