package com.nx.android.input

/**
 * 谁玩谁：这个游戏里哪只手柄是几号玩家。
 *
 * 存的是手柄的**指纹**（[PadProfileKey] 的 key），不是设备 id：拔了再插，指纹不变，还是那个玩家。
 *
 * 玩家 1 永远是手机：触屏的虚拟按键与手机的体感都是它的，它再接手柄。
 * 它的手柄默认是「自动」——**没被指派走的手柄都算玩家 1**（跟今天一样：单人游戏插两只手柄都能玩）；
 * 也可以锁死某一只（第三只手柄就不来掺和了）。
 * 玩家 2~4 各自一只手柄，也可以空着。
 *
 * 一只手柄只能属于一个玩家：把它指派给谁，它就从原来那个人手里挪走。
 */
data class PadAssignment(
    /** 玩家 1 的手柄："" 是自动（没指派的都算玩家 1），否则是那只手柄的指纹。 */
    val p1: String = "",
    val p2: String = "",
    val p3: String = "",
    val p4: String = "",
) {

    /** 某个玩家手里那只手柄的指纹（0 是玩家 1）。 */
    fun padOf(player: Int): String = when (player) {
        0 -> p1
        1 -> p2
        2 -> p3
        3 -> p4
        else -> ""
    }

    /**
     * 这只手柄（指纹）当几号玩家：0~3 是玩家 1~4，**-1 是闲置**（谁都不算，它按了也没人管）。
     *
     * 玩家 1 自动的时候，没被指派走的手柄都是玩家 1 的；锁死某一只之后，别的手柄就闲置了。
     */
    fun playerOf(key: String): Int {
        if (key.isEmpty()) return -1
        for (p in 1..3) if (padOf(p) == key) return p
        if (p1.isEmpty()) return 0   // 自动：没指派的手柄都算玩家 1
        return if (p1 == key) 0 else -1
    }

    /** 指派一只手柄给某个玩家（它从原来那个玩家手里挪走；"" 是「没有手柄」）。 */
    fun assign(player: Int, key: String): PadAssignment {
        if (player !in 0..3) return this
        val slots = arrayOf(p1, p2, p3, p4)
        for (i in slots.indices) if (i != player && slots[i] == key) slots[i] = ""
        slots[player] = key
        return PadAssignment(slots[0], slots[1], slots[2], slots[3])
    }
}
