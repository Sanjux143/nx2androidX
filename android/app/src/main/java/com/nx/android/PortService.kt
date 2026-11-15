package com.nx.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.widget.Toast
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import com.nx.android.domain.porting.PortPhase
import com.nx.android.domain.GameConfig
import com.nx.android.domain.ALIAS_AUTO
import javax.inject.Inject
import com.nx.android.R
import com.nx.android.appstate.PortState
import com.nx.android.appstate.porting.PortCompletionCoordinator
import com.nx.android.data.AppLocale
import com.nx.android.data.AppLog
import com.nx.android.data.PortRunner
import com.nx.android.domain.environment.EnvironmentSetup
import com.nx.android.domain.environment.GuestProcessLauncher
import com.nx.android.domain.GameIdentity
import java.io.File

/**
 * The foreground service that keeps the port alive while the app is in the background or the screen is off: the
 * porter runs in the app's own process, and Android kills idle background processes — the service's notification
 * says a port is on, so the process stays. It carries no state: PortState is the one source; the service keeps
 * the process alive and mirrors the progress into the notification. Not sticky: the porter cannot resume, so a
 * killed service is an interrupted port, not one to restart half-made.
 *
 * 前台服务：在 app 退到后台或息屏时让移植继续活着——移植器跑在 app 自己的进程里，而 Android 会杀掉空闲的
 * 后台进程；这条服务的常驻通知说明「有移植在进行」，进程因此留下。它不持有状态：PortState 是唯一的真源，
 * 服务只负责让进程活着，并把进度映到通知里。它不是 sticky 的：移植器无法续跑，所以被杀掉的服务算一次
 * 中断的移植，而不是一个半成品要从头再来。
 */
@AndroidEntryPoint
class PortService : Service() {

    companion object {
        const val CHANNEL = "nx2a_port"
        const val ACTION_START = "com.nx.android.port.START"
        const val ACTION_CANCEL = "com.nx.android.port.CANCEL"
        const val GAME = "game";
        const val KEYS = "keys"
        const val TOUCH = "touch";
        const val FPS60 = "fps60";
        const val EXP = "exp"
        const val ALIAS = "alias";
        const val FONT = "font";
        const val FIRMWARE = "firmware";
        const val MT = "mt";
        const val PACK_DIR = "packDir"
        // The Intent's extras: the port's parameters, handed over from the UI to the service
        // Intent 的 extra：移植的参数，从界面交到服务手里

        /**
         * Starts a port: the state goes up first (the UI turns at once), the service keeps it alive
         * 启动一次移植：状态先起来（界面立刻转向），服务负责让它活着
         */
        fun start(
            ctx: Context, game: String, keys: String,
            touch: Boolean, fps60: Boolean, exp: Boolean, aliasHeadroom: Int,
            sharedFont: String = "", firmware: String = "", mtReplay: Boolean = false,
            packDir: String? = null
        ) {
            val i = Intent(ctx, PortService::class.java)
                .setAction(ACTION_START)
                .putExtra(GAME, game).putExtra(KEYS, keys)
                .putExtra(TOUCH, touch).putExtra(FPS60, fps60).putExtra(EXP, exp)
                .putExtra(ALIAS, aliasHeadroom)
            if (sharedFont.isNotBlank()) i.putExtra(FONT, sharedFont)
            if (firmware.isNotBlank()) i.putExtra(FIRMWARE, firmware)
            if (mtReplay) i.putExtra(MT, true)
            if (!packDir.isNullOrBlank()) i.putExtra(PACK_DIR, packDir)
            ContextCompat.startForegroundService(ctx, i)
        }

        /**
         * The stop from the UI: the porter and its tools die, the state marks the cancel
         * 界面上的「停止」：移植器和它起的工具进程一起死，状态标记为已取消
         */
        fun cancel(ctx: Context) {
            ctx.startService(Intent(ctx, PortService::class.java).setAction(ACTION_CANCEL))
        }
    }

    @Inject lateinit var operations: com.nx.android.appstate.LibraryOperationCoordinator

    private var runner: PortRunner? = null
    private lateinit var nm: NotificationManager
    @Inject
    lateinit var port: PortState
    @Inject
    lateinit var locale: AppLocale
    @Inject
    lateinit var environmentSetup: EnvironmentSetup
    @Inject
    lateinit var guestLauncher: GuestProcessLauncher
    @Inject
    lateinit var completionCoordinator: PortCompletionCoordinator
    @Inject
    lateinit var paths: com.nx.android.core.storage.AppPaths

    /** Nothing binds to it: the service is started with Intents only. 没有谁绑定它：服务只用 Intent 启动 */
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        // The channel has to exist before the first notification: its name follows the UI language, low importance
        // 通知渠道必须在第一条通知之前建好：名字跟着界面语言走，重要度低
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                locale.str(R.string.port_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The cancel comes as a plain Intent: kill the porter first, then mark the state, then end the service
        // 取消是一条普通 Intent：先杀掉移植器，再标记状态，最后结束服务
        if (intent?.action == ACTION_CANCEL) {
            val cancelled = runner
            val cancelledId = port.buildId
            runner = null
            port.cancel()
            cancelled?.cancel()
            port.history.firstOrNull { it.id == cancelledId }?.let(completionCoordinator::complete)
            stopShown()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val game = intent?.getStringExtra(GAME) ?: return stop()
        if (port.running) return START_NOT_STICKY
        if (com.nx.android.data.Player.playProcessAlive(this)) {
            startForeground(1, notification())
            stopShown()
            return stop()
        }
        val keys = intent.getStringExtra(KEYS) ?: return stop()
        // The state goes up before the foreground notification: the UI turns at once
        // 状态先起来，再挂前台通知：界面立刻转向
        val cfg = GameConfig(
            touch = intent.getBooleanExtra(TOUCH, false),
            fps60 = intent.getBooleanExtra(FPS60, false),
            experimental = intent.getBooleanExtra(EXP, false),
            aliasHeadroom = intent.getIntExtra(ALIAS, ALIAS_AUTO),
            sharedFont = intent.getStringExtra(FONT) ?: "",
            mtReplay = intent.getBooleanExtra(MT, false),
        )
        val began = try { operations.beginBuild(game, cfg) } catch (e: Exception) {
            AppLog.line("[RED] Build history could not be saved: ${e.message}")
            Toast.makeText(this, locale.str(R.string.build_history_unavailable), Toast.LENGTH_LONG).show()
            return stop()
        }
        if (!began) {
            startForeground(1, notification())
            stopShown()
            return stop()
        }
        val taskId = port.buildId
        startForeground(1, notification())
        val handler = android.os.Handler(mainLooper)
        lateinit var taskRunner: PortRunner
        taskRunner = PortRunner(environmentSetup, guestLauncher, locale, File(paths.files, "nx2a/port_exit.txt")) { line ->
            handler.post {
                if (runner === taskRunner && port.running && port.buildId == taskId) {
                    port.onLine(line)
                    nm.notify(1, notification())
                }
            }
        }   // the log bundle takes the exit status: a port the system killed says so
        runner = taskRunner
        try {
            taskRunner.port(
                game, keys, intent.getBooleanExtra(TOUCH, false),
                intent.getBooleanExtra(FPS60, false), intent.getBooleanExtra(EXP, false),
                intent.getIntExtra(ALIAS, ALIAS_AUTO),
                intent.getStringExtra(FONT) ?: "", intent.getStringExtra(FIRMWARE) ?: "",
                intent.getBooleanExtra(MT, false), intent.getStringExtra(PACK_DIR),
            ) {
                handler.post {
                    if (runner === taskRunner) {
                        port.finish()
                        port.history.firstOrNull { it.id == taskId }?.let(completionCoordinator::complete)
                        nm.notify(1, notification())
                        stopShown()
                        stopSelf()
                    }
                }
            }
        } catch (error: Exception) {
            runner = null
            taskRunner.cancel()
            port.onLine("[RED] Build executor could not start: ${error.message}")
            port.finish()
            port.history.firstOrNull { it.id == taskId }?.let(completionCoordinator::complete)
            Toast.makeText(this, locale.str(R.string.build_executor_unavailable), Toast.LENGTH_LONG).show()
            stopShown()
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    /** The extras are missing: there is nothing to start, so the service ends at once. 参数缺失：没什么可启动的，服务直接结束 */
    private fun stop(): Int {
        stopSelf(); return START_NOT_STICKY
    }

    private fun stopShown() {
        try {
            stopForeground(STOP_FOREGROUND_DETACH)
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }


    /**
     * The notification: the game's name, where the port is now, and (once it ended) the result
     */
    private fun notification(): Notification {
        // Tapping it goes back to the app, on the tab where the port is shown
        // 点它回到 app，落在显示移植的那一页
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, 1)
                .putExtra(MainActivity.EXTRA_BUILD_ID, port.buildId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val what = when {
            port.running ->
                if (port.stageName.isEmpty()) locale.str(R.string.port_notification_running) else locale.str(
                    R.string.port_stage_of,
                    port.stageOf,
                    port.stages,
                    port.stageName
                ) +
                        (if (port.stageStep.isEmpty()) "" else " · ${port.stageStep}")

            port.phase == PortPhase.DONE -> locale.str(
                R.string.port_done_path,
                File(port.outPath).name
            )

            port.phase == PortPhase.CANCELLED -> locale.str(R.string.common_cancelled)
            port.phase == PortPhase.INTERRUPTED -> locale.str(R.string.port_interrupted_short)
            else -> locale.str(R.string.port_failed_no_pack)
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)   // the mark's shapes: the notification tints the silhouette
            // 用标记的图形：通知会把剪影染成单色
            .setContentTitle(
                if (port.gameFile.isEmpty()) locale.str(R.string.app_name) else GameIdentity.displayName(
                    File(port.gameFile)
                )
            )
            .setContentText(what)
            .setProgress(100, (port.progress * 100).toInt(), port.running && port.progress <= 0f)
            .setOngoing(port.running)
            .setOnlyAlertOnce(true)
            .setContentIntent(tap)
            .build()
    }
}
