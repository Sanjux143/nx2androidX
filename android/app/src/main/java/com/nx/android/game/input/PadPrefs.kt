package com.nx.android.game.input

import android.content.Context
import android.content.SharedPreferences

/**
 * 手柄的设置（显示、浓淡、按键风格）：单独一份偏好文件。
 *
 * 为什么单独：这些键只有游戏页写，而游戏页跑在 `:play` 进程里；库页与端口（主进程）写的是 `nx2a`。
 * SharedPreferences 不是多进程安全的——两个进程各有一份内存里的表，谁后写谁用自己的旧表覆盖整个文件。
 * 共用一个文件时，游戏里刚改的按键风格会被主进程的一次写入（库扫描、端口历史…）抹掉，下次进来又回到默认。
 * 手柄自己一份文件，只有游戏页碰它，就没有第二个写者。
 *
 * 旧版本把这些键写在 `nx2a` 里：第一次见到某个游戏时从那边搬过来（见 PadView 的 init）。
 */
class PadPrefs(ctx: Context) {

    companion object {
        /** 手柄自己的偏好文件 */
        const val FILE = "nx2a.pad"
    }

    private val prefs: SharedPreferences = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun str(key: String, def: String = ""): String = prefs.getString(key, def) ?: def

    fun bool(key: String, def: Boolean = false): Boolean = prefs.getBoolean(key, def)

    fun contains(key: String): Boolean = prefs.contains(key)

    fun put(key: String, value: String?) = prefs.edit().putString(key, value).apply()

    fun put(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
}
