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
 *
 * 导入的包是另一回事：它的身份是**它自己的路径**（[imported]），同一个游戏因此可以在库里
 * 有一份 App 自己做的和一份导入的，两份各跑各的。
 */
data class GameEntry(
    val titleId: String,        // 游戏的 title ID（每张卡都有）
    val pack: Ported?,          // 数据包
    val work: File?,            // 构建工作区
    val sourcePath: String?,    // 上次构建用的源文件路径（文件可能已经不在了）
    val imported: PortablePack.Imported? = null,   // 导入的包：路径就是它的身份
) {
    /** 上次构建用的那个源文件：还在吗 */
    val source: File? get() = sourcePath?.let { File(it) }?.takeIf { it.isFile }

    /** 这张卡是导入的（不是 App 自己移植的） */
    val isImported: Boolean get() = imported != null

    /** 卡片的稳定标识：导入的卡按自己的路径认，本地的卡按 title ID 认 */
    val key: String get() = imported?.path ?: titleId

    /** 卡上的名字：包清单里的游戏名；导入的卡用登记时记下的名字；没有包就用记住的源路径中的文件名 */
    val label: String get() = pack?.name?.trim()?.takeIf { it.isNotEmpty() }
        ?: imported?.name?.trim()?.takeIf { it.isNotEmpty() }
        ?: sourcePath?.let { GameIdentity.displayName(File(it)) }
        ?: titleId

    /**
     * 数据包读的是工作区里的 RomFS（清单的 romfs_at 指进工作区）：工作区是游戏的一部分，
     * 单独清构建缓存会让它跑不起来——那种卡不摆「清除构建缓存」。
     */
    val romfsSharedWithWorkspace: Boolean
        get() = pack != null && GameCleanup.packReadsWorkspace(pack.romfsAt, work)

    /** 游戏的文件就在包里（导入的文件夹自带 RomFS/；老的 PC 工具出的包也是） */
    val romfsInside: Boolean
        get() = pack != null && File(pack.pack, PortablePack.ROMFS).isDirectory
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
    /** 玩家自己的系统字体（.ttf 的路径）：空 = 用 App 自带的那份（包里带 CJK 字形） */
    val sharedFont: String = "",
    /** 多线程回放：游戏的 Vulkan 命令录到自己的线程上（热闹场景里更快；要关掉得重新移植） */
    val mtReplay: Boolean = false,
)

/**
 * 按游戏配置的存取：JSON 一个游戏一条。纯值，不碰 Android，测试跑在 JVM 上。
 * 旧版本存下的配置没有 "alias" 这一项，读出来是 Auto，构建行为与那时一致；
 * "font" / "mt" 同样是新加的，老配置读出来是"没填 / 关"。
 * 固件不是按游戏的：它是整机级的一份（环境里导入，移植时统一带上），所以不在这里。
 */
object GameConfigJson {

    fun encode(c: GameConfig): JSONObject = JSONObject()
        .put("touch", c.touch).put("fps60", c.fps60).put("exp", c.experimental)
        .put("alias", c.aliasHeadroom)
        .put("font", c.sharedFont).put("mt", c.mtReplay)

    fun decode(o: JSONObject): GameConfig = GameConfig(
        o.optBoolean("touch"), o.optBoolean("fps60"), o.optBoolean("exp"), o.optInt("alias", ALIAS_AUTO),
        o.optString("font", ""), o.optBoolean("mt", false))
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
 *
 * 新格式的包（清单没有 host 字段）：运行库不在包里——它在 App 私有统一目录（runtimes/<title ID 小写>），
 * 由目录侧的构建标记（build.json）说清这份运行库是按什么建的。旧格式（清单带 host）照旧可用，可在
 * 首次启动时晋升拷贝进统一目录。
 */
data class Ported(
    val pack: File,        // <App>-data/
    val host: File,        // 包内的宿主（默认 host/libdmplay.so，清单的 "host" 字段同名）；新格式的包里没有它
    val icon: File?,       // 包内的图标（icon.png），没有为 null
    val titleId: String,   // 清单里的 title id（存档、私有目录都按它分）
    val name: String = "", // 清单里的游戏名
    val status: String = "", // 清单里的档案状态（playable / wip / boots / experimental）：experimental 的移植提醒玩家它还没被正式调通
    val playerApiRevision: Int = 0, // Missing or invalid revisions require a runtime update.
    val display: String = "", // 清单里的显示版本（游戏自己的版本号，如 1.1.1）：老的数据包没有这一项
    val romfsAt: String = "", // 清单里的 romfs_at：游戏的文件不在包里，在工作区（老的数据包没有这一项：它们自带 RomFS）
    val graphics: String = "", // 清单里的 graphics：游戏自己用的图形接口（vulkan / gl / nvn，空 = 不知道；老的数据包没有这一项）
    val lan: Boolean? = null, // 清单里的 lan：游戏能不能局域联机（null = 清单里没有这一项：老的数据包，由档案补上）
    val hostDeclared: Boolean = true, // 清单里声明了 host（= 旧格式，运行库在包里；没有这个字段 = 新格式，运行库在统一目录）。默认 true：直接构造的场合按旧格式对待（包里带着宿主）
    val noLsfg: Boolean? = null, // 清单里的 no_lsfg（构建指纹的一部分；老的数据包没有这一项）
    val profileH: String = "", // 清单里的 profile_h（这个包的运行库按哪份档案编的）：与目录侧标记比对
    val marker: RuntimeBuildMarker? = null, // 统一目录侧的构建标记（新格式才有；启动判断只读目录侧）
) {
    /** The pack has its runtime (a legacy pack's host file in place; a new-format one its folder marker): it is a port, not a leftover manifest. */
    val hasHost: Boolean get() = if (hostDeclared) host.isFile else marker != null

    /**
     * The pack can run: an old-format pack needs its host (built for the current player interface) inside;
     * a new-format one needs the runtime folder's build marker for the current interface. The game's files
     * have to be there either way.
     */
    val runnable: Boolean get() = !romfsMissing && when {
        hostDeclared -> host.isFile && playerApiRevision == PlayerApiRevision.CURRENT
        else -> marker != null && marker.playerApiRevision == PlayerApiRevision.CURRENT
    }

    /** Missing host or a different/legacy revision requires a runtime update. */
    val needsRuntimeUpdate: Boolean get() = when {
        hostDeclared -> !host.isFile || playerApiRevision != PlayerApiRevision.CURRENT
        else -> marker == null || marker.playerApiRevision != PlayerApiRevision.CURRENT
    }

    /**
     * 包的 RomFS 在工作区里（移植时没有复制一份进包）：那个文件夹没了，游戏就跑不起来 —— 要重新移植。
     */
    val romfsMissing: Boolean get() = romfsAt.isNotEmpty() && !File(romfsAt).isDirectory
}

/**
 * 统一目录侧的构建标记（build.json）：这份运行库是按什么建的。启动前的指纹比对只读目录侧——
 * 目录升格为正本后，版本跟着正本走（旧包晋升时这份标记由 App 自己写）。
 */
data class RuntimeBuildMarker(
    val playerApiRevision: Int,
    val profileH: String,
    val noLsfg: Boolean?,   // 旧包晋升出来的运行库不知道这一项（null = 没记，不参与比对）
)
