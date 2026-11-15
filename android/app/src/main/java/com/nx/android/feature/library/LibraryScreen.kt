package com.nx.android.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nx.android.R
import com.nx.android.app.AppActions
import com.nx.android.appstate.LibraryState
import com.nx.android.appstate.PlayerState
import com.nx.android.appstate.PortState
import com.nx.android.core.ui.LocalAppLocale
import com.nx.android.core.ui.PreviewSurface
import com.nx.android.core.ui.nav.NavHint
import com.nx.android.core.ui.nav.NavHintBar
import com.nx.android.core.ui.nav.NavKey
import com.nx.android.core.ui.nav.OnNavKey
import com.nx.android.input.GamepadScan
import com.nx.android.domain.GAME_FILE_EXTENSIONS
import com.nx.android.data.library.GameProfile
import com.nx.android.domain.GameEntry
import com.nx.android.domain.GameStatus
import com.nx.android.domain.LibraryLogic
import com.nx.android.feature.filepicker.PickerMode
import com.nx.android.feature.filepicker.PickerRequest
import kotlinx.coroutines.delay
import java.io.File

/**
 * 游戏库：主机式行视图——横向瓦片（88/128dp），选中的放大，选中游戏铺满背景并在左下给出详情面板（大标题、
 * 状态、▶ 构建、配置与安装的圆钮）。移植配置从右侧全高滑入。
 *
 * 这一层是有状态的（它读库、读移植、读正在玩的游戏）；画的那部分在 [LibraryContent] 里，是无状态的，
 * 所以预览可以单独看它。
 */
@Composable
fun LibraryScreen(lib: LibraryState, port: PortState, player: PlayerState, actions: AppActions,
                  onGoBuilds: () -> Unit, onRequest: (PickerRequest) -> Unit, uiState: LibraryUiState? = null,
                  viewModel: LibraryViewModel? = null, navActive: Boolean = false) {

    // a port ends: the cards' statuses are re-read
    LaunchedEffect(port.phase) { viewModel?.refresh() ?: lib.rescan() }

    // 构建中：工作区是构建开始之后才有的，过一会儿再看一次，那张「构建中」的卡才会出现
    LaunchedEffect(port.running) {
        while (port.running) {
            viewModel?.refresh() ?: lib.rescan()
            delay(3000)
        }
    }

    val visible = uiState?.games ?: lib.visible()
    val heroIndex = uiState?.heroIndex ?: lib.heroIndex
    LaunchedEffect(visible.size) { viewModel?.selectHero(heroIndex, visible.size) ?: lib.selectHero(heroIndex, visible.size) }

    val locale = LocalAppLocale.current

    // 手柄接着吗：接上了才在页面底部给按键提示（插上就淡入，拔掉就淡出）
    val padDevices by GamepadScan.devices.collectAsStateWithLifecycle()
    val padConnected = padDevices.isNotEmpty()

    /**
     * 开始构建：源文件在就直接带过去；不在了（玩家把它挪走腾空间去了）就请他重新选一个。
     */
    fun build(e: GameEntry) {
        val src = e.source
        if (src != null) {
            viewModel?.setBuildTarget(src) ?: run { lib.buildsTarget = src }
            onGoBuilds()
        } else {
            onRequest(PickerRequest(PickerMode.FILE, GAME_FILE_EXTENSIONS, File("/storage"),
                locale.str(R.string.builds_choose_game_file),
                onPicked = { f ->
                    if (LibraryLogic.sourceMatchesTitle(f.name, e.titleId)) {
                        viewModel?.setBuildTarget(f) ?: run { lib.buildsTarget = f }; onGoBuilds()
                    } else {
                        actions.onToast(locale.str(R.string.lib_source_mismatch))
                    }
                }))
        }
    }

    val libraryActions = LibraryActions(
        onSelect = { i ->
            if (i == heroIndex) viewModel?.selectGame(visible.getOrNull(i)) ?: run { lib.selected = visible.getOrNull(i) }
            else viewModel?.selectHero(i, visible.size) ?: lib.selectHero(i, visible.size)
        },
        onBuild = { e ->
            val pack = e.pack
            if (pack?.needsRuntimeUpdate == true) actions.onRun(pack.pack, e.label) else build(e)
        },
        onRun = { e -> e.pack?.let { actions.onRun(it.pack, e.label) } },
        onViewBuild = { e ->
            // 正在构建：去构建页看进度（第一次构建还没有源文件的记忆，就带着正在跑的那个）
            val target = e.source ?: port.gameFile.takeIf { port.running }?.let { File(it) }
            viewModel?.setBuildTarget(target) ?: run { lib.buildsTarget = target }
            onGoBuilds()
        },
        onConfigure = { e -> viewModel?.selectGame(e) ?: run { lib.selected = e } },
    )

    /**
     * 移动选中的游戏：库里没有游戏时不动（不越界，也不崩）。
     */
    fun moveHero(delta: Int) {
        if (visible.isEmpty()) return
        val next = (heroIndex + delta).coerceIn(0, visible.size - 1)
        viewModel?.selectHero(next, visible.size) ?: lib.selectHero(next, visible.size)
    }

    /** 这个游戏的状态 / 它是不是旧版宿主编的包 / 别的游戏在玩时它能不能跑 */
    fun statusOf(e: GameEntry): GameStatus = lib.statusOf(e)
    fun staleOf(e: GameEntry): Boolean = e.pack?.needsRuntimeUpdate == true
    fun lockedOf(e: GameEntry): Boolean {
        val ported = e.pack
        return statusOf(e) == GameStatus.DONE && player.running && player.titleId != (ported?.titleId ?: "")
    }

    /**
     * 主动作：把选中的游戏跑起来 / 去构建 / 看构建的进度。
     *
     * 与左下角那个主按钮同一条路（`mainActionOf`）：别的游戏在玩时主按钮是置灰的，这里也不做事。
     */
    fun runMainAction() {
        val entry = visible.getOrNull(heroIndex) ?: return
        if (lockedOf(entry)) return
        when (mainActionOf(statusOf(entry), staleOf(entry))) {
            MainAction.VIEW_BUILD -> libraryActions.onViewBuild(entry)
            MainAction.RUN -> libraryActions.onRun(entry)
            MainAction.BUILD -> libraryActions.onBuild(entry)
        }
    }

    /**
     * 手柄导航：左右移动选中的游戏，确认键打开它的配置面板。
     *
     * 走的是库里已有的那套：移动就是改 heroIndex（瓦片行的滚动与放大都跟着它），
     * 确认就是主按钮那个动作，Start 就是「点已选中的那个瓦片」——手柄与手指走同一条路。
     */
    OnNavKey(enabled = navActive) { key ->
        when (key) {
            NavKey.Left -> moveHero(-1)
            NavKey.Right -> moveHero(+1)
            NavKey.Confirm -> runMainAction()
            NavKey.Menu -> libraryActions.onSelect(heroIndex)
            else -> {}
        }
    }

    LibraryContent(
        entries = visible,
        heroIndex = heroIndex,
        statusOf = { statusOf(it) },
        staleOf = { staleOf(it) },
        profileOf = { lib.profileOf(it) },
        portProgress = port.progress,
        // 单游戏：别的游戏在玩时，那个游戏的「▶ 运行」置灰并说明
        lockedOf = { lockedOf(it) },
        runningGameName = player.name,
        actions = libraryActions,
        navActive = navActive,
        padConnected = padConnected,
    )

    // 配置面板不在这里画：它要盖住顶栏，所以由页面的根（MainScreen）在最上层画（见 ui/App.kt）
}

@Composable
fun LibraryRoute(viewModel: LibraryViewModel, port: PortState, player: PlayerState,
                 actions: AppActions,
                 onGoBuilds: () -> Unit, onRequest: (PickerRequest) -> Unit,
                 navActive: Boolean = false) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LibraryScreen(viewModel.library, port, player, actions, onGoBuilds, onRequest, uiState, viewModel,
        navActive = navActive)
}

/** 页面上的动作：选一个游戏、构建、运行、看正在跑的构建、开配置 */
internal data class LibraryActions(
    val onSelect: (Int) -> Unit,
    val onBuild: (GameEntry) -> Unit,
    val onRun: (GameEntry) -> Unit,
    val onViewBuild: (GameEntry) -> Unit,
    val onConfigure: (GameEntry) -> Unit,
)

/**
 * 库页画的那一层：空态、瓦片行、左下详情。
 *
 * 无状态——只有列表、选中项、状态与动作；状态从哪来（库里、还是在构建）与动作要做什么（跳构建页、
 * 跑起来），都由外面给。所以预览可以拿假数据单独看它。
 */
@Composable
internal fun LibraryContent(
    entries: List<GameEntry>,
    heroIndex: Int,
    statusOf: (GameEntry) -> GameStatus,
    staleOf: (GameEntry) -> Boolean = { false },
    profileOf: (String) -> GameProfile? = { null },
    portProgress: Float,
    lockedOf: (GameEntry) -> Boolean,
    runningGameName: String,
    actions: LibraryActions,
    navActive: Boolean = false,
    padConnected: Boolean = false,
) {
    val sel = entries.getOrNull(heroIndex)
    // 提示条上确认键那一项写的文案：与左下角主按钮完全一样（同一个 mainActionOf）
    val mainLabel = sel?.let { e ->
        val stale = staleOf(e)
        mainActionLabel(mainActionOf(statusOf(e), stale), stale)
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        if (entries.isEmpty()) {
            // 空态：架子上的几个空位、一句提示、支持的格式。没有按钮——加游戏的路只有构建页那一条
            // 竖着放不下（横屏）时也能滚
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyShelf()
                    Spacer(Modifier.height(26.dp))
                    Text(
                        stringResource(R.string.lib_empty_title),
                        style = MaterialTheme.typography.displaySmall,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.lib_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 340.dp),
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (f in EMPTY_FORMATS) FormatChip(f)
                    }
                }
            }
            return@Column
        }
        Spacer(Modifier.height(10.dp))
        HeroRow(entries, heroIndex, statusOf, portProgress) { i -> actions.onSelect(i) }
        Spacer(Modifier.weight(1f))
        sel?.let { entry ->
            HeroDetails(
                entry = entry,
                profile = profileOf(entry.titleId),
                status = statusOf(entry),
                stale = staleOf(entry),
                locked = lockedOf(entry),
                runningGameName = runningGameName,
                portProgress = portProgress,
                onBuild = { actions.onBuild(entry) },
                onRun = { actions.onRun(entry) },
                onViewBuild = { actions.onViewBuild(entry) },
                onConfigure = { actions.onConfigure(entry) },
            )
        }
        Spacer(Modifier.height(18.dp))
    }
    // 按键提示：接了手柄才浮出来，靠右下角（不占地方：插上手柄时页面别的东西也不挪）
    AnimatedVisibility(
        visible = navActive && padConnected && entries.isNotEmpty(),
        modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
        enter = fadeIn(), exit = fadeOut(),
    ) {
        NavHintBar(
            listOfNotNull(
                NavHint("←→", stringResource(R.string.ui_nav_move)),
                // 确认键那一项写的与主按钮一样：运行 / 构建 / 查看构建 / 更新运行库
                mainLabel?.let { NavHint("A", it) },
                NavHint("Start", stringResource(R.string.ui_nav_details)),
                NavHint("L/R", stringResource(R.string.ui_nav_tabs)),
            )
        )
    }
    }
}

/** 预览：有游戏的库页（选中第二个：构建中的那个） */
@Preview(name = "Library with games", widthDp = 1280, heightDp = 800, showBackground = true)
@Composable
private fun LibraryContentPreview() {
    PreviewSurface {
        LibraryContent(
            entries = previewGames.map { it.entry },
            heroIndex = 1,
            statusOf = previewStatus,
            portProgress = 0.42f,
            lockedOf = { false },
            runningGameName = "",
            actions = previewActions(),
        )
    }
}

/** 预览：空库（架子上几个空位） */
@Preview(name = "Library empty", widthDp = 1280, heightDp = 800, showBackground = true)
@Composable
private fun LibraryEmptyPreview() {
    PreviewSurface {
        LibraryContent(
            entries = emptyList(),
            heroIndex = 0,
            statusOf = previewStatus,
            portProgress = 0f,
            lockedOf = { false },
            runningGameName = "",
            actions = previewActions(),
        )
    }
}
