package com.nx.android.feature.gamedetail

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.outlined.VideogameAsset
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nx.android.R
import com.nx.android.appstate.LibraryState
import com.nx.android.appstate.PlayerState
import com.nx.android.appstate.PortState
import com.nx.android.domain.drivers.Driver
import com.nx.android.data.GameIcon
import com.nx.android.data.GameLog
import com.nx.android.data.LogBundle
import com.nx.android.data.Player
import com.nx.android.data.SaveShare
import com.nx.android.data.StorageVolumes
import com.nx.android.domain.GameIdentity
import com.nx.android.domain.GAME_FILE_EXTENSIONS
import com.nx.android.domain.GameCleanup
import com.nx.android.domain.GameEntry
import com.nx.android.domain.GameStatus
import com.nx.android.domain.LibraryLogic
import com.nx.android.domain.PackMover
import com.nx.android.domain.Ported
import com.nx.android.domain.saves.SAVE_PACKAGE_EXTENSION
import com.nx.android.domain.saves.SaveRepository
import com.nx.android.domain.playback.RunOptionsSnapshot
import com.nx.android.app.AppActions
import com.nx.android.core.ui.LocalAppLocale
import com.nx.android.core.ui.components.CapsuleButton
import com.nx.android.core.ui.components.Hint
import com.nx.android.feature.filepicker.PickerMode
import com.nx.android.feature.filepicker.PickerRequest
import com.nx.android.core.ui.components.PickRow
import com.nx.android.core.ui.components.QuietButton
import com.nx.android.core.ui.components.StatusDot
import com.nx.android.core.ui.components.rememberGameIcon
import com.nx.android.core.ui.components.sizeText
import com.nx.android.core.ui.failureText
import com.nx.android.core.ui.theme.NxPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


@Composable
private fun statusLabel(s: GameStatus): String = when (s) {
    GameStatus.NOT_PORTED -> stringResource(R.string.common_not_ported)
    GameStatus.PORTING -> stringResource(R.string.common_porting)
    GameStatus.DONE -> stringResource(R.string.common_done)
    GameStatus.NEEDS_BUILD -> stringResource(R.string.lib_needs_build_status)
}

@Composable
private fun experimentalTag(ported: Ported?): String? =
    if (ported?.status == "experimental") stringResource(R.string.lib_experimental) else null

@Composable
private fun reportTag(ported: Ported?): String? =
    if (ported?.needsRuntimeUpdate == true) stringResource(R.string.lib_needs_report) else null

private fun versionTag(ported: Ported?): String? =
    ported?.display?.trim()?.takeIf { it.isNotEmpty() }

private enum class Removal { CACHE, DELETE }

/** 一次破坏性动作的请求：做什么、要删哪些东西、哪些够不着（卡不在时） */
private data class RemovalRequest(val kind: Removal, val plan: GameCleanup.Plan,
                                  val unreachable: List<File> = emptyList())

/**
 * 移植配置面板：文件路径、状态与该游戏自己的选项，开始移植与安装也从这里走。
 * 由页面的根在最上层画（MainScreen），它的遮罩要盖住顶栏。
 */
@Composable
fun GameDetailRoute(lib: LibraryState, port: PortState, player: PlayerState,
                    e: GameEntry, installedDrivers: List<Driver>, actions: AppActions, onGoBuilds: () -> Unit,
                    onRequest: (PickerRequest) -> Unit, onClose: () -> Unit) {
    val viewModel: GameDetailViewModel = hiltViewModel(key = "game-detail-${e.titleId}")
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val locale = LocalAppLocale.current
    LaunchedEffect(e.titleId, e.pack?.pack) { viewModel.open(e.titleId, e.pack?.pack) }
    DisposableEffect(viewModel, e.titleId) {
        onDispose { viewModel.close(e.titleId) }
    }
    val shareFile: (File) -> Unit = { file ->
        runCatching {
            context.startActivity(Intent.createChooser(SaveShare.intent(context, file), locale.str(R.string.lib_share_saves)))
        }.onFailure { actions.onToast(locale.str(R.string.lib_no_receiver)) }
    }
    GameDetailScreen(lib, port, player, e, installedDrivers, viewModel.saveRepository, shareFile,
        uiState, viewModel::selectLanguage, viewModel::selectDriver, actions, onGoBuilds, onRequest, onClose)
}

@Composable
private fun GameDetailScreen(lib: LibraryState, port: PortState, player: PlayerState,
                        e: GameEntry, installedDrivers: List<Driver>, saveRepository: SaveRepository,
                        shareFile: (File) -> Unit, detailState: GameDetailUiState,
                        onSelectLanguage: (String?) -> Unit, onSelectDriver: (String?) -> Unit,
                        actions: AppActions, onGoBuilds: () -> Unit,
                        onRequest: (PickerRequest) -> Unit, onClose: () -> Unit) {
    val locale = LocalAppLocale.current
    val status = lib.statusOf(e)
    // Title ID：游戏的身份（还没构建过的还没有）
    val titleId = detailState.titleId ?: e.titleId
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var removal by remember { mutableStateOf<RemovalRequest?>(null) }
    // 可移动卡（SD 卡）上 App 自己的端口目录：插着才有
    val cardDirs = remember { StorageVolumes.removablePortsDirs(context) }
    // 搬移：等玩家确认的目标目录（null = 没有要搬的）
    var moveTarget by remember { mutableStateOf<File?>(null) }
    var choosingVolume by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    // 搬移进度：卡上写入很慢，玩家要看得见还在动
    var moveProgress by remember { mutableStateOf<PackMover.Progress?>(null) }
    // 删除/清缓存正在进行（遮罩挡着，不让乱点）
    var removing by remember { mutableStateOf(false) }
    // 正在搬移/删除时：返回键不做事（别把进行中的动作打断，面板也别关）
    BackHandler(enabled = moving || removing) { }
    BackHandler(enabled = !(moving || removing)) { onClose() }

    /**
     * 开始构建：源文件在就直接带过去；不在了（玩家把它挪走腾空间去了）就请他重新选一个。
     */
    fun build() {
        val src = e.source
        if (src != null) {
            lib.buildsTarget = src
            onClose()
            onGoBuilds()
        } else {
            onRequest(PickerRequest(PickerMode.FILE, GAME_FILE_EXTENSIONS, File("/storage"),
                locale.str(R.string.builds_choose_game_file),
                onPicked = { f ->
                    if (LibraryLogic.sourceMatchesTitle(f.name, e.titleId)) {
                        lib.buildsTarget = f; onClose(); onGoBuilds()
                    } else {
                        actions.onToast(locale.str(R.string.lib_source_mismatch))
                    }
                }))
        }
    }
    // 完成的移植：数据包里带着宿主写的运行日志（log.txt）
    val ported = e.pack
    // 这个游戏的包现在在哪：私有目录，还是卡上
    val packOnCard = ported?.pack?.parentFile?.let { p ->
        cardDirs.firstOrNull { it.second.absolutePath == p.absolutePath }
    }
    // 数据包里的存档（packs 的 saves/）：删包时它们会一起消失
    val packHasSaves = ported?.let {
        val s = File(it.pack, "saves")
        s.isDirectory && s.listFiles()?.isNotEmpty() == true
    } == true
    // 数据包读的是工作区里的 RomFS：工作区是游戏的一部分，清构建缓存会让它跑不起来——那种卡不摆这个按钮
    val cacheLocked = e.romfsSharedWithWorkspace

    /**
     * 破坏性动作：正在构建 / 正在运行就说清楚为什么，不能动；能动就把要删的东西算出来给确认框。
     * 清缓存不动数据包，删游戏连数据包与存档一起删——两个动作的确认框各自写清影响。
     */
    fun askRemoval(kind: Removal) {
        val blocked = lib.removalBlocked(e)
        if (blocked != null) {
            actions.onToast(locale.str(removeBlockedText(blocked)))
            return
        }
        scope.launch {
            val p = withContext(Dispatchers.IO) {
                if (kind == Removal.CACHE) lib.planCache(e) else lib.planRemoval(e)
            }
            // 卡不在时够不着的包：先说清楚（不然玩家以为全删干净了）
            val unreachable = withContext(Dispatchers.IO) { lib.unreachablePacks(e) }
            removal = RemovalRequest(kind, p, unreachable)
        }
    }

    /** 确认之后真的做 */
    fun doRemoval() {
        val req = removal ?: return
        removal = null
        removing = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                if (req.kind == Removal.CACHE) lib.clearCache(e) else lib.remove(e)
            }
            removing = false
            actions.onToast(
                when (r.outcome) {
                    GameCleanup.Outcome.DONE -> {
                        // 游戏没了，这个抽屉也就没有意义了
                        if (req.kind == Removal.DELETE) onClose()
                        val done = when (req.kind) {
                            Removal.CACHE -> locale.str(R.string.lib_remove_cache_done)
                            Removal.DELETE -> locale.str(R.string.lib_remove_done)
                        }
                        if (r.workspaceKnown) done else locale.str(
                            if (req.kind == Removal.CACHE) R.string.lib_remove_cache_workspace_unknown
                            else R.string.lib_remove_workspace_unknown)
                    }
                    GameCleanup.Outcome.FAILED -> {
                        // 卡上那份够不着：说清楚是卡上的那一份没删成（不能只说「删除失败」）
                        val onCard = r.failed.filter { it.absolutePath.startsWith("/storage/") &&
                                                       !it.absolutePath.startsWith("/storage/emulated") }
                        if (onCard.isNotEmpty())
                            locale.str(R.string.lib_remove_failed_card, onCard.joinToString { it.absolutePath })
                        else
                            locale.str(R.string.lib_remove_failed_files, r.failed.joinToString { it.name })
                    }
                    GameCleanup.Outcome.BUSY_PORTING -> locale.str(R.string.lib_remove_busy_porting)
                    GameCleanup.Outcome.BUSY_RUNNING -> locale.str(R.string.lib_remove_busy_running)
                    GameCleanup.Outcome.BUSY_MOVING -> locale.str(R.string.lib_pack_busy_moving)
                    GameCleanup.Outcome.WORKSPACE_IN_USE -> locale.str(R.string.lib_remove_cache_workspace_in_use)
                }
            )
        }
    }

    /**
     * 搬移数据包：到可移动卡上，或者搬回内部存储。
     *
     * 正在构建 / 正在运行 / 正在搬都不能搬（说清楚为什么）；没有卡时那个动作不可用。
     * 搬完之前要确认：卡上写入很慢，拔卡就把这次搬移弄坏了。
     */
    fun askMove() {
        val blocked = lib.moveBlocked(e)
        if (blocked != null) {
            actions.onToast(locale.str(removeBlockedText(blocked)))
            return
        }
        when {
            packOnCard != null -> moveTarget = lib.internalPortsDir()          // 搬回内部存储
            cardDirs.isEmpty() -> actions.onToast(locale.str(R.string.lib_pack_no_card))
            cardDirs.size == 1 -> moveTarget = cardDirs[0].second
            else -> choosingVolume = true                                       // 多张卡：先问哪一张
        }
    }

    /** 确认之后真的搬（后台线程，卡上写入很慢） */
    fun doMove(target: File) {
        moveTarget = null
        moving = true
        moveProgress = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { lib.movePack(e, target) { moveProgress = it } }
            moving = false
            moveProgress = null
            actions.onToast(
                if (r.ok) locale.str(R.string.lib_pack_moved)
                else locale.str(R.string.lib_pack_move_failed, r.failure ?: "")
            )
        }
    }

    /** 游戏运行日志：宿主把它写在了数据包里（上一轮的在 log.prev.txt），打成日志包再发出去 */
    fun shareGameLog(target: Ported) {
        if (!actions.storageOk()) { actions.onToast(locale.str(R.string.lib_log_needs_storage)); return }
        val game = e.label
        val facts = GameLog.facts(game, target.titleId, target.display, target.status, target.playerApiRevision)
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    GameLog.bundle(LogBundle.deviceText(context), game, facts, target.pack, SaveShare.packagesDir(context))
                }
            }
            r.onSuccess { bundle ->
                if (bundle == null) {
                    actions.onToast(locale.str(R.string.lib_no_runtime_log))
                } else {
                    runCatching {
                        context.startActivity(
                            Intent.createChooser(SaveShare.intent(context, bundle), locale.str(R.string.main_share_log)))
                    }.onFailure { e ->
                        actions.onToast(if (e is ActivityNotFoundException) locale.str(R.string.lib_no_receiver)
                        else locale.str(R.string.lib_share_failed, failureText(locale, e)))
                    }
                }
            }.onFailure {
                actions.onToast(locale.str(R.string.lib_log_export_failed, failureText(locale, it)))
            }
        }
    }

    Box(
        Modifier.fillMaxSize()
            // 遮罩：左边留出游戏自己的画面，往右渐渐暗下去接到面板（不是一块死黑）
            .background(
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.28f to Color(0x8C0E0E0F),
                    0.44f to Color(0xD90E0E0F),
                    1f to Color(0xF20E0E0F),
                )
            )
            .clickable { onClose() },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            Modifier
                .width(460.dp)
                .fillMaxHeight()
                .background(NxPalette.Surface)
                .clickable(enabled = false) {}
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        e.label,
                        style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(titleId, versionTag(ported), statusLabel(status), experimentalTag(ported), reportTag(ported)).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 已完成的游戏才有可管理的产物
                if (status == GameStatus.DONE) {
                    Box {
                        QuietButton(locale.str(R.string.lib_manage), onClick = { menuOpen = !menuOpen },
                            modifier = Modifier.width(112.dp),
                            trailing = { Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp)) })
                        androidx.compose.material3.DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(locale.str(R.string.main_share_log)) },
                                leadingIcon = { Icon(Icons.Filled.Description, null, Modifier.size(18.dp)) },
                                onClick = {
                                    menuOpen = false
                                    val p = ported
                                    if (p == null) actions.onToast(locale.str(R.string.lib_artifact_missing))
                                    else shareGameLog(p)
                                },
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(locale.str(if (packOnCard != null) R.string.lib_pack_move_back else R.string.lib_pack_move_to_card)) },
                                leadingIcon = { Icon(Icons.Filled.SdCard, null, Modifier.size(18.dp)) },
                                onClick = { menuOpen = false; askMove() },
                            )
                            if (!cacheLocked) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(locale.str(R.string.lib_remove_cache_action)) },
                                    leadingIcon = { Icon(Icons.Filled.CleaningServices, null, Modifier.size(18.dp)) },
                                    onClick = { menuOpen = false; askRemoval(Removal.CACHE) },
                                )
                            }
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(locale.str(R.string.lib_remove_action), color = NxPalette.Danger) },
                                leadingIcon = { Icon(Icons.Filled.Delete, null, Modifier.size(18.dp), tint = NxPalette.Danger) },
                                onClick = { menuOpen = false; askRemoval(Removal.DELETE) },
                            )
                        }
                    }
                }
            }
            // 源文件在的时候告诉玩家它在哪；不在了（挪走腾空间去了）就说明它不在了
            e.sourcePath?.let {
                Hint(it, size = 11.sp)
                if (e.source == null) Hint(locale.str(R.string.lib_source_missing), size = 11.sp)
            }

            // 内容自上而下顺排：不用弹性 spacer 把它压到底（那会在路径与「产物：」之间开一大片空白）；
            // 列本身可滚，存档多时也够得着。

            when {
                status == GameStatus.PORTING -> {
                    Hint(locale.str(R.string.lib_porting_see_builds))
                    Spacer(Modifier.height(6.dp))
                    CapsuleButton(locale.str(R.string.lib_view_builds), {
                        // 第一次构建还没有源文件的记忆：那就带着正在跑的那个构建的源文件去
                        lib.buildsTarget = e.source ?: port.gameFile.takeIf { port.running }?.let { File(it) }
                        onClose()
                        onGoBuilds()
                    }, modifier = Modifier.fillMaxWidth())
                }
                status == GameStatus.DONE -> {
                    if (ported == null) {
                        Hint(locale.str(R.string.lib_artifact_missing_state))
                    } else {
                        Hint(locale.str(R.string.builds_artifact_path, ported.pack.absolutePath), size = 11.sp)
                        Spacer(Modifier.height(8.dp))
                        RunOptions(installedDrivers, detailState.runOptions,
                            detailState.runOptionsFailure, onSelectLanguage, onSelectDriver)
                        Spacer(Modifier.height(10.dp))
                        SaveRows(ported, e.label, player, saveRepository, actions, onRequest, actions.onToast, shareFile)
                        Spacer(Modifier.height(6.dp))
                        // 别的游戏在跑时锁住这个：跑着的那个自己不受限（点它的「运行」是回到它）
                        val locked = player.running && player.titleId != ported.titleId
                        if (locked) Hint(locale.str(R.string.lib_close_current_game, player.name))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            // An incompatible player interface offers a runtime update first.
                            if (ported.needsRuntimeUpdate) {
                                CapsuleButton(locale.str(R.string.runtime_update), {
                                    actions.onRun(ported.pack, e.label)
                                }, modifier = Modifier.weight(1f), enabled = !locked)
                            } else {
                                CapsuleButton(locale.str(R.string.builds_run_action), {
                                    actions.onRun(ported.pack, e.label)
                                }, modifier = Modifier.weight(1f), enabled = !locked)
                            }
                            CapsuleButton(locale.str(R.string.lib_build_action), {
                                build()
                            }, modifier = Modifier.weight(1f))
                        }
                    }
                }
                else -> {
                    if (ported != null) {
                        Hint(locale.str(R.string.lib_needs_report))
                        CapsuleButton(locale.str(R.string.runtime_update), {
                            actions.onRun(ported.pack, e.label)
                        }, modifier = Modifier.fillMaxWidth(), enabled = !port.running && !player.running)
                        Spacer(Modifier.height(8.dp))
                    }
                    Hint(if (e.work != null && e.pack == null) locale.str(R.string.lib_residual_hint)
                         else locale.str(R.string.lib_needs_build_hint))
                    Spacer(Modifier.height(8.dp))
                    CapsuleButton(locale.str(R.string.lib_build_action), {
                        build()
                    }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // 清构建缓存：游戏照常能玩，只把能再生的东西清掉
                        // （数据包读的是工作区里的 RomFS 时不行：那种卡只留删除游戏）
                        if (!cacheLocked) {
                            QuietButton(locale.str(R.string.lib_remove_cache_action),
                                { askRemoval(Removal.CACHE) }, modifier = Modifier.weight(1f))
                        }
                        QuietButton(locale.str(R.string.lib_remove_action),
                            { askRemoval(Removal.DELETE) }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    // 搬移的确认框：写明后果（拔卡后不能玩、卸载会连同删掉它）
    moveTarget?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { moveTarget = null },
            title = { Text(locale.str(if (packOnCard != null) R.string.lib_pack_move_back_title else R.string.lib_pack_move_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        locale.str(if (packOnCard != null) R.string.lib_pack_move_back_text else R.string.lib_pack_move_text),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("· ${target.absolutePath}", style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { doMove(target) }) {
                    Text(locale.str(R.string.lib_pack_move_confirm))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { moveTarget = null }) {
                    Text(locale.str(R.string.common_cancel))
                }
            },
        )
    }

    // 多张卡：问搬到哪一张
    if (choosingVolume) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { choosingVolume = false },
            title = { Text(locale.str(R.string.lib_pack_choose_volume_title)) },
            text = {
                Column {
                    cardDirs.forEach { (name, dir) ->
                        androidx.compose.material3.TextButton(
                            onClick = { choosingVolume = false; moveTarget = dir },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(name) }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { choosingVolume = false }) {
                    Text(locale.str(R.string.common_cancel))
                }
            },
        )
    }

    removal?.let { req ->
        // 要删的东西，一样一行：数据包（带存档）、工作区、半成品包
        val items = req.plan.targets.map { t ->
            when (t.kind) {
                GameCleanup.Kind.PACK ->
                    if (packHasSaves) locale.str(R.string.lib_remove_item_pack_saves)
                    else locale.str(R.string.lib_remove_item_pack)
                GameCleanup.Kind.WORKSPACE -> locale.str(R.string.lib_remove_item_workspace)
                GameCleanup.Kind.PARTIAL -> locale.str(R.string.lib_remove_item_partial)
            }
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { removal = null },
            title = {
                Text(locale.str(if (req.kind == Removal.CACHE) R.string.lib_remove_cache_confirm_title
                else R.string.lib_remove_confirm_title))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        when {
                            req.kind == Removal.CACHE && req.plan.workspaceInUse -> locale.str(R.string.lib_remove_cache_workspace_in_use)
                            req.kind == Removal.CACHE && items.isEmpty() -> locale.str(R.string.lib_remove_cache_nothing)
                            req.kind == Removal.CACHE && status == GameStatus.DONE -> locale.str(R.string.lib_remove_cache_what_done)
                            req.kind == Removal.CACHE -> locale.str(R.string.lib_remove_cache_what_failed)
                            items.isEmpty() -> locale.str(R.string.lib_remove_what_empty)
                            status == GameStatus.DONE -> locale.str(R.string.lib_remove_what_done)
                            else -> locale.str(R.string.lib_remove_what_failed)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    items.forEach {
                        Text("· $it", style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // 卡不在：卡上那份这次删不到，先说清楚
                    if (req.unreachable.isNotEmpty()) {
                        Text(locale.str(R.string.lib_remove_unreachable_card),
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        req.unreachable.forEach {
                            Text("· ${it.absolutePath}", style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { doRemoval() }) {
                    Text(locale.str(if (req.kind == Removal.CACHE) R.string.common_clear else R.string.common_delete),
                         color = NxPalette.Danger)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { removal = null }) {
                    Text(locale.str(R.string.common_cancel))
                }
            },
        )
    }

    // 全屏遮罩：搬移/删除进行中挡住整个屏幕——这些动作要一分钟上下，不能让人乱点
    if (moving || removing) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xCC0E0E0F))
                .clickable(enabled = false) {},   // 吃掉所有点击：下面那些按钮都按不到
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (moving) {
                    val p = moveProgress
                    if (p != null) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { p.fraction },
                            modifier = Modifier.width(220.dp),
                        )
                    } else {
                        androidx.compose.material3.CircularProgressIndicator()
                    }
                    Text(locale.str(R.string.lib_pack_moving),
                         style = MaterialTheme.typography.bodyMedium)
                    // 已搬多少 / 一共多少
                    p?.let {
                        Text(locale.str(R.string.lib_pack_move_progress,
                                        sizeText(it.bytes), sizeText(it.totalBytes)),
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    androidx.compose.material3.CircularProgressIndicator()
                    Text(locale.str(R.string.lib_cleanup_working),
                         style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** 删不了的时候说清楚为什么 */
private fun removeBlockedText(outcome: GameCleanup.Outcome): Int = when (outcome) {
    GameCleanup.Outcome.BUSY_PORTING -> R.string.lib_remove_busy_porting
    GameCleanup.Outcome.BUSY_RUNNING -> R.string.lib_remove_busy_running
    GameCleanup.Outcome.BUSY_MOVING -> R.string.lib_pack_busy_moving
    else -> R.string.lib_remove_failed
}

/**
 * 运行前配置（数据包的 env.txt，下次运行生效）：游戏语言与 Vulkan 驱动，选项来自包内的清单文件。
 * 驱动有两个来源：数据包自己带的（写在 drivers.txt 里，宿主在自己旁边找它）和玩家装在 App 里的
 * （在 App 的私有目录里，按绝对路径写进 env.txt）。
 */
@Composable
private fun RunOptions(
    installed: List<Driver>,
    options: RunOptionsSnapshot?,
    failure: Throwable?,
    onSelectLanguage: (String?) -> Unit,
    onSelectDriver: (String?) -> Unit,
) {
    val systemDriver = stringResource(R.string.driver_system_default)
    if (options != null) {
        PickRow(stringResource(R.string.lib_section_language), options.languages, options.language,
            onSelect = onSelectLanguage, fallback = systemDriver)
        PickRow(stringResource(R.string.driver_section),
            options.driverChoices(installed.map { it.library.absolutePath to it.label })
                .map { (value, name) -> value to (name ?: systemDriver) }, options.driver,
            // the device's own driver is an empty value, not the absence of the row: the app keeps that choice
            // (an absent row is where the default driver goes)
            onSelect = { value -> onSelectDriver(value) }, fallback = systemDriver,
            hint = stringResource(R.string.driver_pick_hint))
    } else {
        androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
    }
    failure?.let { Hint(failureText(LocalAppLocale.current, it), size = 11.sp) }
}

/**
 * 存档（库页侧）：导出成一个可以发出去的包，导入把别人发来的包换进去。
 *
 * 活存档是应用自己的文件（宿主把它挂在 filesDir/save/<titleId>），所以这里直接动它：不写请求文件，
 * 也不用等下次启动游戏。游戏在跑的时候不动它——那会儿它正往存档里写。
 */
@Composable
private fun SaveRows(ported: Ported, gameName: String, player: PlayerState, repository: SaveRepository,
                     actions: AppActions, onRequest: (PickerRequest) -> Unit, onToast: (String) -> Unit,
                     onShareFile: (File) -> Unit) {
    val locale = LocalAppLocale.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val titleId = ported.titleId
    val packSaves = remember(ported.pack) { File(ported.pack, "saves") }
    val packagesDir = remember(repository) { repository.packagesDirectory() }

    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf("") }          // 上一次做了什么（留在面板里，不只弹一下）
    var askClose by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<File?>(null) }
    var pendingRestore by remember { mutableStateOf<File?>(null) }
    var pendingDelete by remember { mutableStateOf<File?>(null) }
    var packages by remember { mutableStateOf<List<File>>(emptyList()) }
    var backups by remember { mutableStateOf<List<File>>(emptyList()) }
    var backupSizes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }

    LaunchedEffect(reload, packagesDir, packSaves) {
        packages = repository.listPackages()
        backups = repository.listBackups(packSaves)
        backupSizes = buildMap {
            for (backup in backups) {
                val device = File(backup.parentFile, backup.name + ".device")
                put(backup.absolutePath, repository.treeSize(backup) + if (device.isDirectory) repository.treeSize(device) else 0L)
            }
        }
    }

    // 游戏在跑的时候它的存档正被写着：这时不碰它
    fun gameRunning(): Boolean = player.running || Player.playProcessAlive(context)

    fun sharePackage(file: File) = onShareFile(file)

    fun doExport() {
        if (gameRunning()) { askClose = true; return }
        if (!actions.storageOk()) { onToast(locale.str(R.string.lib_saves_need_storage)); return }
        busy = true
        scope.launch {
            val r = repository.export(titleId, gameName, packSaves)
            busy = false
            reload++
            r.onSuccess {
                note = locale.str(R.string.lib_exported_to, it.parentFile?.absolutePath)
                onToast(note)
            }.onFailure {
                if (it is com.nx.android.domain.saves.SaveOperationException &&
                    it.reason == com.nx.android.domain.saves.SaveOperationException.Reason.GAME_RUNNING) {
                    askClose = true
                    return@onFailure
                }
                note = locale.str(R.string.lib_export_failed, failureText(locale, it))
                onToast(note)
            }
        }
    }

    fun doImport(pkg: File) {
        if (gameRunning()) { askClose = true; return }
        busy = true
        scope.launch {
            val r = repository.import(titleId, pkg, packSaves)
            busy = false
            reload++
            r.onSuccess {
                note = if (it != null) locale.str(R.string.lib_imported_backup, it) else locale.str(R.string.lib_imported)
                onToast(note)
            }.onFailure {
                if (it is com.nx.android.domain.saves.SaveOperationException &&
                    it.reason == com.nx.android.domain.saves.SaveOperationException.Reason.GAME_RUNNING) {
                    askClose = true
                    return@onFailure
                }
                note = locale.str(R.string.lib_import_failed, failureText(locale, it))
                onToast(note)
            }
        }
    }

    fun doRestore(backup: File) {
        if (gameRunning()) { askClose = true; return }
        busy = true
        scope.launch {
            val r = repository.restore(titleId, backup, packSaves)
            busy = false
            reload++
            r.onSuccess {
                note = if (it != null) locale.str(R.string.lib_restored_backup, it) else locale.str(R.string.lib_restored)
                onToast(note)
            }.onFailure {
                if (it is com.nx.android.domain.saves.SaveOperationException &&
                    it.reason == com.nx.android.domain.saves.SaveOperationException.Reason.GAME_RUNNING) {
                    askClose = true
                    return@onFailure
                }
                note = locale.str(R.string.lib_restore_failed, failureText(locale, it))
                onToast(note)
            }
        }
    }

    fun shareBackup(backup: File) {
        busy = true
        scope.launch {
            val r = repository.prepareBackupShare(titleId, gameName, backup, packSaves)
            busy = false
            reload++
            r.onSuccess { sharePackage(it) }.onFailure { onToast(locale.str(R.string.lib_package_failed, failureText(locale, it))) }
        }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Text(locale.str(R.string.lib_section_saves), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        QuietButton(locale.str(R.string.lib_export_saves), onClick = { doExport() }, modifier = Modifier.weight(1f), enabled = !busy)
        QuietButton(locale.str(R.string.lib_import_saves), onClick = {
            onRequest(PickerRequest(PickerMode.FILE, setOf(SAVE_PACKAGE_EXTENSION), packagesDir, locale.str(R.string.lib_choose_save_package), onPicked = { pkg ->
                val id = repository.titleIdInPackage(pkg.name)
                if (id != null && !id.equals(titleId, ignoreCase = true)) {
                    note = locale.str(R.string.lib_other_games_save, id)
                    onToast(note)
                } else {
                    pendingImport = pkg
                }
            }))
        }, modifier = Modifier.weight(1f), enabled = !busy)
    }
    Hint(locale.str(R.string.lib_packages_where, packagesDir.absolutePath), size = 11.sp)
    if (note.isNotEmpty()) Hint(note, size = 11.sp)

    if (packages.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(locale.str(R.string.lib_section_packages), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        for (p in packages) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("${p.name}  ·  ${sizeText(p.length())}",
                     style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                     maxLines = 1, overflow = TextOverflow.Ellipsis)
                QuietButton(locale.str(R.string.common_share), onClick = { sharePackage(p) }, enabled = !busy)
                QuietButton(locale.str(R.string.common_delete), onClick = { pendingDelete = p }, enabled = !busy)
            }
        }
    }

    if (backups.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(locale.str(R.string.lib_section_backups), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        for (b in backups) {
            val dev = File(b.parentFile, b.name + ".device")
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("${b.name}  ·  ${sizeText(backupSizes[b.absolutePath] ?: 0L)}",
                     style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                     maxLines = 1, overflow = TextOverflow.Ellipsis)
                QuietButton(locale.str(R.string.common_restore), onClick = { pendingRestore = b }, enabled = !busy)
                QuietButton(locale.str(R.string.common_share), onClick = { shareBackup(b) }, enabled = !busy)
                QuietButton(locale.str(R.string.common_delete), onClick = { pendingDelete = b }, enabled = !busy)
            }
        }
    }

    // 游戏还在跑：存档正被它写着，先让它退出
    if (askClose) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askClose = false },
            title = { Text(locale.str(R.string.lib_game_running)) },
            text = { Text(locale.str(R.string.lib_game_running_hint)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    askClose = false
                    actions.onStopGame()
                }) { Text(locale.str(R.string.drawer_exit), color = NxPalette.Building) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { askClose = false }) { Text(locale.str(R.string.common_cancel)) } },
        )
    }

    pendingImport?.let { pkg ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(locale.str(R.string.lib_import_confirm)) },
            text = { Text(locale.str(R.string.lib_import_confirm_body, pkg.name)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    pendingImport = null
                    doImport(pkg)
                }) { Text(locale.str(R.string.common_import), color = NxPalette.Building) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { pendingImport = null }) { Text(locale.str(R.string.common_cancel)) } },
        )
    }

    pendingRestore?.let { backup ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text(locale.str(R.string.lib_restore_confirm)) },
            text = { Text(locale.str(R.string.lib_restore_confirm_body, backup.name)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    pendingRestore = null
                    doRestore(backup)
                }) { Text(locale.str(R.string.common_restore), color = NxPalette.Building) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { pendingRestore = null }) { Text(locale.str(R.string.common_cancel)) } },
        )
    }

    pendingDelete?.let { file ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(locale.str(R.string.lib_delete_confirm)) },
            text = { Text(locale.str(R.string.lib_delete_confirm_body, file.name)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        val gone = repository.delete(file, packSaves)
                        reload++
                        onToast(if (gone) locale.str(R.string.lib_deleted, file.name) else locale.str(R.string.lib_delete_failed))
                    }
                }) { Text(locale.str(R.string.common_delete), color = NxPalette.Danger) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { pendingDelete = null }) { Text(locale.str(R.string.common_cancel)) } },
        )
    }
}
