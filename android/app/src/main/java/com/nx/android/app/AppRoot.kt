package com.nx.android.app

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nx.android.R
import com.nx.android.input.GamepadScan
import com.nx.android.input.ui.PadCenterController
import com.nx.android.input.PadAssignment
import com.nx.android.input.ui.PadCenterScreen
import com.nx.android.appstate.PortState
import com.nx.android.appstate.PlayerState
import com.nx.android.appstate.OnboardingState
import com.nx.android.data.AppLocale
import com.nx.android.data.PrefsStore
import com.nx.android.data.PadPlayers
import com.nx.android.data.StorageVolumes
import com.nx.android.domain.EnvCheck
import com.nx.android.domain.GameIdentity
import com.nx.android.domain.GAME_FILE_EXTENSIONS
import com.nx.android.domain.GameConfig
import com.nx.android.feature.builds.BuildsRoute
import com.nx.android.feature.builds.BuildsViewModel
import com.nx.android.core.ui.nav.NavKey
import com.nx.android.core.ui.nav.OnNavKey
import com.nx.android.core.ui.components.CapsuleButton
import com.nx.android.core.ui.elapsedSecs
import com.nx.android.core.ui.ProvideAppLocale
import com.nx.android.feature.filepicker.FilePickerScreen
import com.nx.android.feature.logs.LogOverlay
import com.nx.android.feature.filepicker.PickerMode
import com.nx.android.feature.filepicker.PickerRequest
import com.nx.android.core.ui.components.QuietButton
import com.nx.android.feature.environment.EnvChecklistDialog
import com.nx.android.feature.environment.EnvChip
import com.nx.android.domain.GameEntry
import com.nx.android.feature.gamedetail.GameDetailRoute
import com.nx.android.feature.library.HeroBackground
import com.nx.android.feature.library.LibraryRoute
import com.nx.android.feature.library.LibraryViewModel
import com.nx.android.feature.storage.StorageViewModel
import com.nx.android.feature.onboarding.OnboardingViewModel
import com.nx.android.feature.updates.UpdateViewModel
import com.nx.android.feature.environment.EnvironmentCheckViewModel
import com.nx.android.domain.drivers.DriverRepository
import com.nx.android.domain.environment.EnvironmentSetup
import com.nx.android.appstate.LibraryState
import com.nx.android.feature.onboarding.OnboardingScreen
import com.nx.android.feature.drivers.DriverRoute
import com.nx.android.domain.drivers.Driver
import com.nx.android.feature.settings.SettingsScreen
import com.nx.android.feature.settings.SettingsSection
import com.nx.android.core.ui.theme.GlassSheet
import com.nx.android.core.ui.theme.NxPalette
import com.nx.android.core.ui.theme.NxTheme
import com.nx.android.feature.updates.UpdatePopup
import com.nx.android.feature.updates.openRepo
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The main screen's pages */
enum class Tab(@StringRes val label: Int, val icon: ImageVector) {
    LIBRARY(R.string.app_tab_library, Icons.Filled.SportsEsports),
    BUILDS(R.string.app_tab_builds, Icons.Filled.Inventory2),
}

/**
 * 手柄导航现在归谁：谁在最上面，谁拿走手柄。
 *
 * 判断只在这一处做（页面的根），页面自己只管「我收到这个动作时做什么」——
 * 这样不会出现两个页面同时响应，也不会要动一处就想起另一处。
 */
private enum class NavHost { NONE, LIBRARY, PANEL, SETTINGS }

/** The screens' way back to the activity's own privileges: what they may ask for, nothing else */
data class AppActions(
    val onAskStorage: () -> Unit,
    val onAskBattery: () -> Unit,
    val storageOk: () -> Boolean,
    val onSetKeys: (String) -> Unit,
    val onStartPort: (file: File, cfg: GameConfig) -> Unit,
    val onCancelPort: () -> Unit,
    val onRun: (pack: File, name: String) -> Unit,
    val onStopGame: () -> Unit,
    val onToast: (String) -> Unit,
    val version: () -> String,
    val keys: () -> String,
    val onShareLog: () -> Unit,
    val onClearLog: () -> Unit,
    val readLog: () -> String,
    val onShareBuildLog: (String) -> Unit,
    val readBuildLog: (String, Long, Int) -> String,
    val buildLogLength: (String) -> Long,
    val isBuildActive: (String) -> Boolean,
    val emptyBuildLogMessage: (String) -> String,
    val titleBuildLog: (String) -> String,
    val onUpdateRuntime: (File, String) -> Unit = { _, _ -> },
    // the firmware: one for the machine (imported from a zip into the app's own folder, optional)
    val firmware: () -> String = { "" },
    val onImportFirmware: (File) -> Unit = {},
    val onClearFirmware: () -> Unit = {},
)

/** The app's root: the guided setup until it is done, the main shell after */
/**
 * The app's root: the guided setup until it is done, the main shell after.
 *
 * The pages that cover the whole screen (the file picker, the driver page, the log) are composed here, above
 * the pages themselves: a page's own content sits in a scrolling column, and a cover inside one never fills
 * the screen — it is laid out in the column's flow instead.
 */
@Composable
fun AppRoot(
    prefs: PrefsStore,
    locale: AppLocale,
    port: PortState,
    library: LibraryState,
    player: PlayerState,
    onboarding: com.nx.android.appstate.OnboardingState,
    drivers: DriverRepository,
    environmentSetup: EnvironmentSetup,
    actions: AppActions,
    startTab: Int,
    runtime: com.nx.android.appstate.RuntimeUpdateState,
    launchBuildId: String,
    padController: PadCenterController,
    padCenterOpen: Boolean,
    onPadCenter: (Boolean) -> Unit,
) {
    NxTheme {
        // 界面语言：整棵树读同一份资源，玩家在设置里换语言时这里跟着换
        ProvideAppLocale(locale) {
        var pickerReq by remember { mutableStateOf<PickerRequest?>(null) }
        var driversOpen by remember { mutableStateOf(false) }
    var logOpen by remember { mutableStateOf(false) }
    var buildLogId by remember { mutableStateOf("") }
        var envCheckOpen by remember { mutableStateOf(false) }
        var ossNotice by rememberSaveable { mutableStateOf(false) }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val onboardingViewModel: OnboardingViewModel = hiltViewModel()
        val onboardingUiState by onboardingViewModel.uiState.collectAsStateWithLifecycle()
        val environmentCheckViewModel: EnvironmentCheckViewModel = hiltViewModel()
        // 开源声明：引导走完的那一刻弹一次，老用户（早已完成引导）升级后第一次启动也弹；记在 prefs 里，只此一次
        LaunchedEffect(onboardingUiState.active) {
            if (!onboardingUiState.active
                && prefs.bool(PrefsStore.OB_COMPLETED)
                && !prefs.bool(PrefsStore.OSS_NOTICE_SHOWN)) ossNotice = true
        }
        Box(Modifier.fillMaxSize().background(NxPalette.Background)) {
            if (onboardingUiState.active) {
                OnboardingScreen(onboardingViewModel, actions) { pickerReq = it }
            } else {
                // 这一层之外还盖着东西吗（文件选择器、驱动页、日志、清单、开源声明）：
                // 盖着的时候手柄的键不落到里面的页面上
                val outerOverlayOpen = pickerReq != null || driversOpen || logOpen ||
                    buildLogId.isNotEmpty() || envCheckOpen || ossNotice
                MainScreen(prefs, locale, port, library, player, onboarding, drivers, environmentSetup, actions, startTab, runtime, launchBuildId,
                    padController = padController,
                    padCenterOpen = padCenterOpen, onPadCenter = onPadCenter,
                    outerOverlayOpen = outerOverlayOpen,
                    onRequest = { pickerReq = it },
                    onDrivers = { driversOpen = true },
                    onLog = { id -> buildLogId = id },
                    onDiagnosticLog = { logOpen = true },
                    onEnvCheck = { envCheckOpen = true })
            }
            val volumes = remember(ctx, locale.mode) { StorageVolumes.list(ctx, locale) }
            pickerReq?.let { req ->
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))) {
                    if (!actions.storageOk()) {
                        // 内置文件管理器读不了没有全文件访问的存储：给授权入口
                        Column(
                            Modifier.fillMaxSize().padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                        ) {
                            Spacer(Modifier.weight(1f))
                            Text(stringResource(R.string.app_storage_permission_needed),
                                 style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(R.string.app_storage_permission_picker),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.weight(1f))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Spacer(Modifier.weight(1f))
                                QuietButton(stringResource(R.string.common_cancel), onClick = { pickerReq = null })
                                CapsuleButton(stringResource(R.string.app_grant_permission), onClick = {
                                    pickerReq = null
                                    actions.onAskStorage()
                                })
                            }
                        }
                    } else {
                        FilePickerScreen(
                            req = req,
                            volumes = volumes,
                            onPicked = { f ->
                                pickerReq = null
                                req.onPicked(f)
                            },
                            onCancel = { pickerReq = null },
                        )
                    }
                }
            }
            // 驱动页：盖住整屏，在页面之外
            if (driversOpen) {
                DriverRoute(onClose = { driversOpen = false })
            }
            // 环境检查清单：顶栏胶囊点开的，盖住整屏
            if (envCheckOpen) {
                BackHandler { envCheckOpen = false }
                EnvChecklistDialog(
                    viewModel = environmentCheckViewModel,
                    onboarding = onboardingViewModel,
                    portRunning = port.running,
                    onPrepareEnv = { onboardingViewModel.unpack() },
                    onPickKeys = {
                        envCheckOpen = false
                        pickerReq = PickerRequest(PickerMode.FILE, emptySet(), null,
                            locale.str(R.string.onb_choose_prod_keys),
                            onPicked = { f -> actions.onSetKeys(f.path) })
                    },
                    firmware = actions.firmware(),
                    onImportFirmware = {
                        envCheckOpen = false
                        pickerReq = PickerRequest(PickerMode.FILE, setOf("zip"), null,
                            locale.str(R.string.fw_pick),
                            onPicked = { f -> actions.onImportFirmware(f) })
                    },
                    onRunOnboarding = {
                        // the checklist first: it is drawn over the setup and would hide it
                        envCheckOpen = false
                        onboarding.open()
                    },
                    onDismiss = { envCheckOpen = false },
                )
            }
            // 日志浮层：同样盖住整屏
            if (logOpen) {
                BackHandler { logOpen = false }
                LogOverlay(onClose = { logOpen = false }, text = remember { actions.readLog() })
            }
            if (buildLogId.isNotEmpty()) {
                BackHandler { buildLogId = "" }
                data class InitialBuildLog(val text: String, val offset: Long, val length: Long)
                val page by produceState(initialValue = InitialBuildLog("", 0, 0), buildLogId) {
                    val length = withContext(Dispatchers.IO) { actions.buildLogLength(buildLogId) }
                    val offset = (length - 64 * 1024).coerceAtLeast(0)
                    val text = withContext(Dispatchers.IO) { actions.readBuildLog(buildLogId, offset, 64 * 1024) }
                    value = InitialBuildLog(text, offset, length)
                }
                LogOverlay(
                    onClose = { buildLogId = "" }, text = page.text,
                    title = actions.titleBuildLog(buildLogId), buildId = buildLogId,
                    initialOffset = page.offset, initialLength = page.length,
                    active = actions.isBuildActive(buildLogId),
                    emptyText = actions.emptyBuildLogMessage(buildLogId),
                    readRange = { offset, length -> actions.readBuildLog(buildLogId, offset, length) },
                    latestLength = { actions.buildLogLength(buildLogId) },
                )
            }
            // 开源声明：盖在最后面，玩家先看到它
            if (ossNotice) {
                OpenSourceNoticeDialog(
                    onClose = {
                        ossNotice = false
                        prefs.put(PrefsStore.OSS_NOTICE_SHOWN, true)
                    },
                    onRepo = {
                        openRepo(ctx)
                        ossNotice = false
                        prefs.put(PrefsStore.OSS_NOTICE_SHOWN, true)
                    },
                )
            }
        }
        }
    }
}

/**
 * 开源声明：这个软件是免费开源的，禁止盗卖；花钱买到的请联系卖家退款。
 *
 * 它只出现一次：记在 prefs 里，关掉就不再出现。
 */
@Composable
private fun OpenSourceNoticeDialog(onClose: () -> Unit, onRepo: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.oss_notice_title), fontWeight = FontWeight.Bold) },
        text = { Text(stringResource(R.string.oss_notice_body)) },
        confirmButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.oss_notice_ok), color = NxPalette.Building) }
        },
        dismissButton = {
            TextButton(onClick = onRepo) { Text(stringResource(R.string.oss_notice_repo)) }
        },
    )
}

@Composable
fun MainScreen(prefs: PrefsStore, locale: AppLocale, port: PortState, library: LibraryState,
               player: PlayerState, onboarding: OnboardingState, drivers: DriverRepository,
               environmentSetup: EnvironmentSetup, actions: AppActions, startTab: Int,
               runtime: com.nx.android.appstate.RuntimeUpdateState,
               launchBuildId: String,
               padController: PadCenterController,
               padCenterOpen: Boolean,
               onPadCenter: (Boolean) -> Unit,
               outerOverlayOpen: Boolean = false,
               onRequest: (PickerRequest) -> Unit,
               onDrivers: () -> Unit,
               onLog: (String) -> Unit,
               onDiagnosticLog: () -> Unit,
               onEnvCheck: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(startTab) }
    // 设置是一整页，盖住顶栏与下面的页面：从顶栏的设置图标进来，关掉才回到底下的页面
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsSection by remember { mutableStateOf(SettingsSection.ENVIRONMENT) }
    // 手柄中心：从顶栏的手柄按钮进来，盖住整屏（跟设置页一样）。开着不开着是活动那边
    // 的一份（分发层也要知道：手柄的键该进中心还是进页面）
    val padDevices by GamepadScan.devices.collectAsStateWithLifecycle()
    val padCtx = androidx.compose.ui.platform.LocalContext.current
    val lib = library
    // 手柄导航归谁：谁在最上面，谁拿走手柄。
    // 手柄中心开着的时候，手柄的键进中心（活动层就不发了），外面还盖着东西的时候也一样。
    val navHost = when {
        outerOverlayOpen || padCenterOpen -> NavHost.NONE
        settingsOpen -> NavHost.SETTINGS
        lib.selected != null -> NavHost.PANEL
        else -> NavHost.LIBRARY
    }

    // 肩键切页签：页签的状态在这一层，所以切页也在这里做（浮层开着时不切）。
    OnNavKey(enabled = navHost == NavHost.LIBRARY) { key ->
        when (key) {
            NavKey.TabLeft -> tab = Tab.LIBRARY.ordinal
            NavKey.TabRight -> tab = Tab.BUILDS.ordinal
            else -> {}
        }
    }
    val libraryViewModel: LibraryViewModel = hiltViewModel()
    val buildsViewModel: BuildsViewModel = hiltViewModel()
    LaunchedEffect(launchBuildId) {
        if (launchBuildId.isNotBlank()) {
            tab = Tab.BUILDS.ordinal
            buildsViewModel.openBuild(launchBuildId)
        }
    }
    val storageViewModel: StorageViewModel = hiltViewModel()
    val updateViewModel: UpdateViewModel = hiltViewModel()
    val environmentCheckViewModel: EnvironmentCheckViewModel = hiltViewModel()
    val environmentCheckUiState by environmentCheckViewModel.uiState.collectAsStateWithLifecycle()
    val installedDrivers by drivers.drivers.collectAsStateWithLifecycle()

    com.nx.android.feature.runtime.RuntimeUpdateDialog(runtime,
        onRetry = { actions.onUpdateRuntime(File(runtime.pack), runtime.name) },
        onFinished = { actions.onToast(locale.str(R.string.runtime_updated)) },
        onUseCurrentProfile = {
            // 玩家选择用当前档案继续：下一次更新带上这个开关（清单里的指纹会换成当前的）
            runtime.useCurrentProfile()
            actions.onUpdateRuntime(File(runtime.pack), runtime.name)
        })

    // 选中游戏的背景铺满整块屏幕：顶栏透明画在图上，三个 tab 共用这层背景
    Box(Modifier.fillMaxSize()) {
        HeroBackground(lib.visible().getOrNull(lib.heroIndex))
        Column(Modifier.fillMaxSize()) {
        TopBar(
            selected = tab,
            onSelect = { tab = it.ordinal },
            onRefresh = { lib.rescan() },
            onSettings = { settingsOpen = true },
            padCount = padDevices.size,
            onOpenPadCenter = { onPadCenter(true) },
            chip = environmentCheckUiState.chip,
            onChip = onEnvCheck,
        )
        // 有新版本时的弹框：冷启动查到了新版本才出现
        UpdatePopup(updateViewModel)
        Box(Modifier.weight(1f)) {
            when (Tab.entries[tab]) {
                Tab.LIBRARY -> LibraryRoute(libraryViewModel, port, player, actions,
                    onGoBuilds = { buildsViewModel.setTarget(lib.buildsTarget); tab = Tab.BUILDS.ordinal }, onRequest = onRequest,
                    navActive = navHost == NavHost.LIBRARY)
                Tab.BUILDS -> BuildsRoute(buildsViewModel, prefs.keys, environmentSetup.ready, actions, onRequest, onLog)
            }
        }
        if (port.running || buildsViewModel.gameInfo.reading) {
            ActivityBar(port, reading = buildsViewModel.gameInfo.reading, openBuilds = { tab = Tab.BUILDS.ordinal })
        }
        }
        // 配置面板画在最上层：它的遮罩要盖住顶栏与整块页面
        val panelFile = remember { mutableStateOf<GameEntry?>(null) }
        LaunchedEffect(lib.selected) { if (lib.selected != null) panelFile.value = lib.selected }
        AnimatedVisibility(
            visible = lib.selected != null,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            panelFile.value?.let { f ->
                GameDetailRoute(lib, port, player, f, installedDrivers, actions,
                    onGoBuilds = { buildsViewModel.setTarget(lib.buildsTarget); tab = Tab.BUILDS.ordinal }, onRequest = onRequest,
                    onClose = { lib.selected = null },
                    navActive = navHost == NavHost.PANEL)
            }
        }
        // 手柄中心：盖住整屏（顶栏也在下面），关掉才回到底下的页面
        if (padCenterOpen) {
            PadCenterScreen(
                controller = padController,
                onClose = { onPadCenter(false) },
                // 谁玩谁：指派是每个游戏一份，这里看的是**正在跑**的那个游戏的（没在跑就是默认：
                // 没被指派走的手柄都算玩家 1）
                assignment = remember(player.running, player.titleId) {
                    if (player.running && player.titleId.isNotEmpty())
                        PadPlayers(padCtx, player.titleId).load()
                    else PadAssignment()
                },
            )
        }
        // 设置页：盖住整屏（顶栏也在下面），关掉才回到底下的页面
        if (settingsOpen) {
            SettingsScreen(onboarding, installedDrivers, drivers, updateViewModel,
                storageViewModel, actions,
                onRequest, onDrivers,
                section = settingsSection, onSelectSection = { settingsSection = it },
                onDiagnosticLog = onDiagnosticLog,
                onClose = { settingsOpen = false },
                navActive = navHost == NavHost.SETTINGS)
        }
    }
}

/** 一行式顶栏：品牌 + 两个纯文字 tab（选中亮/未选灰）+ 环境状态胶囊 + 右侧图标与时钟（capyps5 的骨架，我们的颜色） */
@Composable
private fun TopBar(selected: Int, onSelect: (Tab) -> Unit, onRefresh: () -> Unit,
                   onSettings: () -> Unit,
                   padCount: Int, onOpenPadCenter: () -> Unit,
                   chip: EnvCheck.Chip, onChip: () -> Unit) {
    val time by produceState(initialValue = "") {
        val format = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (true) {
            value = format.format(Date())
            delay(10_000)
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
    ) {
        Image(painterResource(R.drawable.ic_launcher_mark), null, Modifier.size(32.dp))
        // 品牌：名字只有 app_name 一处，末位的标记（X）单独用品牌色
        val brand = stringResource(R.string.app_name)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onBackground)) { append(brand.dropLast(1)) }
                withStyle(SpanStyle(color = NxPalette.Brand)) { append(brand.takeLast(1)) }
            },
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(start = 8.dp, end = 30.dp),
        )
        Tab.entries.forEachIndexed { i, t ->
            Text(
                stringResource(t.label),
                style = MaterialTheme.typography.titleLarge,
                color = if (i == selected) MaterialTheme.colorScheme.onBackground else NxPalette.Unbuilt,
                modifier = Modifier.clickable { onSelect(t) }.padding(end = 26.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        TopIcon(Icons.Filled.Refresh, stringResource(R.string.app_refresh_library), onRefresh)
        Spacer(Modifier.width(6.dp))
        // 手柄中心：顶栏的入口（接着几只手柄就亮几颗）
        PadIcon(padCount, onOpenPadCenter)
        Spacer(Modifier.width(6.dp))
        // 设置：它是一整页，从这个图标进来
        TopIcon(Icons.Filled.Settings, stringResource(R.string.app_tab_settings), onSettings)
        Spacer(Modifier.width(6.dp))
        EnvChip(chip, onChip)
        Text(time, style = MaterialTheme.typography.titleMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
    }
}

/** 手柄的入口：接着手柄时图标是亮的，旁边一个数字是接了几只。 */
@Composable
private fun PadIcon(padCount: Int, onClick: () -> Unit) {
    val description = stringResource(R.string.app_pad_center_connected, padCount)
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(
                Icons.Filled.SportsEsports,
                null,
                Modifier.size(22.dp),
                tint = if (padCount > 0) MaterialTheme.colorScheme.onBackground else NxPalette.Unbuilt,
            )
            if (padCount > 0) {
                Text(
                    "$padCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TopIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onBackground)
    }
}

/** The port's progress, always in sight at the bottom while it runs; tapping opens the builds page */
@Composable
private fun ActivityBar(port: PortState, reading: Boolean, openBuilds: () -> Unit) {
    val secs = elapsedSecs(port.startedAt)
    GlassSheet(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(Modifier.fillMaxWidth().clickable { openBuilds() }.padding(horizontal = 14.dp, vertical = 10.dp),
               verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = NxPalette.Building,
                )
                if (port.running) {
                    Text(
                        GameIdentity.displayName(File(port.gameFile)),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f), maxLines = 1,
                    )
                    Text(
                        stringResource(R.string.app_stage_and_time,
                            port.stageOf, port.stages, secs / 60, secs % 60),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // 正在读选中的游戏（移植器在看它是什么）：一句话，玩家知道还在忙
                    Text(
                        stringResource(R.string.builds_info_reading),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f), maxLines = 1,
                    )
                }
            }
            if (port.running && port.stageStep.isNotEmpty()) {
                Text(
                    "${port.stageName} · ${port.stageStep}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
            if (port.running) {
                LinearProgressIndicator(
                    progress = { port.progress },
                    modifier = Modifier.fillMaxWidth().height(5.dp),
                    color = NxPalette.Building,
                )
            }
        }
    }
}
