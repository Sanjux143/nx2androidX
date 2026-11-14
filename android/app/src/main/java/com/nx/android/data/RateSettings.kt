package com.nx.android.data

/**
 * 帧率档位（纯值，JVM 可测）：0 跟随游戏（自适应自由升降），60/40/30 钉住那一档。
 * 宿主那边是 perf_android.c 的 fps_pin：自适应帧率自己会在 60/40/30 之间切换，钉住一档就不再切。
 */
data class RatePin(val fps: Int) {

    companion object {
        /** 选项表：跟随游戏在前（0），后面是宿主的三档 */
        val PINS = intArrayOf(0, 60, 40, 30)

        /** 只有宿主认得的档位才算数（别的值等于自由自适应） */
        fun valid(v: Int) = v in PINS

        /** 选项表里的位置（-1 = 不是宿主的一档） */
        fun indexOf(v: Int) = PINS.indexOf(v)
    }
}

/**
 * 一个游戏的帧率档位：prefs 壳（按 Title ID 的键）。
 * 键不存在 = 玩家没选过：档案自己的默认（android.tsv 的 DM_FPS_PIN）继续说话。
 */
class RateSettings(private val prefs: PrefsStore, private val titleId: String) {

    private val base = "rate.${titleId.lowercase()}"

    /** 玩家选的档位；null = 没选过（档案的默认继续生效） */
    fun load(): RatePin? = prefs.str("$base.pin").toIntOrNull()?.takeIf { RatePin.valid(it) }?.let { RatePin(it) }

    fun save(pin: RatePin) {
        if (RatePin.valid(pin.fps)) prefs.put("$base.pin", pin.fps.toString())
    }
}
