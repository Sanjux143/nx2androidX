package com.nx.android.domain

import org.json.JSONObject
import java.io.File

/** porter 接受的游戏文件后缀（白名单，小写、不带点） */
val GAME_FILE_EXTENSIONS = setOf("nsp", "xci", "nsz", "xcz")

/** A game card's status, and the port options a game carries — pure vocabulary, shared by the layers */
enum class GameStatus {
    NOT_PORTED,
    PORTING,
    DONE,
    NEEDS_BUILD,
}

/**
 * 库里的一张卡：一个游戏（title ID）+ 它的三样东西——数据包、构建工作区、上次构建用的源文件。
 *
 * 身份是 title ID，不是路径：源文件被挪走或删掉时，卡还在（包与工作区还在）。
 */
data class GameEntry(
    val titleId: String,        // 游戏的 title ID（每张卡都有）
    val pack: Ported?,          // 数据包
    val work: File?,            // 构建工作区
    val sourcePath: String?,    // 上次构建用的源文件路径（文件可能已经不在了）
) {
    /** 上次构建用的那个源文件：还在吗 */
    val source: File? get() = sourcePath?.let { File(it) }?.takeIf { it.isFile }

    /** 卡片的稳定标识 */
    val key: String get() = titleId

    /** 卡上的名字：包清单里的游戏名；没有包就用记住的源路径中的文件名 */
    val label: String get() = pack?.name?.trim()?.takeIf { it.isNotEmpty() }
        ?: sourcePath?.let { GameIdentity.displayName(File(it)) }
        ?: titleId

    /**
     * 数据包读的是工作区里的 RomFS（清单的 romfs_at 指进工作区）：工作区是游戏的一部分，
     * 单独清构建缓存会让它跑不起来——那种卡不摆「清除构建缓存」。
     */
    val romfsSharedWithWorkspace: Boolean
        get() = pack != null && GameCleanup.packReadsWorkspace(pack.romfsAt, work)
}


/**
 * 额外地址空间（别名区）：-1 = Auto（不指定，由移植器按自己的规则决定：档案调通过的版本不加，
 * 没调通过的加 8 GB），0~48 GB 为玩家手动指定。它编译进宿主的内存布局，改了要重新构建。
 */
const val ALIAS_AUTO = -1

data class GameConfig(
    val touch: Boolean = false,
    val fps60: Boolean = false,
    val experimental: Boolean = false,
    val aliasHeadroom: Int = ALIAS_AUTO,
)

/**
 * 按游戏配置的存取：JSON 一个游戏一条。纯值，不碰 Android，测试跑在 JVM 上。
 * 旧版本存下的配置没有 "alias" 这一项，读出来是 Auto，构建行为与那时一致。
 */
object GameConfigJson {

    fun encode(c: GameConfig): JSONObject = JSONObject()
        .put("touch", c.touch).put("fps60", c.fps60).put("exp", c.experimental)
        .put("alias", c.aliasHeadroom)

    fun decode(o: JSONObject): GameConfig = GameConfig(
        o.optBoolean("touch"), o.optBoolean("fps60"), o.optBoolean("exp"), o.optInt("alias", ALIAS_AUTO))
}

/** Moves filename-keyed legacy options to title IDs once the library can identify their games. */
object GameConfigMigration {
    fun migrateLegacy(configs: MutableMap<String, GameConfig>, games: List<GameEntry>): Boolean {
        var changed = false
        for (entry in games) {
            if (configs.containsKey(entry.titleId)) continue
            val sourceName = entry.sourcePath?.let { File(it).name } ?: continue
            val config = configs.remove(sourceName) ?: continue
            configs[entry.titleId] = config
            changed = true
        }
        return changed
    }
}

/**
 * 一个完成的移植：数据包是唯一产物（手机线不再产出 APK），宿主程序（libdmplay.so）在包里，App 的
 * 玩家把它拷进私有目录后直接运行。「已完成」以包与宿主齐备为准。
 */
data class Ported(
    val pack: File,        // <App>-data/
    val host: File,        // 包内的宿主（默认 host/libdmplay.so，清单的 "host" 字段同名）
    val icon: File?,       // 包内的图标（icon.png），没有为 null
    val titleId: String,   // 清单里的 title id（存档、私有目录都按它分）
    val name: String = "", // 清单里的游戏名
    val status: String = "", // 清单里的档案状态（playable / wip / boots / experimental）：experimental 的移植提醒玩家它还没被正式调通
    val playerApiRevision: Int = 0, // Missing or invalid revisions require a runtime update.
    val display: String = "", // 清单里的显示版本（游戏自己的版本号，如 1.1.1）：老的数据包没有这一项
    val romfsAt: String = "", // 清单里的 romfs_at：游戏的文件不在包里，在工作区（老的数据包没有这一项：它们自带 RomFS）
) {
    /** The pack has its host: it is a port, not a leftover manifest. */
    val hasHost: Boolean get() = host.isFile

    /** The pack has its host, was built for the current player interface revision, and its game's files are there. */
    val runnable: Boolean get() = host.isFile && playerApiRevision == PlayerApiRevision.CURRENT && !romfsMissing

    /** Missing host or a different/legacy revision requires a runtime update. */
    val needsRuntimeUpdate: Boolean get() = !host.isFile || playerApiRevision != PlayerApiRevision.CURRENT

    /**
     * 包的 RomFS 在工作区里（移植时没有复制一份进包）：那个文件夹没了，游戏就跑不起来 —— 要重新移植。
     */
    val romfsMissing: Boolean get() = romfsAt.isNotEmpty() && !File(romfsAt).isDirectory
}
