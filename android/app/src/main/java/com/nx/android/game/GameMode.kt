package com.nx.android.game

/**
 * Android 的游戏模式（Game Mode）：系统给 App 的档位（标准 / 性能）。
 *
 * 上游 #294 的规则：**只有宿主自己画的那些游戏**走性能档的节奏策略 —— NVN 与 Vulkan 的游戏是宿主
 * 的重放在画（宿主管得了帧的节奏），OpenGL ES 的游戏是手机自己的驱动在画（宿主管不着，系统自己的
 * 干预留着）。上游把它做成**每个移植一个 APK** 的旗标（只给 NVN 的移植），本线一个 App 管所有游戏，
 * 所以按游戏的图形接口来分：能管的转发给宿主，不能管的转发 0（宿主用默认的节奏）。
 *
 * 老的数据包没有 graphics 这一项（空 = 不知道）→ 不转发（保守）✓。
 */
object GameMode {

    /** 宿主自己画的那些游戏的图形接口（清单里的 graphics）：重放画它们，节奏归宿主管 */
    private val HOST_DRAWN = setOf("nvn", "vulkan")

    /**
     * 系统报的档位与游戏的图形接口 → 转发给宿主的档位。
     *
     * 系统报的：2 = 性能（GameManager.GAME_MODE_PERFORMANCE），1 = 标准，-1 = 这台设备不支持。
     * 宿主那边只认 2（perf_android.c 的策略：性能档里游戏自己的周期说话）✓。
     */
    fun forward(systemMode: Int, graphics: String): Int =
        if (graphics.lowercase() in HOST_DRAWN) systemMode else 0
}
