package com.nx.android.input

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 手柄的档案：每只手柄一份，存在 App 自己的目录里（按档案键分）。
 *
 * 档案里是玩家对这只**手柄**做的事：绑了哪个键、禁用了哪个键、死区多大、扳机阈值多少、
 * 面键按位置还是按标签。**不属于游戏**：换一个游戏还是这一份。
 *
 * 坏了怎么办：整份读不出来就当没有（回到出厂映射），坏文件挪到一边备查 —— 手柄中心照常能打开。
 */
class PadProfileStore(private val file: File) {

    /** 一只手柄的档案。 */
    data class Profile(
        val key: String,
        val name: String = "",
        val persistent: Boolean = true,
        /** 弃用：面键按位置还是按标签的老开关。现在面键的语义由逐键绑定表达，这个恒为 false；
         *  老档案里存过的值照读不误（读出来也不作数），留着是为了不把老档案判成坏的。 */
        val labels: Boolean = false,
        val bindings: Map<String, String> = emptyMap(),   // 来源 → 游戏键（PadSources.DISABLED 是禁用）
        val calibration: PadCalibration = PadCalibration(),
    )

    /** 读到的档案与读的结果（坏的、降级的）。 */
    data class LoadResult(
        val profiles: Map<String, Profile>,
        val recoveredFromCorruption: Boolean = false,
    )

    companion object {
        const val SCHEMA_VERSION = 1
        const val FILE_NAME = "profiles.json"
        const val BAD_SUFFIX = ".bad"
    }

    private val backup: File get() = File(file.parentFile, file.name + ".bak")

    /** 读全部档案（坏了就是空的）。 */
    fun load(): LoadResult {
        val raw = readTextOrNull(file) ?: return LoadResult(emptyMap())
        val decoded = runCatching { decode(raw) }.getOrNull()
        if (decoded != null) return LoadResult(decoded)
        val rawBackup = readTextOrNull(backup)
        val recovered = rawBackup?.let { runCatching { decode(it) }.getOrNull() }
        if (recovered != null) {
            runCatching { file.writeText(rawBackup) }
            return LoadResult(recovered, recoveredFromCorruption = true)
        }
        // 实在读不出来：把坏文件挪到一边，当作没有档案（回到出厂映射）
        runCatching {
            file.copyTo(File(file.parentFile, file.name + BAD_SUFFIX), overwrite = true)
            file.delete()
        }
        return LoadResult(emptyMap(), recoveredFromCorruption = true)
    }

    /** 写全部档案（先写备份，再写本体）。 */
    fun save(profiles: Map<String, Profile>) {
        runCatching { file.parentFile?.mkdirs() }
        val encoded = encode(profiles)
        runCatching { file.writeText(encoded) }
        runCatching { backup.writeText(encoded) }
    }

    /** 档案的文本形式（单元测试直接过这一关，不碰磁盘）。 */
    fun encode(profiles: Map<String, Profile>): String {
        val pads = JSONObject()
        profiles.toSortedMap().forEach { (key, profile) ->
            val bindings = JSONObject()
            val disabled = JSONArray()
            profile.bindings.toSortedMap().forEach { (source, target) ->
                if (!PadSources.isKnown(source)) return@forEach
                if (target == PadSources.DISABLED) disabled.put(source) else if (PadTargets.isValid(target)) bindings.put(source, target)
            }
            val entry = JSONObject()
                .put("name", profile.name)
                .put("persistent", profile.persistent)
                .put("labels", profile.labels)
                .put("bindings", bindings)
                .put("disabled", disabled)
            if (profile.calibration.configured) {
                entry.put("calibration", JSONObject()
                    .put("deadzone", profile.calibration.deadzone.toDouble())
                    .put("trigger", profile.calibration.trigger.toDouble()))
            }
            pads.put(key, entry)
        }
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("pads", pads)
            .toString()
    }

    /** 档案的文本形式读回来（脏数据丢，不抛）。 */
    fun decode(raw: String): Map<String, Profile> {
        val root = JSONObject(raw)
        require(root.optInt("schemaVersion", -1) == SCHEMA_VERSION) { "Unsupported profile schema" }
        val pads = root.optJSONObject("pads") ?: throw IllegalArgumentException("Missing pad profiles")
        val out = linkedMapOf<String, Profile>()
        for (key in pads.keys()) {
            val entry = pads.optJSONObject(key) ?: continue
            val bindings = linkedMapOf<String, String>()
            val encoded = entry.optJSONObject("bindings") ?: JSONObject()
            for (source in encoded.keys()) {
                val target = encoded.optString(source)
                if (PadSources.isKnown(source) && PadTargets.isValid(target)) bindings[source] = target
            }
            val disabled = entry.optJSONArray("disabled") ?: JSONArray()
            for (index in 0 until disabled.length()) {
                val source = disabled.optString(index)
                if (PadSources.isKnown(source)) bindings[source] = PadSources.DISABLED
            }
            val calibration = entry.optJSONObject("calibration")?.let {
                PadCalibration(
                    deadzone = it.optDouble("deadzone", PadCalibration.DEFAULT_DEADZONE.toDouble()).toFloat(),
                    trigger = it.optDouble("trigger", PadCalibration.DEFAULT_TRIGGER.toDouble()).toFloat(),
                )
            } ?: PadCalibration()
            out[key] = Profile(
                key = key,
                name = entry.optString("name", ""),
                persistent = entry.optBoolean("persistent", true),
                labels = entry.optBoolean("labels", false),
                bindings = bindings,
                calibration = calibration,
            )
        }
        return out
    }

    private fun readTextOrNull(target: File): String? =
        if (!target.isFile) null else runCatching { target.readText() }.getOrNull()
}
