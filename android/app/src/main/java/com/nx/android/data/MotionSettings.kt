package com.nx.android.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager

/**
 * 体感：设备转动带动游戏里的六轴传感器（宿主那边 dm_motion_set，王国之泪的弓就是靠它瞄）。
 *
 * 两个开关：
 * - 开不开：默认按设备有没有陀螺仪（没有就别开）
 * - 保留甩动方向：用实测的方向，不用宿主生成的波形（宿主那边 DM_MOTION_RAW）
 *
 * 纯值：env 行在这里组好，JVM 上可测。运行前 env 行交给宿主，运行中抽屉里的开关经 JNI 直达
 * （宿主每 250 ms 重读一次环境变量，所以改了立刻生效，不用重启正在跑的游戏）。
 */
data class MotionKnobs(val on: Boolean, val raw: Boolean) {

    /** 启动时交给宿主的 env 行 */
    fun envLines(): Array<String> = arrayOf(
        "DM_MOTION=${if (on) 1 else 0}",
        "DM_MOTION_RAW=${if (raw) 1 else 0}",
    )
}

/**
 * 体感开关：一个游戏一份（跟抽屉里的其它行一样：帧率档位、帧生成都是每个游戏各一份）。
 *
 * 默认值按设备有没有陀螺仪定；玩家改过之后以他改的为准。
 */
class MotionSettings(private val prefs: PrefsStore, private val titleId: String) {

    private val base = "motion.${titleId.lowercase()}"

    fun load(hasGyro: Boolean): MotionKnobs = MotionKnobs(
        on = prefs.bool("$base.on", hasGyro),
        raw = prefs.bool("$base.raw", false),
    )

    fun save(k: MotionKnobs) {
        prefs.put("$base.on", k.on)
        prefs.put("$base.raw", k.raw)
    }
}

/** 这台设备有没有陀螺仪（体感瞄准要它） */
fun hasGyroscope(context: Context): Boolean =
    (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
        ?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
