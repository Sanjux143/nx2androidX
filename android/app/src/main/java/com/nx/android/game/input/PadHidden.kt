package com.nx.android.game.input

/**
 * 藏起来的控件：玩家在布局编辑器里长按某��键把它藏起来，对局里它不出现、那片触摸归游戏。
 *
 * 藏起来的键仍然在控件表里（只是带一个 hidden 标志），所以它随时找得回来：
 * 编辑器里它画成半透明的幽灵，点一下就恢复。
 *
 * 名单存在 app 自己的偏好里（每个游戏各记一份，与「显示手柄」「按键风格」同一份文件）：
 * 那是「这台设备上玩家怎么玩这个游戏」的事，不进数据包的 settings.txt。
 */
object PadHidden {

    /** 偏好键的前缀，后面接 title ID（每个游戏各记一份） */
    const val KEY_PREFIX = "pad.hidden"

    /** 按游戏的偏好键：同一个游戏记得自己藏了哪些键 */
    fun key(titleId: String?): String =
        if (titleId.isNullOrEmpty()) KEY_PREFIX else "$KEY_PREFIX.$titleId"

    /** 偏好里存的值（逗号分隔的控件名）→ 名单；没存过或坏数据就是空名单 */
    fun parse(value: String?): Set<String> {
        if (value.isNullOrEmpty()) return emptySet()
        return value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    /** 名单 → 偏好里存的值（空的名单存空串） */
    fun format(names: Set<String>): String =
        names.filter { it.isNotEmpty() }.sorted().joinToString(",")
}
