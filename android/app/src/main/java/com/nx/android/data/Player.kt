package com.nx.android.data

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import com.nx.android.domain.Ported
import com.nx.android.game.GameActivity

/**
 * The player's launcher: a game runs in the app's own ":play" process (the host is loaded there from the
 * app's private runtimes folder — sdcard is noexec, the shared storage's files cannot be loaded in place).
 * Running a game hands the pack to the GameActivity, whose own prepare brings the runtime in. One game at
 * a time; the process ends with the game, and the ledger watches the process to know whether one is playing.
 */
object Player {

    /** The ":play" process's name suffix (the manifest's android:process) */
    const val PROCESS_SUFFIX = ":play"

    /** the ":play" process's presence: a game is playing (or hanging in the background) while it is there */
    fun playProcessAlive(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.runningAppProcesses?.any { it.processName.endsWith(PROCESS_SUFFIX) } == true
    }

    /**
     * Runs a finished port: the GameActivity comes up and its own prepare brings the host in (the
     * fingerprint is checked there, a stale runtime is rebuilt there, the promotion copy of an old pack
     * happens there — this side only hands the pack over, whatever state its runtime is in).
     *
     * 这里没有进程的事：占不占用、有没有别的游戏在跑，都由账本（PlayerState）在门里回答——这里只管
     * 把包交给游戏页。
     */
    fun run(activity: Activity, port: Ported, name: String, onReady: (Boolean) -> Unit) {
        val i = Intent(activity, GameActivity::class.java)
            .putExtra(GameActivity.EXTRA_PACK, port.pack.absolutePath)
            .putExtra(GameActivity.EXTRA_TITLE_ID, port.titleId)
            .putExtra(GameActivity.EXTRA_NAME, name)
        activity.startActivity(i)
        onReady(true)
    }

}
