package com.nx.android

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import com.nx.android.domain.porting.PortPhase
import javax.inject.Inject
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nx.android.R
import com.nx.android.appstate.LibraryState
import com.nx.android.appstate.EnvCheckState
import com.nx.android.appstate.OnboardingState
import com.nx.android.appstate.PlayerState
import com.nx.android.appstate.PortState
import com.nx.android.data.AppLocale
import com.nx.android.data.AppLog
import com.nx.android.data.AppVersion
import com.nx.android.data.LogBundle
import com.nx.android.data.Player
import com.nx.android.data.PrefsStore
import com.nx.android.data.porting.BuildSessionRepository
import com.nx.android.core.storage.AppPaths
import androidx.core.content.FileProvider
import com.nx.android.data.library.LibraryFileScanner
import com.nx.android.domain.GameIdentity
import com.nx.android.domain.LibraryLogic
import com.nx.android.domain.RunAction
import com.nx.android.app.AppActions
import com.nx.android.app.AppRoot
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The shell: it owns what only an activity can own — the storage permission, the system pickers — and puts
 * the screen up. Hilt supplies long-lived dependencies; screens receive state holders
 * and action lambdas, never this activity.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var portCompletionCoordinator: com.nx.android.appstate.porting.PortCompletionCoordinator

    companion object {
        const val EXTRA_TAB = "tab"   // the notification's tap lands on the builds page
        const val EXTRA_BUILD_ID = "buildId"
    }

    @Inject lateinit var locale: AppLocale
    @Inject lateinit var prefs: PrefsStore
    @Inject lateinit var library: LibraryState
    @Inject lateinit var player: PlayerState
    @Inject lateinit var operations: com.nx.android.appstate.LibraryOperationCoordinator
    @Inject lateinit var runtime: com.nx.android.appstate.RuntimeUpdateState
    @Inject lateinit var port: PortState
    @Inject lateinit var buildSessions: BuildSessionRepository
    @Inject lateinit var paths: AppPaths
    @Inject lateinit var onboarding: OnboardingState
    @Inject lateinit var environmentSetup: com.nx.android.domain.environment.EnvironmentSetup
    @Inject lateinit var drivers: com.nx.android.domain.drivers.DriverRepository
    @Inject lateinit var envCheck: EnvCheckState
    @Inject lateinit var update: com.nx.android.appstate.UpdateState

    /** 界面语言：toast 与分享面板的标题从这里取词 */
    /** Which tab the screen opens on (the notification's tap says the builds page) */
    var launchTab = 0
    private var launchBuildId by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全屏沉浸：内容铺满整块屏幕，系统栏隐藏（下滑临时呼出）
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        onboarding.storageOk = storageGranted()
        onboarding.batteryOk = batteryUnrestricted()
        launchTab = intent?.getIntExtra(EXTRA_TAB, 0) ?: 0
        launchBuildId = intent?.getStringExtra(EXTRA_BUILD_ID).orEmpty()
        if (!prefs.bool(PrefsStore.OB_COMPLETED)) onboarding.open()
        AppLog.init(this)
        port.attach()   // a port the system killed shows up as interrupted
        port.history.filter { !it.legacy && it.phase in setOf(PortPhase.DONE, PortPhase.FAILED, PortPhase.CANCELLED, PortPhase.INTERRUPTED) }
            .forEach(portCompletionCoordinator::complete)
        if (port.phase == PortPhase.INTERRUPTED)
            port.history.firstOrNull { it.phase == PortPhase.INTERRUPTED }?.let(portCompletionCoordinator::complete)
        // 游戏在跑：游戏页是这个任务里压在库页上面的那一页——先把它顶到前面，再画库页（库页画在它下面，
        // 玩家看不见，但游戏结束后它就在那儿）。判据直接问进程，不看 PlayerState.running：那个靠 2 秒
        // 轮询，App 的进程刚起来时还是 false。
        if (isLauncherEntry(intent) && Player.playProcessAlive(this)) com.nx.android.game.GameActivity.resume(this)
        setContent { AppRoot(prefs, locale, port, library, player, onboarding, drivers, environmentSetup, actions, launchTab, runtime, launchBuildId) }
        // the small tools come with the app: an update brings new ones, and the phone has to get them
        // (the app's own Vulkan driver too: it is unpacked into the app's folder, ready to be picked)
        Thread {
            environmentSetup.refreshTools()
        }.start()
        lifecycleScope.launch {
            drivers.ensureShipped()
            drivers.refresh()
        }
        // 有没有新版本：冷启动查一次，查不到就安静地过去（引导没走完的第一次启动不查）。
        // 它自己开线程（协程），所以不跟上面那条工具刷新挤在一起：那边出了岔子（未捕获的异常会
        // 杀掉整个进程），也不能把这一查一起带走。
        if (prefs.bool(PrefsStore.OB_COMPLETED)) update.checkQuietly()
    }

    /** The screens' way back to the activity's own privileges: the actions they may ask for, nothing else */
    private val actions = AppActions(
        onAskStorage = { askStorage() },
        onAskBattery = { askBattery() },
        storageOk = { onboarding.storageOk },
        onSetKeys = { prefs.writeKeys(it); envCheck.refresh() },
        onStartPort = { file, cfg ->
            // 没有「所有文件访问」权限就读不到游戏文件（重装 App 后会掉）：移植会死在半个 Python 栈里，先说清楚
            if (port.running || runtime.running || player.running || Player.playProcessAlive(this)) {
                toast(locale.str(R.string.runtime_busy))
            } else if (file.canRead()) {
                // 落位跟着这个游戏的数据包走：它在可移动卡上，新包就建在那儿（内部存储的那份不动）
                val packDir = library.packDirOf(file)?.absolutePath
                PortService.start(this, file.absolutePath, prefs.keys,
                                  cfg.touch, cfg.fps60, cfg.experimental, cfg.aliasHeadroom, packDir)
            } else {
                toast(locale.str(R.string.builds_needs_storage)); askStorage()
            }
        },
        onCancelPort = { PortService.cancel(this) },
        onRun = { pack, name ->
            // 运行一个完成的移植：这个游戏还活着就把它带回前台（不重启），否则拉起 :play
            // （包是唯一真源，拷贝覆盖旧副本）
            LibraryFileScanner.portFromPack(pack)?.let { ported ->
                val action = LibraryLogic.runAction(
                    running = player.running,
                    runningTitleId = player.titleId,
                    portTitleId = ported.titleId,
                    processAlive = Player.playProcessAlive(this),
                )
                if (port.running || runtime.running) {
                    toast(locale.str(R.string.runtime_busy))
                } else if (ported.needsRuntimeUpdate) {
                    if (player.running || Player.playProcessAlive(this)) toast(locale.str(R.string.runtime_busy))
                    else updateRuntime(pack, name)
                } else if (action == RunAction.RESUME) {
                    com.nx.android.game.GameActivity.resume(this)
                } else {
                    prefs.put(PrefsStore.PLAYER_LAST_START, System.currentTimeMillis().toString())
                    if (!operations.beginPlay(ported.titleId, name)) {
                        toast(locale.str(R.string.runtime_busy))
                        return@let
                    }
                    Player.run(this, ported, name) { ok ->
                        if (!ok) {
                            player.stop()
                            toast(locale.str(R.string.main_cannot_run_no_host, pack.name) + "\n" + locale.str(R.string.runtime_play_failed))
                        }
                    }
                }
            } ?: toast(locale.str(R.string.main_cannot_run_not_a_pack, pack.name))
        },
        onUpdateRuntime = { pack, name -> updateRuntime(pack, name) },
        onStopGame = {
            player.stop()
            com.nx.android.game.GameActivity.stop(this)
        },
        onToast = { toast(it) },
        version = { versionName() },
        keys = { prefs.keys },
        onShareLog = {
            lifecycleScope.launch {
                val zip = withContext(Dispatchers.IO) {
                    LogBundle.build(this@MainActivity, paths.work, buildSessions.logFiles(), bundleDir())
                }
                shareBundle(zip, R.string.main_share_log, R.string.main_log_empty)
            }
        },
        onClearLog = {
            AppLog.clear()
            toast(locale.str(R.string.main_log_cleared))
        },
        readLog = { AppLog.text(2000) },
        onShareBuildLog = { id ->
            lifecycleScope.launch {
                val session = port.history.firstOrNull { it.id == id }
                when {
                    session == null || !session.logAvailable -> toast(locale.str(R.string.builds_log_unavailable))
                    buildSessions.logLength(id) == 0L -> toast(locale.str(R.string.builds_log_empty))
                    else -> {
                        val safeName = BuildSessionRepository.safeName(session.game)
                        val log = buildSessions.logFile(id)
                        val zip = withContext(Dispatchers.IO) {
                            LogBundle.build(this@MainActivity, paths.work,
                                listOfNotNull(log?.let { "$safeName-${id.take(8)}.log" to it }),
                                bundleDir(), "$safeName-${id.take(8)}")
                        }
                        shareBundle(zip, R.string.builds_log_share, R.string.builds_log_empty)
                    }
                }
            }
        },
        readBuildLog = { id, offset, length -> buildSessions.readLog(id, offset, length) },
        buildLogLength = { id -> buildSessions.logLength(id) },
        isBuildActive = { id -> port.history.firstOrNull { it.id == id }?.phase == PortPhase.RUNNING },
        emptyBuildLogMessage = { id ->
            val session = port.history.firstOrNull { it.id == id }
            locale.str(when {
                session == null || !session.logAvailable -> R.string.builds_log_unavailable
                session.phase == PortPhase.RUNNING -> R.string.builds_log_waiting
                session.legacy -> R.string.builds_log_legacy
                else -> R.string.builds_log_empty
            })
        },
        titleBuildLog = { id -> port.history.firstOrNull { it.id == id }?.let {
            "${File(it.game).name} · ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it.startedAt))}"
        } ?: locale.str(R.string.common_log) },
    )

    private fun updateRuntime(pack: File, name: String) {
        if (Player.playProcessAlive(this) || !operations.beginRuntimeUpdate(pack.absolutePath, name)) {
            toast(locale.str(R.string.runtime_busy))
            return
        }
        try {
            RuntimeUpdateService.start(this)
        } catch (error: Exception) {
            AppLog.line("[RED] Runtime update could not start: ${error.message}")
            runtime.finish(false)
        }
    }

    override fun onResume() {
        super.onResume()
        onboarding.storageOk = storageGranted()   // the permission may have been given in the background
        onboarding.batteryOk = batteryUnrestricted()   // so may the battery exemption
        // 回到前台：顶栏胶囊的两个事实重读一遍（玩家可能在后台改了文件）
        envCheck.refresh()
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val started = prefs.str(PrefsStore.PLAYER_LAST_START).toLongOrNull() ?: 0L
            if (started > 0 && !Player.playProcessAlive(this)) {
                val manager = getSystemService(android.app.ActivityManager::class.java)
                val crash = runCatching {
                    manager.getHistoricalProcessExitReasons(packageName, 0, 16).any {
                        it.processName.endsWith(Player.PROCESS_SUFFIX) && it.timestamp >= started &&
                            (it.reason == android.app.ApplicationExitInfo.REASON_CRASH ||
                             it.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE)
                    }
                }.getOrDefault(false)
                if (crash) {
                    prefs.put(PrefsStore.PLAYER_LAST_START, null)
                    toast(locale.str(R.string.runtime_play_failed))
                }
            }
        }
    }

    /**
     * 桌面图标那条 intent 会送到任务根的**已有**那个库页实例上（栈的顺序不动，游戏页还在最上面）。
     * 有些 ROM 会把任务根拉到前台，库页就盖住了游戏——所以这里再把游戏页顶回最前。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra(EXTRA_BUILD_ID).orEmpty().isNotEmpty()) {
            launchTab = intent.getIntExtra(EXTRA_TAB, 0)
            launchBuildId = intent.getStringExtra(EXTRA_BUILD_ID).orEmpty()
        }
        if (isLauncherEntry(intent) && Player.playProcessAlive(this)) com.nx.android.game.GameActivity.resume(this)
    }

    /**
     * 「打开 App」的那一条：桌面图标的 intent（MAIN/LAUNCHER）。
     * 别的入口不是——通知点进来是要去构建页的，玩家明确要去那一页，游戏不该把它抢走。
     */
    private fun isLauncherEntry(i: Intent?): Boolean =
        i?.action == Intent.ACTION_MAIN && i.hasCategory(Intent.CATEGORY_LAUNCHER)

    /** The phone's language changed (configChanges holds it): the app's words are read again */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        locale.configurationChanged()
    }

    // ---- the storage (the activity's own privilege) ----------------------------------------------------------

    /** The shared storage: the game files and the ports the tool makes. From Android 11 the old permissions are
     * not enough: the app needs the all-files access, which the user gives once */
    private fun storageGranted(): Boolean {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager()
        return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    fun askStorage() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                          android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            }
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e2: Exception) {
                toast(locale.str(R.string.main_no_permission_settings))
            }
        }
    }

    // ---- the battery (the system's own privilege) ------------------------------------------------------------

    /**
     * Whether the app is exempt from the power saving. The unpack and a port are long background jobs:
     * the power saving stops them halfway without it.
     *
     * Some ROMs never report the exemption (the setting lives in their own power manager), and a phone
     * that cannot answer must not look broken: a failure counts as exempt. The check is a hint, not a
     * gate, so nothing depends on the answer being right.
     */
    private fun batteryUnrestricted(): Boolean = try {
        val pm = getSystemService(android.os.PowerManager::class.java)
        pm == null || pm.isIgnoringBatteryOptimizations(packageName)
    } catch (e: Exception) {
        true
    }

    /**
     * Android's own prompt: the player allows the app to run in the background there. The list of the
     * exempt apps is the fallback for the ROMs that have no prompt of their own.
     */
    fun askBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                 Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                toast(locale.str(R.string.main_no_battery_settings))
            }
        }
    }

    // ---- after the port (the activity's own privilege) -----------------------------------------------------------

    fun versionName(): String = AppVersion.of(this).ifEmpty { "?" }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    /**
     * The log bundle's folder in the cache: the FileProvider serves it (res/xml/file_paths.xml), and the
     * bundles older than a week are deleted when a new one is packed.
     */
    private fun bundleDir() = File(cacheDir, "shared-logs")

    /** Sends the log bundle (a zip) out; nothing when there was nothing to pack */
    private fun shareBundle(zip: File?, titleRes: Int, emptyRes: Int) {
        if (zip == null) {
            toast(locale.str(emptyRes))
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", zip)
            val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, locale.str(titleRes)))
        } catch (e: Exception) {
            AppLog.line("[RED] the log bundle could not be shared: ${e.message}")
            toast(locale.str(R.string.main_log_bundle_failed))
        }
    }

    // ---- Activity-owned state holders injected by Hilt ------------------------------------------------------------

}
