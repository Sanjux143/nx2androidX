package com.nx.android.input

/**
 * 游戏自己的按键：实体手柄的一个物理键能绑到哪个游戏键上（宿主那边的 DM_BTN_*，一位一个）。
 *
 * 名字就是宿主认的词（小写），推给宿主的映射表里也用它们。
 */
object PadTargets {

    /** 展示与绑定的顺序：面键、肩键、扳机、摇杆、十字键。 */
    val ALL: List<String> = listOf(
        "a", "b", "x", "y",
        "l", "r", "zl", "zr",
        "ls", "rs",
        "plus", "minus",
        "up", "down", "left", "right",
    )

    /** 这个词是一个游戏键吗（推给宿主前先过这一关，脏数据不许进表）。 */
    fun isValid(target: String): Boolean = target in ALL

    /** 游戏键在宿主那边是第几位（推给宿主的是名字，这里是位；宿主自己按名字查）。 */
    fun indexOf(target: String): Int = ALL.indexOf(target)
}
