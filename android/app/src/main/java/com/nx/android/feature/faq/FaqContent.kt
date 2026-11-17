package com.nx.android.feature.faq

import android.content.Context
import com.nx.android.data.AppLocale
import org.json.JSONArray

/** 一条问答：问题与答案都是明文（内容在 assets 的 JSON 里，不在代码里也不在资源里） */
data class FaqEntry(val question: String, val answer: String)

/** 一组问答：[key] 是稳定的（测试拿它对齐两份文件），[title] 与问答都是本地化的 */
data class FaqGroup(val key: String, val title: String, val entries: List<FaqEntry>)

/**
 * 常见问题的内容。
 *
 * 内容是数据：`assets/faq/faq_zh.json` 与 `assets/faq/faq_en.json`，问答成对放着，
 * 改内容只改 JSON，不动代码。页面按 App 的界面语言挑一份。
 */
object FaqContent {

    /** 内容文件：玩家选的语言；跟着系统时看手机自己的语言 */
    fun fileFor(mode: AppLocale.Mode, deviceLanguage: String): String =
        when (mode) {
            AppLocale.Mode.ZH -> "faq/faq_zh.json"
            AppLocale.Mode.EN -> "faq/faq_en.json"
            AppLocale.Mode.SYSTEM ->
                if (deviceLanguage == "zh") "faq/faq_zh.json" else "faq/faq_en.json"
        }

    /**
     * 读那份内容并解析。
     *
     * 读不到或坏掉都只给一个空列表：页面照常打开（上面那张 GitHub 卡片还在），
     * 不因为内容文件出事而崩。
     */
    fun load(context: Context, path: String): List<FaqGroup> = runCatching {
        context.assets.open(path).bufferedReader().use { parse(it.readText()) }
    }.getOrDefault(emptyList())

    /** 解析：文本 → 组。空文本或坏 JSON 给一个空列表，不抛异常 */
    fun parse(json: String): List<FaqGroup> = runCatching {
        val array = JSONArray(json)
        val groups = ArrayList<FaqGroup>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val items = obj.optJSONArray("items")
            val entries = ArrayList<FaqEntry>(items?.length() ?: 0)
            if (items != null) {
                for (j in 0 until items.length()) {
                    val item = items.optJSONObject(j) ?: continue
                    val q = item.optString("q").trim()
                    val a = item.optString("a").trim()
                    // 少一边的问答不算问答：跳过，不给玩家看半句
                    if (q.isEmpty() || a.isEmpty()) continue
                    entries.add(FaqEntry(q, a))
                }
            }
            groups.add(FaqGroup(obj.optString("group"), obj.optString("title"), entries))
        }
        groups
    }.getOrDefault(emptyList())
}
