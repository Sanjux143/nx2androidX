package com.nx.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

/**
 * The tool's saved settings: every key in one place, and the path the whole UI reads (the keys file) as
 * observable state — a write goes to disk at once, a read is a Compose read. The key names and their meanings
 * are the old app's, unchanged: an update keeps its settings.
 */
@Singleton
class PrefsStore @Inject constructor(@ApplicationContext ctx: Context) {

    companion object {
        // the prefs file's name (the app's own settings, one file)
        const val NAME = "nx2a"
        // the old app's key, unchanged
        const val KEYS = "keys"
        // the imported firmware package (a zip): its file name; the unpacked copy is in the app's own folder
        const val FIRMWARE = "firmware"
        // the old app's global port options: a game without its own config keeps them as the defaults
        const val LEGACY_TOUCH = "touch"
        const val LEGACY_FPS60 = "fps60"
        const val LEGACY_EXPERIMENTAL = "experimental"
        // the app's own language: "" follows the phone, "zh" and "en" are the player's own choice
        const val LANG = "lang"
        // the library's memory
        const val LIB_CONFIGS = "lib.configs"
        const val LIB_PORTS = "lib.ports"
        // title ID → the data pack's path the last time it was seen (for the storage page's "out of reach")
        const val LIB_PACKS = "lib.packs"
        // title ID → the source file the game was last built from (for the rebuild button)
        const val LIB_SOURCES = "lib.sources"
        const val LIB_MEMORIES_MIGRATED = "lib.memories.migrated"
        // the guided setup
        const val OB_STEP = "ob.step"
        const val OB_LATER = "ob.later"
        const val OB_COMPLETED = "ob.completed"
        // the open-source notice: the player has seen it (once is enough)
        const val OSS_NOTICE_SHOWN = "oss.notice.shown"
        // the port: the interruption marker and the history
        const val PORT_RUNNING = "port.running"
        const val RUNTIME_PENDING_PACK = "runtime.pendingPack"
        const val PLAYER_LAST_START = "player.lastStart"
        const val PORT_STARTED_AT = "port.startedAt"
        const val PORT_HISTORY = "port.history"
        const val PORT_COMPLETED = "port.completed"
        // the performance HUD: the two lines and what the performance one shows
        const val HUD_STATS = "hud.stats"
        const val HUD_DEVICE = "hud.device"
        const val HUD_FIELDS = "hud.fields"
        const val HUD_TEMP_F = "hud.temp.f"
    }

    val prefs: SharedPreferences = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** prod.keys 的路径：库的发起前提、设置页、引导都读它；picker 与引导写它（一份，就是这一个） */
    var keys by mutableStateOf(prefs.getString(KEYS, "") ?: "")
        private set

    fun writeKeys(path: String) {
        keys = path.trim()
        prefs.edit().putString(KEYS, keys).apply()
    }

    // ---- 通用读写（引导与库的记忆；键名见常量） ----

    fun str(key: String, def: String = ""): String = prefs.getString(key, def) ?: def

    fun bool(key: String, def: Boolean = false): Boolean = prefs.getBoolean(key, def)

    fun put(key: String, value: String?) = prefs.edit().putString(key, value).apply()

    fun put(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    // ---- JSON（历史、按游戏配置、产物映射） ----

    fun json(key: String): JSONObject = try {
        JSONObject(prefs.getString(key, "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    fun putJson(key: String, o: JSONObject) = prefs.edit().putString(key, o.toString()).apply()

    fun array(key: String): JSONArray = try {
        JSONArray(prefs.getString(key, "[]") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }

    fun putArray(key: String, a: JSONArray) = prefs.edit().putString(key, a.toString()).apply()
}
