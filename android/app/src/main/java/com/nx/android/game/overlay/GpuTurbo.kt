package com.nx.android.game.overlay

/**
 * GPU 提频的三档（宿主那边是 perf_android.c 的 DM_GPU_TURBO）：
 * 关（调频器自己的频率）、一直跑最高频、只在掉帧时拉高。
 *
 * 宿主每 60 个 present 重读一次那个环境变量，所以改完即时生效，不用重启游戏。
 */
object GpuTurbo {
    /** 关：GPU 用调频器给的频率。 */
    const val OFF = "0"

    /** 一直最高频：更凉快也更快，更热更费电。 */
    const val ON = "1"

    /** 掉帧才拉高：画面跟不上时升到最高频，闲下来自己降回去。 */
    const val AUTO = "auto"

    /** 三档的顺序：关、在、掉帧时。抽屉里的下拉按这个顺序。 */
    val MODES = listOf(OFF, ON, AUTO)

    /** 宿主给的值归一化：认不出来的一律当「关」（宿主那边也是这个意思）。 */
    fun normalize(raw: String?): String = when (raw) {
        ON -> ON
        AUTO -> AUTO
        else -> OFF
    }
}
