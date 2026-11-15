package com.nx.android.data.firmware

import android.content.Context
import com.nx.android.data.PrefsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 固件：整机级的一份（模拟器的固件包：一个 zip，根目录下一堆 .nca）。
 *
 * 玩家在环境清单（顶栏那个胶囊）、设置页的环境一节、引导或构建页选这个 zip，这里把它解开到
 * App 的私有目录（files/firmware/，nca 直接躺在这一层 —— 移植器要的「系统内容文件夹」就是它，
 * nx_extract 只扫一层）。
 *
 * 移植时命令行带 --firmware 指着那个目录；没导入就没有这一项 —— 固件是**可选**的，
 * 只有读主机系统数据的游戏（Mii 之类）才用得到。
 */
@Singleton
class FirmwareRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val prefs: PrefsStore,
) {
    /** 解开后的目录：App 私有目录下的 firmware/ */
    val dir: File get() = File(ctx.filesDir, "firmware")

    /** 已导入的固件（zip 的名字）：空 = 没导入 */
    val imported: String get() = prefs.str(PrefsStore.FIRMWARE)

    /** 移植器要的文件夹：导入过、里面确实有 nca 才有 */
    val folder: String? get() = dir.takeIf { d ->
        d.isDirectory && d.listFiles()?.any { it.name.endsWith(".nca") } == true
    }?.absolutePath

    /** 解开 zip 到私有目录：旧的先清掉，写出的文件数是返回值 */
    fun unpack(zip: File): Int {
        val target = dir
        target.deleteRecursively()
        target.mkdirs()
        val n = unpackZip(zip, target)
        prefs.put(PrefsStore.FIRMWARE, zip.name)
        return n
    }

    /** 忘掉固件：私有目录里的那一份删掉 */
    fun clear() {
        dir.deleteRecursively()
        prefs.put(PrefsStore.FIRMWARE, "")
    }
}

/**
 * 解开 zip 到 target：每个条目只取文件名的最后一段（目录结构被摊平，移植器只扫一层），
 * 路径里带 ".." 或以 "/" 开头的条目直接丢掉（哪儿也去不了），返回写出的文件数。
 * 纯函数：测试在 JVM 上跑。
 */
fun unpackZip(zip: File, target: File): Int {
    var n = 0
    ZipFile(zip).use { z ->
        val entries = z.entries()
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            if (e.isDirectory) continue
            val raw = e.name
            if (raw.startsWith("/") || raw.split('/').any { it == ".." }) continue
            val name = raw.substringAfterLast('/')
            if (name.isEmpty() || name == "." || name == "..") continue
            val out = File(target, name)
            z.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            n++
        }
    }
    return n
}
