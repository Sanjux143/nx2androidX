package com.nx.android.data.library

import com.nx.android.core.storage.AppPaths
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a game needs before it is ported: the profile the app ships (the payload's tree).
 *
 * The keys are the profile's own (dm/games/<title ID>/android.tsv): the graphics API the game draws with,
 * whether it plays over the local network, what the device has to have, and how far the profile goes.
 * A game with no profile here is one this app knows nothing about.
 */
data class GameProfile(
    val status: String = "",      // playable | wip | boots | experimental (the profile's own word)
    val graphics: String = "",    // vulkan | gl | nvn ('' when it is not known)
    val lan: Boolean = false,     // playable over the local network with several devices
    val requires: List<String> = emptyList(),   // device features the game needs (bcn: BC texture compression)
)

/** Reads the profiles the app ships; one file per game, read once and remembered. */
@Singleton
class ProfileReader @Inject constructor(
    private val paths: AppPaths,
) {
    private val dir = File(paths.tool, "nxnative/dm/games")
    private val cache = HashMap<String, GameProfile?>()

    /** The profile of a game by its title ID; null when the app ships none for it. */
    @Synchronized
    fun of(titleId: String): GameProfile? {
        if (titleId.isEmpty()) return null
        if (cache.containsKey(titleId)) return cache[titleId]
        val p = read(titleId)
        cache[titleId] = p
        return p
    }

    private fun read(titleId: String): GameProfile? {
        val f = profileFile(dir, titleId) ?: return null
        return try { parseProfile(f.readText(Charsets.UTF_8)) } catch (_: Exception) { null }
    }
}

/**
 * 一款游戏的档案文件：档案的文件夹名是**大写**的 title ID（01001300232F4000），清单里也是。
 * 两个写法都试一遍，认到哪个算哪个。纯函数：测试在 JVM 上跑。
 */
fun profileFile(gamesDir: File, titleId: String): File? {
    for (id in listOf(titleId, titleId.uppercase())) {
        val f = File(File(gamesDir, id), "android.tsv")
        if (f.isFile) return f
    }
    return null
}

/**
 * 一份档案（dm/games/<id>/android.tsv）里的那几个键：`键<TAB>值` 一行一个，`#` 开头是注释。
 * 纯函数：测试在 JVM 上跑。
 */
fun parseProfile(text: String): GameProfile {
    val rows = HashMap<String, String>()
    for (line in text.split('\n')) {
        val l = line.trimEnd('\r')
        if (l.isBlank() || l.startsWith("#")) continue
        val k = l.substringBefore('\t')
        if (k.isEmpty() || k in rows) continue
        rows[k] = l.substringAfter('\t', "").trim()
    }
    return GameProfile(
        status = rows["status"] ?: "",
        graphics = rows["graphics"] ?: "",
        lan = rows["lan"] == "1",
        requires = rows["requires"]?.split(' ')?.filter { it.isNotEmpty() } ?: emptyList(),
    )
}
