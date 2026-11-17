package com.nx.android.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.coroutineContext

/**
 * 占用统计的纯文件部分（spec app-storage-usage「存储」分区）。
 *
 * 这个对象只做四件事：**量一棵目录树**（字节数与文件数）、**认一认这份内容变了没有**
 * （指纹）、**把一次统计的结果存成 JSON**、**决定要不要量**（当天复用）。它不认 Android、
 * 不认界面、也不知道「游戏」是什么——那些在 `appstate/StorageUsageState.kt` 里。所以它能
 * 在 JVM 上直接测。
 *
 * **模型**：一项 = 一个游戏（或那一堆没人管的东西），一项由若干「段」组成，一段 = 一段
 * 目录（工作区或数据包）。每段各有自己的状态与数字：卡不在时卡上那段够不着，它不是 0，
 * 是未知。
 *
 * 三条口径（面板上也要写出来）：
 * - 符号链接只计它自己（不跟随，不把它指向的内容算进来）；
 * - 硬链接按份累加（同一份内容链了几次就算几次）；
 * - 量到一半停下来的（预算触顶或被取消）⇒ 结果是**下界**，标为部分统计，不冒充完整数字。
 */
object StorageUsage {

    /** A short visit to the storage section should not start a directory walk. */
    const val SCAN_GATE_MS = 400L

    /** 一段目录的角色 */
    enum class Role {
        /** 构建工作区（构建缓存） */
        WORKSPACE,

        /** 数据包 */
        PACK,

        /** App 之外的文件（导入的包：它在玩家自己挑的目录里，App 不统计它） */
        OUTSIDE,
    }

    /** 一段的状态 */
    enum class State {
        /** 还没轮到（刚进面板时的骨架） */
        PENDING,
        /** 正在量它 */
        RUNNING,
        /** 量完了，数字完整 */
        DONE,
        /** 只量了一部分：数字是下界 */
        PARTIAL,
        /** 量不了（目录读不到） */
        FAILED,
        /** 够不着（东西在不在手机上的卷不在） */
        UNKNOWN,
    }

    /** 一项里的一段目录 */
    data class Part(
        val role: Role,
        val path: String,
        val bytes: Long = 0L,
        val files: Int = 0,
        val state: State = State.PENDING,
    )

    /** 一项 = 一个游戏，或那一堆没人管的包与残留 */
    data class Item(
        /** 稳定标识（游戏用 title ID 或源文件路径，兜底项固定） */
        val id: String,
        /** 显示名（游戏名；兜底项是「未登记的包与残留」） */
        val name: String,
        /** 封面（包里的 icon.png）：面板上给这一项画个缩略图 */
        val icon: String = "",
        val parts: List<Part> = emptyList(),
        /** 内容指纹：这一项变了没有（变了就重新量） */
        val fingerprint: String = "",
    ) {
        /** 合计：够不着与量不了的那几段不算进来——那是未知，不是 0 */
        val bytes: Long
            get() = parts.filter { it.state == State.DONE || it.state == State.PARTIAL }.sumOf { it.bytes }

        /** 合计的文件数 */
        val files: Int
            get() = parts.filter { it.state == State.DONE || it.state == State.PARTIAL }.sumOf { it.files }

        /** 这一项是不是有个下界之外的东西（够不着 / 量不了 / 只量了一半） */
        val partial: Boolean
            get() = parts.any { it.role != Role.OUTSIDE && (it.state == State.PARTIAL || it.state == State.UNKNOWN || it.state == State.FAILED) }

        /** 这一项是不是导入的（文件在 App 之外，不计入合计） */
        val outside: Boolean
            get() = parts.any { it.role == Role.OUTSIDE }
    }

    /** 一次统计的结果 */
    data class Snapshot(
        val items: List<Item> = emptyList(),
        /** 统计完成的时间（0 = 从未统计过） */
        val scannedAt: Long = 0L,
        /** 统计进行中 */
        val scanning: Boolean = false,
        /**
         * 上一次统计被中途打断了（玩家离开了分区）。
         *
         * 半份结果不算「今天量过」：下次进分区要重新量。它只活在内存里——
         * 没跑完的统计本来就不落盘。
         */
        val interrupted: Boolean = false,
    )

    /** 单项统计的预算：文件数上限 */
    const val MAX_FILES = 500_000

    /** 单项统计的预算：耗时上限（毫秒） */
    const val MAX_MILLIS = 20_000L

    /** 每这么多文件查一次取消：检查本身有开销，节流后可以忽略 */
    private const val CANCEL_CHECK_EVERY = 512

    /** 快照的 schema 版本：读不懂就丢掉重算 */
    const val SCHEMA = "nx2a.usage.v2"

    /** 兜底那一项的标识：占了地方却不对应库里任何游戏的东西 */
    const val ORPHAN_ID = "orphan"

    /** 量一棵树时累加的数（中途被取消时，里面已量到的部分就是下界） */
    class Acc {
        var bytes: Long = 0L
        var files: Int = 0
    }

    /**
     * 量一棵目录树，累加到 [acc] 里。
     *
     * - 目录不存在 ⇒ 什么都没有（[State.DONE]，字节 0）：不是失败；
     * - 根目录读不了 ⇒ [State.FAILED]；
     * - 中间的目录读不了、或统计期间被删 ⇒ 跳过，整体是下界（[State.PARTIAL]）；
     * - 触达预算（文件数或耗时）⇒ 停下，下界（[State.PARTIAL]）；
     * - 协程被取消 ⇒ 抛出取消异常，调用方把 [acc] 里已量到的部分留下当下界。
     */
    suspend fun measure(
        dir: File,
        acc: Acc,
        maxFiles: Int = MAX_FILES,
        maxMillis: Long = MAX_MILLIS,
    ): State {
        if (!dir.exists()) return State.DONE
        val root = try {
            dir.toPath()
        } catch (e: Exception) {
            return State.FAILED
        }
        val deadline = System.nanoTime() + maxMillis * 1_000_000L
        val pending = ArrayDeque<Path>()
        pending.addLast(root)
        var partial = false       // 有一处读不了：数字是下界
        var budgetHit = false     // 触了预算：停下，数字是下界
        while (pending.isNotEmpty() && !budgetHit) {
            coroutineContext.ensureActive()
            val current = pending.removeLast()
            val entries: List<Path> = try {
                Files.newDirectoryStream(current).use { it.toList() }
            } catch (e: IOException) {
                if (current == root) return State.FAILED
                // 中间的目录读不了（被删了或没权限）：跳过它，其余照量，整体是下界
                partial = true
                continue
            }
            for (path in entries) {
                if (acc.files >= maxFiles || System.nanoTime() >= deadline) {
                    budgetHit = true
                    break
                }
                val attrs = try {
                    Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (e: IOException) {
                    // 量的时候被删掉了或读不了：这一项跳过，整体是下界
                    partial = true
                    continue
                }
                when {
                    attrs.isDirectory -> pending.addLast(path)
                    attrs.isRegularFile -> {
                        acc.bytes += attrs.size()
                        acc.files++
                    }
                    // 符号链接：只计它自己（NOFOLLOW_LINKS 下的 size 是链接自身的长度）
                    attrs.isSymbolicLink -> {
                        acc.bytes += attrs.size()
                        acc.files++
                    }
                    // 设备节点 / 管道等：不计
                    else -> Unit
                }
                if (acc.files % CANCEL_CHECK_EVERY == 0) coroutineContext.ensureActive()
            }
        }
        return if (partial || budgetHit) State.PARTIAL else State.DONE
    }

    /**
     * 这一段目录的内容指纹：顶层有哪些东西（名字 + 类型）。
     *
     * 只看顶层，而且是**名字**：新增或删掉一个数据包、一个构建目录，改的正是那一层，
     * 顶层变了就是「多了一份没统计过的东西」。底下几十万文件的改动不惊动它——那不是
     * 「多了一份」，当天不必重量（玩家想看最新的可以点「重新统计」）。
     *
     * 目录不存在或读不了 ⇒ 空指纹。
     */
    fun fingerprintOf(dir: File): String {
        val children = dir.listFiles() ?: return ""
        val sb = StringBuilder()
        for (c in children.sortedBy { it.name }) {
            sb.append(c.name).append(if (c.isDirectory) "/;" else ";")
        }
        return sb.toString()
    }

    /** 这个时刻是不是今天（设备本地时区） */
    fun isToday(at: Long): Boolean {
        if (at <= 0L) return false
        val zone = java.time.ZoneId.systemDefault()
        val day = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val today = java.time.Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone).toLocalDate()
        return day == today
    }

    /**
     * 一天里这一项的指纹：它各段的指纹拼起来。
     *
     * 够不着的那段（卡不在）没有指纹可言，跳过它。
     */
    suspend fun itemFingerprint(item: Item, fingerprintOf: suspend (File) -> String): String {
        val sb = StringBuilder()
        for (p in item.parts) {
            if (p.state == State.UNKNOWN) continue
            sb.append(p.role.name).append(':').append(p.path).append('=')
            sb.append(fingerprintOf(File(p.path))).append('|')
        }
        return sb.toString()
    }

    /**
     * 存下来的指纹拆回「哪一段 → 什么名字」。
     *
     * 存的格式是一段一段的：`角色:路径=名字;名字2;|角色:路径=名字3;|`。
     */
    private fun parseFingerprint(fp: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (chunk in fp.split('|')) {
            if (chunk.isEmpty()) continue
            val eq = chunk.indexOf('=')
            if (eq <= 0) continue
            out[chunk.substring(0, eq)] = chunk.substring(eq + 1)
        }
        return out
    }

    /**
     * 这一项的内容还是不是上次量过的那一份。
     *
     * **只比够得着的那几段**：卡不在时卡上那一段算不出指纹，拿它去比存下来的
     * 指纹只会永远对不上 ⇒ 玩家每次进分区都要把工作区重量一遍。够不着的那段
     * 跳过就好（它本来也没有数字可对）。
     *
     * 存的那份里多出来的段（玩家把数据包删了）不比：它已经不在清单上了。
     */
    suspend fun fingerprintMatches(
        item: Item,
        stored: String,
        fingerprintOf: suspend (File) -> String,
    ): Boolean {
        if (stored.isEmpty()) return false
        val storedParts = parseFingerprint(stored)
        for (p in item.parts) {
            if (p.state == State.UNKNOWN) continue      // 够不着：无从比起
            val key = p.role.name + ":" + p.path
            val have = storedParts[key] ?: return false  // 存的那份里没有这一段 ⇒ 清单变了
            if (have != fingerprintOf(File(p.path))) return false
        }
        return true
    }

    /**
     * 量一遍（或者不量），量完落盘。
     *
     * - 当天量过、且量的是现在这份内容 ⇒ 不遍历，直接用落盘的那份；
     * - 跑完了才写文件：半份数字不算「今天量过」（被取消时不写）；
     * - 逐段发布：量完一段就发一次（[publish]），不等全部量完。
     *
     * [measureItem] 是量一段的动作，[fingerprintOf] 是认一份内容指纹的动作（两个都由调用
     * 方放到后台线程上），[target] 是落盘的位置。
     */
    suspend fun scanAndSave(
        snapshot: Snapshot,
        target: File,
        force: Boolean = false,
        publish: (Snapshot) -> Unit = {},
        fingerprintOf: suspend (File) -> String = { StorageUsage.fingerprintOf(it) },
        measureItem: suspend (File, Acc) -> State,
    ): Snapshot {
        if (!force && !snapshot.interrupted && snapshot.scannedAt > 0L && isToday(snapshot.scannedAt)) {
            // 今天量过：看看量的是不是现在这份内容（清单里多了一个游戏 / 一份包 ⇒ 要重量）
            var same = true
            for (item in snapshot.items) {
                if (item.parts.any { it.state == State.PENDING }) { same = false; break }
                if (!fingerprintMatches(item, item.fingerprint, fingerprintOf)) { same = false; break }
            }
            if (same) return snapshot
        }
        // 重新统计时旧数字先作废：这一轮的合计只由这一轮量出来的数累加，
        // 上一轮的数字不冒充当前占用（够不着的那几段本来就没有数字，不动）。
        var current = snapshot.resetMeasured().copy(scanning = true)
        publish(current)
        try {
            for (item in current.items) {
                for (p in item.parts) {
                    // 够不着的东西不量（卡不在）：如实标着「够不着，大小未知」
                    if (p.state == State.UNKNOWN) continue
                    // 开量这一段：先标「统计中」，面板上那一段跟着动
                    current = current.withPart(item.id, p.role, p.path) { it.copy(state = State.RUNNING) }
                    publish(current)
                    val acc = Acc()
                    val state = try {
                        measureItem(File(p.path), acc)
                    } catch (e: CancellationException) {
                        // 面板被关掉了：把已经测出的那一段作为下界留下（半份，不落盘）
                        current = current.withPart(item.id, p.role, p.path) {
                            it.copy(bytes = acc.bytes, files = acc.files, state = State.PARTIAL)
                        }
                        publish(current)
                        throw e
                    }
                    current = current.withPart(item.id, p.role, p.path) {
                        it.copy(bytes = acc.bytes, files = acc.files, state = state)
                    }
                    publish(current)
                }
                val fp = itemFingerprint(item, fingerprintOf)
                current = current.withFingerprint(item.id, fp)
                publish(current)
            }
            current = current.copy(scannedAt = System.currentTimeMillis(), scanning = false)
            publish(current)
            write(target, current)
            return current
        } catch (e: CancellationException) {
            // 半份结果不算「今天量过」：下次进分区要重新量（这一版只活在内存里）
            current = current.copy(scanning = false, interrupted = true)
            publish(current)
            throw e
        } catch (e: Exception) {
            current = current.copy(scanning = false)
            publish(current)
            throw e
        }
    }

    /**
     * 换掉一项里的某一段（进度发布）。
     *
     * **每次都换新的列表**：发布出去的快照必须是不可变的值。挂着一个还在被改的
     * 列表会让「这一版比上一版新」这件事看不出来（两个快照看起来一样），界面于是
     * 少更新一处——合计停在旧数上，各行却是新数。
     */
    private fun Snapshot.withPart(id: String, role: Role, path: String, transform: (Part) -> Part): Snapshot =
        copy(items = items.map { item ->
            if (item.id != id) item
            else item.copy(parts = item.parts.map { p ->
                if (p.role == role && p.path == path) transform(p) else p
            })
        })

    /** 把要量的几段退回「未统计」（重新统计时旧数字先作废） */
    private fun Snapshot.resetMeasured(): Snapshot =
        copy(items = items.map { item ->
            item.copy(parts = item.parts.map { p ->
                if (p.state == State.UNKNOWN) p else p.copy(bytes = 0L, files = 0, state = State.PENDING)
            })
        })

    /** 换掉一项的指纹 */
    private fun Snapshot.withFingerprint(id: String, fingerprint: String): Snapshot =
        copy(items = items.map { if (it.id == id) it.copy(fingerprint = fingerprint) else it })

    /** 把一次统计写成 JSON（落盘用） */
    fun render(snapshot: Snapshot): String {
        val root = JSONObject()
        root.put("schema", SCHEMA)
        root.put("measuredAt", snapshot.scannedAt)
        val items = JSONArray()
        snapshot.items.forEach { item ->
            val parts = JSONArray()
            item.parts.forEach { p ->
                parts.put(JSONObject()
                    .put("role", p.role.name)
                    .put("path", p.path)
                    .put("bytes", p.bytes)
                    .put("files", p.files)
                    .put("state", p.state.name))
            }
            items.put(JSONObject()
                .put("id", item.id)
                .put("name", item.name)
                .put("icon", item.icon)
                .put("fp", item.fingerprint)
                .put("parts", parts))
        }
        root.put("items", items)
        return root.toString()
    }

    /** 读回落盘的统计（读不懂就当作没有） */
    fun parse(text: String): Snapshot? = try {
        val root = JSONObject(text)
        if (root.optString("schema") != SCHEMA) null
        else {
            val items = mutableListOf<Item>()
            val array = root.optJSONArray("items") ?: JSONArray()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { it.isNotEmpty() } ?: continue
                val parts = mutableListOf<Part>()
                val partsArray = o.optJSONArray("parts") ?: JSONArray()
                for (j in 0 until partsArray.length()) {
                    val po = partsArray.optJSONObject(j) ?: continue
                    val role = runCatching { Role.valueOf(po.optString("role")) }.getOrDefault(Role.PACK)
                    parts += Part(
                        role = role,
                        path = po.optString("path"),
                        bytes = po.optLong("bytes"),
                        files = po.optInt("files"),
                        state = runCatching { State.valueOf(po.optString("state")) }.getOrDefault(State.DONE),
                    )
                }
                items += Item(
                    id = id,
                    name = o.optString("name").ifEmpty { id },
                    icon = o.optString("icon"),
                    parts = parts,
                    fingerprint = o.optString("fp"),
                )
            }
            Snapshot(items = items, scannedAt = root.optLong("measuredAt"))
        }
    } catch (e: Exception) {
        null
    }

    /** 把一次统计写到磁盘（读不懂就当作没有） */
    fun write(target: File, snapshot: Snapshot) {
        runCatching { target.writeText(render(snapshot)) }
    }

    /** 从磁盘读回一次统计（没有就 null） */
    fun read(target: File): Snapshot? {
        if (!target.isFile) return null
        val text = runCatching { target.readText() }.getOrNull() ?: return null
        return parse(text)
    }
}

/** Storage statistics operations consumed by the storage feature and replaceable in JVM tests. */
interface StorageUsageController {
    val snapshot: StorageUsage.Snapshot
    fun load()
    suspend fun prepare()
    suspend fun scan(force: Boolean = false)
}
