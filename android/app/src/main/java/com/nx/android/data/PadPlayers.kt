package com.nx.android.data

import com.nx.android.input.PadAssignment
import org.json.JSONObject
import java.io.File

/**
 * 谁玩谁：这个游戏里哪只手柄是几号玩家（**每个游戏一份**），存在一个文件里。
 *
 * 文件（`files/players/<title ID>.json`）而不是 SharedPreferences：指派是在游戏里改的（游戏的抽屉在
 * `:play` 进程里），而首页的手柄中心在主进程里——两个进程各有一份 SharedPreferences 的缓存，一边写另一边
 * 看不见（手柄中心的玩家徽章会显示旧的）。文件是两边都直接读的真相，与手柄档案
 * （`files/pads/profiles.json`）一个做法。
 *
 * 存的是手柄的指纹（不认设备 id）：拔了再插，指纹不变，还是那个玩家。
 */
class PadPlayers(private val ctx: android.content.Context, private val titleId: String) {

    private val store = PadPlayersStore(
        File(File(ctx.filesDir, PadPlayersStore.DIR), "${titleId.lowercase()}.json"),
    )

    /** 读这个游戏的指派。 */
    fun load(): PadAssignment {
        store.read()?.let { return it }
        // 老的一份（在 SharedPreferences 里）：读出来搬进文件
        legacy()?.let { legacy ->
            save(legacy)
            return legacy
        }
        return PadAssignment()
    }

    /** 写下这个游戏的指派。 */
    fun save(a: PadAssignment) {
        store.write(a)
        // 老的那份也跟着写（降级回去时它还在）
        val prefs = legacyPrefs()
        for (i in 1..4) prefs.edit().putString("players.${titleId.lowercase()}.$i", a.padOf(i - 1)).apply()
    }

    /** 老的那一份（SharedPreferences）：只在文件还没有的时候用。 */
    private fun legacy(): PadAssignment? {
        val prefs = legacyPrefs()
        val base = "players.${titleId.lowercase()}"
        val values = (1..4).map { prefs.getString("$base.$it", "") ?: "" }
        if (values.all { it.isEmpty() }) return null
        return PadAssignment(values[0], values[1], values[2], values[3])
    }

    private fun legacyPrefs() = ctx.getSharedPreferences(PrefsStore.NAME, android.content.Context.MODE_PRIVATE)
}

/**
 * 指派的那个文件本身（纯文件读写，可在 JVM 上测）。
 *
 * 格式：
 * ```
 * { "schemaVersion": 1, "players": { "1": "<fingerprint>", "2": "", "3": "", "4": "" } }
 * ```
 * 读不出来（没有、坏了）就是没有指派：玩家 1 自动，别的空着。
 */
class PadPlayersStore(private val file: File) {

    companion object {
        const val SCHEMA_VERSION = 1
        const val DIR = "players"
    }

    /** 读出这个游戏的指派（没有或坏了就是 null）。 */
    fun read(): PadAssignment? = runCatching {
        val slots = JSONObject(file.readText()).optJSONObject("players") ?: return@runCatching null
        PadAssignment(
            p1 = slots.optString("1", ""),
            p2 = slots.optString("2", ""),
            p3 = slots.optString("3", ""),
            p4 = slots.optString("4", ""),
        )
    }.getOrNull()

    /** 写下这个游戏的指派（先写临时文件再改名，免得写到一半进程没了）。 */
    fun write(a: PadAssignment) {
        val slots = JSONObject()
        slots.put("1", a.p1); slots.put("2", a.p2); slots.put("3", a.p3); slots.put("4", a.p4)
        val json = JSONObject()
        json.put("schemaVersion", SCHEMA_VERSION)
        json.put("players", slots)
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".new")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) { file.writeText(json.toString()); tmp.delete() }
        }
    }
}
