package com.nx.android.domain

/**
 * The porter's progress events (`port --progress json`): one JSON object per line. Parsing is free of Android,
 * so the tests can run on the JVM.
 */
data class PortEvent(
    val kind: String,        // say / stage / progress / result
    val message: String,     // say's text
    val stage: Int,          // stage: which of the stages
    val stages: Int,         // stage: how many there are
    val name: String,        // progress: the stage's name
    val step: String,        // progress: what within the stage
    val note: String,        // progress: a line of the stage's own work (the rewriter's progress)
    val frac: Float,         // progress: 0..1, or -1 when absent
    val apk: String,         // result: the APK the port made
    val data: String,        // result: the data pack
    val pkg: String,         // result: the game's package name
)

object PortEvents {

    /** The line as an event; null for plain log text (not a JSON object) */
    fun parse(line: String): PortEvent? {
        val t = line.trim()
        if (!t.startsWith("{")) return null
        return try {
            val o = org.json.JSONObject(t)
            PortEvent(
                kind = o.optString("ev"),
                message = o.optString("msg"),
                stage = o.optInt("k", 0),
                stages = o.optInt("n", 0),
                name = o.optString("name"),
                step = o.optString("step"),
                note = o.optString("note"),
                frac = if (o.has("frac")) o.optDouble("frac", -1.0).toFloat() else -1f,
                apk = o.optString("apk"),
                data = o.optString("data"),
                pkg = o.optString("package"),
            )
        } catch (e: Exception) {
            null
        }
    }
}
