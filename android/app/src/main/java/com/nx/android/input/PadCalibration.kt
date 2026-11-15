package com.nx.android.input

/**
 * 一只手柄的校准：摇杆的死区与扳机的阈值。
 *
 * 默认值就是宿主一直在用的那两个（死区 0.12、扳机 0.5）——没设置过时行为与改动前一致。
 */
data class PadCalibration(
    val deadzone: Float = DEFAULT_DEADZONE,
    val trigger: Float = DEFAULT_TRIGGER,
) {
    /** 玩家动过吗（没动过就不必写进档案，宿主那边用默认值）。 */
    val configured: Boolean get() = deadzone != DEFAULT_DEADZONE || trigger != DEFAULT_TRIGGER

    companion object {
        const val DEFAULT_DEADZONE = 0.12f
        const val DEFAULT_TRIGGER = 0.5f

        /** 死区的取值范围（太大手柄自己都会飘）。 */
        val DEADZONE_RANGE = 0f..0.5f

        /** 扳机阈值的取值范围。 */
        val TRIGGER_RANGE = 0.05f..0.95f
    }
}
