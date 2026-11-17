package com.nx.android.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.nx.android.R
import com.nx.android.domain.Ported

/**
 * 游戏的桌面快捷方式（pin shortcut）：id 稳定（game-<title ID 小写>），重复发送更新同一个图标而不产生
 * 重复；只带 title ID，点击时重新解析——包挪到存储卡、卡拔插、库重扫都不影响它（身份是 title ID 不是路径）。
 * 点击落在 MainActivity 的自定义 action 上，与库页卡片走同一扇门。
 */
object GameShortcuts {

    /** the shortcut's intent lands here (MainActivity handles this action in onCreate and onNewIntent) */
    const val ACTION = "com.nx.android.action.LAUNCH_GAME"
    const val EXTRA_TITLE_ID = "shortcut_title_id"

    fun id(titleId: String): String = "game-${titleId.lowercase()}"

    /**
     * Sends a game to the home screen: true when the icon is out there, false when this device's launcher
     * does not support pinning (the caller says so — nothing fails silently). Sending again updates the
     * same icon (the stable id), never a second one.
     */
    fun request(activity: Activity, ported: Ported, name: String): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(activity)) return false
        val label = name.ifBlank { ported.titleId }
        val bitmap = ported.icon?.absolutePath?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
        val icon = if (bitmap != null) IconCompat.createWithBitmap(bitmap)
                   else IconCompat.createWithResource(activity, R.mipmap.ic_launcher)
        val intent = Intent(activity, com.nx.android.MainActivity::class.java)
            .setAction(ACTION)
            .putExtra(EXTRA_TITLE_ID, ported.titleId)
        val info = ShortcutInfoCompat.Builder(activity, id(ported.titleId))
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(icon)
            .setIntent(intent)
            .build()
        return ShortcutManagerCompat.requestPinShortcut(activity, info, null)
    }

    /** The game is gone from the library: its home-screen icon greys out, and tapping it starts nothing. */
    fun disable(context: Context, titleId: String, message: String) {
        runCatching { ShortcutManagerCompat.disableShortcuts(context, listOf(id(titleId)), message) }
    }
}
