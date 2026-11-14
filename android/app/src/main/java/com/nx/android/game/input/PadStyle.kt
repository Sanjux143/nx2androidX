package com.nx.android.game.input

/**
 * 虚拟手柄的按键风格。
 *
 * 自带：数据包自带的主题贴图（游戏专属的那套，或它自己的默认包）。
 * 默认：app 里的默认主题包（ergo，淡蓝半透明那副——实验性移植与没有专属主题的游戏就是它）。
 *
 * 风格只改「画什么」，不改「在哪、多大、命中哪」——命中判定来自宿主给的布局，与贴图无关，
 * 所以换风格不动任何输入语义。
 */
enum class PadStyle(val key: String) {
    /** 数据包自带的主题贴图 */
    PACK("pack"),
    /** app 里的默认主题包（ergo） */
    DEFAULT("default");

    companion object {
        /** 偏好键的前缀，后面接 title ID（每个游戏各记一份） */
        const val KEY_PREFIX = "pad.style"

        /** 按游戏的偏好键：同一个游戏记得自己选过的风格 */
        fun key(titleId: String?): String =
            if (titleId.isNullOrEmpty()) KEY_PREFIX else "$KEY_PREFIX.$titleId"

        /** 偏好里存的值 → 风格；认不出来的（没存过、坏数据）回落到自带 */
        fun of(value: String?): PadStyle = entries.firstOrNull { it.key == value } ?: PACK
    }
}
