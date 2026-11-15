package com.nx.android.input

/**
 * 推给宿主的映射表（行式文本，宿主那边读它：nxnative/dm/android/pad_map.h）。
 *
 * 宿主没有 JSON 解析器，所以 App 存 JSON、推文本。一只设备一段：
 *
 * ```
 * v1
 * dev 3 labels=1 dz=0.15 tr=0.55 player=1
 * bind scan:305 a
 * unbind scan:314
 * ```
 *
 * 设备是 Android 的 input device id：宿主按事件的 device 查同一张表，所以同型号的两只手柄各查各的。
 * player 是它当几号玩家：0 是玩家 1（不写也是它），1~3 是玩家 2~4，-1 是闲置（它按了也没人管）。
 */
object PadSpec {

    /** 一只设备在宿主那边的那一段。 */
    data class Device(
        val deviceId: Int,
        val labels: Boolean,
        val player: Int = 0,
        val calibration: PadCalibration = PadCalibration(),
        val bindings: Map<String, String> = emptyMap(),
    )

    /** 整张表。 */
    fun build(devices: List<Device>): String {
        val out = StringBuilder("v1\n")
        for (device in devices) {
            out.append("dev ").append(device.deviceId)
                .append(" labels=").append(if (device.labels) 1 else 0)
                .append(" player=").append(device.player)
                .append(" dz=").append(format(device.calibration.deadzone))
                .append(" tr=").append(format(device.calibration.trigger))
                .append('\n')
            for ((source, target) in device.bindings.toSortedMap()) {
                if (!PadSources.isKnown(source)) continue
                if (target == PadSources.DISABLED) {
                    out.append("unbind ").append(source).append('\n')
                } else if (PadTargets.isValid(target)) {
                    out.append("bind ").append(source).append(' ').append(target).append('\n')
                }
            }
        }
        return out.toString()
    }

    /** 小数用宿主读得懂的样子（它用 atof，逗号不行）。 */
    private fun format(value: Float): String {
        val text = value.toString()
        return if (text.endsWith(".0")) text.dropLast(2) else text
    }
}
