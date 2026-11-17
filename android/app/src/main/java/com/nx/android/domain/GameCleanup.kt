package com.nx.android.domain

import java.io.File
import java.util.Locale

/**
 * 一个游戏留在手机上的构建数据：数据包（移植成功时）、工作区（移植器解包重写的地方，几个 GB）、
 * 半成品包（打包阶段被打断留下的）、统一目录里那份运行库（runtimes/<title ID>，唯一正本，不删
 * 就是永久孤儿）。删一个游戏就是删这些——游戏源文件是玩家自己的，不碰。
 *
 * 纯文件操作，不依赖 Android，测试跑在 JVM 上。
 */
object GameCleanup {

    /** 删除一个游戏的结果：删掉了、被拒（正在构建 / 正在运行 / 正在搬移 / 工作区在用）、失败 */
    enum class Outcome { DONE, BUSY_PORTING, BUSY_RUNNING, BUSY_MOVING, WORKSPACE_IN_USE, FAILED }

    data class Result(
        val outcome: Outcome,
        val workspaceKnown: Boolean = true,  // 工作区知不知道在哪（文件名里没有 title ID 就不知道）
        val failed: List<File> = emptyList(),
    )

    /** 要删的东西 */
    enum class Kind { PACK, WORKSPACE, PARTIAL, RUNTIME }

    data class Target(val file: File, val kind: Kind)

    /**
     * 一次删除的计划：删哪些东西、工作区知不知道在哪。
     *
     * [workspaceKnown] 为 false 是因为游戏文件名里没有 title ID（多半是下载器改过名），
     * 移植器按 title ID 命名的工作区就找不到了——这时要说实话，不能假装删干净了。
     *
     * [workspaceInUse] 是因为数据包读的是工作区里的 RomFS（移植时没有复制一份进包）：
     * 单独删工作区会让游戏跑不起来，这时只能整个删。
     */
    data class Plan(
        val targets: List<Target>,
        val workspaceKnown: Boolean,
        val workspaceInUse: Boolean = false,
    )

    /** 删完的结果：什么没删掉 */
    data class Deleted(
        val failed: List<File>,
        val workspaceKnown: Boolean,
    )

    /**
     * 这个游戏要删的东西。
     *
     * @param titleId 这个游戏的 title ID（文件名里的，或数据包清单里的）；null = 不知道工作区在哪
     * @param packs 这个游戏已完成的数据包（每个卷上可能各有一份：私有的、可移动卡上的）
     * @param keepPartials 留着半成品包：另一个构建正在写它，删了就把那次构建杀了
     * @param workspaceInUse 数据包读的是工作区里的 RomFS：单独删工作区会让游戏跑不起来
     */
    fun plan(titleId: String?, packs: List<File>, workspace: File?, partials: List<File>,
             workspaceInUse: Boolean = false, runtime: File? = null): Plan {
        val targets = ArrayList<Target>()
        packs.forEach { targets += Target(it, Kind.PACK) }
        workspace?.let { targets += Target(it, Kind.WORKSPACE) }
        partials.forEach { targets += Target(it, Kind.PARTIAL) }
        runtime?.let { targets += Target(it, Kind.RUNTIME) }
        return Plan(targets, workspaceKnown = titleId != null, workspaceInUse = workspaceInUse)
    }

    /** 工作区：<work>/<title ID>_build（移植器按 title ID 建的目录）；没有 title ID 就不知道它在哪 */
    fun workspaceOf(titleId: String?, workRoot: File): File? {
        if (titleId.isNullOrBlank()) return null
        return File(workRoot, "${titleId.uppercase(Locale.US)}_build")
    }

    /**
     * 数据包读的是工作区里的 RomFS（清单的 romfs_at 指进工作区，移植时没有复制一份进包）：
     * 那个文件夹是游戏的一部分，单独清掉工作区游戏就跑不起来了。
     *
     * 老的包自带 RomFS（没有 romfs_at）：工作区随便删。
     */
    fun packReadsWorkspace(romfsAt: String, workspace: File?): Boolean {
        if (romfsAt.isEmpty() || workspace == null) return false
        return File(romfsAt).toPath().startsWith(workspace.toPath())
    }


}
