package com.nx.android.domain

import org.json.JSONObject

/**
 * What a game file holds, as the porter reads it (`porter inspect --json`).
 *
 * The reading is the porter's own: it opens the NSP/XCI headers, finds the title ID (with prod.keys when the dump
 * carries no tag), and looks the game's profile up in the tree the app ships. The app shows what it says and
 * follows its rules — the same rules the desktop GUI follows.
 */
data class GameInfo(
    val titleId: String = "",
    val name: String = "",
    val status: GameProfileStatus = GameProfileStatus.UNSUPPORTED,
    val supported: Boolean = false,
    /** The porter's own word: the game has no profile for Android, so the port has to be an experimental one. */
    val experimental: Boolean = false,
    val reason: String = "",
    /** The base game's file name */
    val base: String = "",
    /** The updates next to the base game */
    val updates: List<GameFile> = emptyList(),
    /** The DLC next to the base game */
    val dlc: List<GameFile> = emptyList(),
    /** The version this port would pack (empty when the porter has none to pack). */
    val packVersion: String = "",
)

/** One file the porter found: its name, its version and the subfolder it was found in. */
data class GameFile(
    val version: String = "",
    val name: String = "",
    /** The subfolder it was found in ('' when it sits next to the picked file) */
    val folder: String = "",
)

/** How far the porter's profile for a game goes. */
enum class GameProfileStatus {
    /** playable: the profile is brought up on this game. */
    PLAYABLE,

    /** wip: it plays, but parts of it are missing. */
    WIP,

    /** boots: it starts. */
    BOOTS,

    /** experimental: the profile is a provisional one. */
    EXPERIMENTAL,

    /** a game profile, but not ported to Android yet. */
    IOS_ONLY,

    /** no game profile at all. */
    UNSUPPORTED;

    companion object {
        /** The porter's own words (`android.tsv`'s status); an unknown one is treated as the best case. */
        fun of(raw: String): GameProfileStatus = when (raw) {
            "wip" -> WIP
            "boots" -> BOOTS
            "experimental" -> EXPERIMENTAL
            else -> PLAYABLE
        }
    }
}

/** Reads what a game file holds. The app's own reading of it is the porter's. */
interface GameInspector {
    /** What the file holds and whether it can be ported; null when the file could not be read at all. */
    suspend fun inspect(gameFile: String, keys: String): GameInfo?
}

/**
 * The build options a game needs.
 *
 * The experimental port is not a choice for a game the porter has no profile for: the port refuses to run
 * without it, so the option is ticked for the player (null when nothing has to change).
 */
object BuildOptions {
    fun withExperimental(current: GameConfig, info: GameInfo?): GameConfig? =
        if (info?.experimental == true && !current.experimental) current.copy(experimental = true) else null
}

/**
 * The porter's report, read out of the guest's output.
 *
 * The tool writes its report to stdout and everything else (a warning, a traceback) around it, so the JSON
 * document is taken out of the text. A report that says it failed (`{"error": …}`) is no report: null comes back.
 */
object GameInfoJson {

    fun decode(output: String): GameInfo? {
        val text = output.trim()
        // the report is printed indented, so its opening brace is the last one at the start of a line
        val start = text.lastIndexOf("\n{").let { if (it >= 0) it + 1 else text.indexOf('{') }
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val json = try {
            JSONObject(text.substring(start, end + 1))
        } catch (e: Exception) {
            return null
        }
        if (json.has("error")) return null
        val android = json.optJSONObject("android")
        val status = when {
            android != null -> GameProfileStatus.of(android.text("status"))
            json.text("profile").isNotEmpty() -> GameProfileStatus.IOS_ONLY
            else -> GameProfileStatus.UNSUPPORTED
        }
        return GameInfo(
            titleId = json.text("title_id"),
            name = json.text("name"),
            status = status,
            supported = json.optBoolean("supported"),
            experimental = json.optBoolean("experimental"),
            reason = json.text("reason"),
            base = json.text("base"),
            updates = json.files("updates"),
            dlc = json.files("dlc"),
            packVersion = json.text("pack"),
        )
    }

    /** The files of one array of the report: each with its name, its version and the subfolder it was found in. */
    private fun JSONObject.files(name: String): List<GameFile> {
        val array = optJSONArray(name) ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            GameFile(version = o.text("version"), name = o.text("file"), folder = o.text("folder"))
        }
    }

    /** A field as text: a missing one and a null one are both empty (the porter writes null for "no pack"). */
    private fun JSONObject.text(name: String): String = if (isNull(name)) "" else optString(name)
}
