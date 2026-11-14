package com.nx.android.game

import android.content.Context
import android.view.Surface

/**
 * The game's JNI surface (one libdmplay.so per game: the host statically linked with its entry, see
 * nxnative/dm/android/jni_android.c). Loaded with System.load of the copy in the app's private folder
 * (sdcard is noexec), started once per process — the host ends the process with the game, a second
 * start in one process is refused on the native side.
 */
object Native {
    /** 能力位：玩家前端（App 画虚拟手柄，宿主不画自己的面板） */
    const val FRONT_CAPS_PLAYER = 1
    /** 能力位：宿主为 App 的性能 HUD 数帧（老宿主还在画面上自己画帧率计数器） */
    const val FRONT_CAPS_PERF = 2

    @JvmStatic external fun start(pack: String, internal: String, titleId: String?, dpi: Int, env: Array<String>, ctx: Context): Boolean
    @JvmStatic external fun setSurface(surface: Surface?)
    @JvmStatic external fun motion(source: Int, device: Int, action: Int, ids: IntArray, xs: FloatArray, ys: FloatArray, axis: FloatArray): Boolean
    @JvmStatic external fun key(device: Int, source: Int, code: Int, action: Int, meta: Int, repeat: Int): Boolean
    @JvmStatic external fun lifecycle(ev: Int)

    /** 运行中抽屉的帧生成：即时下发（DM_CTL 的 env 行走不到 JNI，这里直达宿主）；"<state> <reason>" 见 dm_framegen.h */
    @JvmStatic external fun frameGenSet(on: Boolean, mult: Int, target: Int, flow: Int)
    @JvmStatic external fun frameGenStatus(): String

    /**
     * 运行中抽屉的帧率档位：0 跟随游戏（自适应自由升降），60/40/30 钉住那一档。
     * 宿主那边是 perf_android.c 的 fps_pin，它每 30 帧重读一次环境变量，所以即时生效。
     */
    @JvmStatic external fun perfPinSet(fps: Int)

    /** 宿主现在的档位（它读 DM_FPS_PIN；0 = 自由自适应） */
    @JvmStatic external fun perfPinGet(): Int

    /** 运行中抽屉的宿主菜单行（画面与操作/存档）：what 0 overlay 1 opacity 2 rumble 4 docked */
    @JvmStatic external fun menuGet(what: Int): Int
    @JvmStatic external fun menuSet(what: Int, v: Double)
    @JvmStatic external fun menuState(): String
    @JvmStatic external fun menuEditOpen()
    @JvmStatic external fun menuSaves(): String
    @JvmStatic external fun menuExport()
    @JvmStatic external fun menuImport(name: String): Boolean
    @JvmStatic external fun menuExportNote(): String

    /**
     * 性能 HUD 的计数（gfx_vk_present.c 的 dm_present_sample）：0 游戏帧数、1 帧生成帧数、2 帧间隔之和(纳秒)、
     * 3 帧间隔的个数、4 屏幕的刷新周期(纳秒)。App 隔一秒读两次，自己算帧率与帧时间。
     */
    @JvmStatic external fun perfSample(): LongArray

    /** 虚拟手柄（App 绘制层）：布局（JSON，见 front_player.c 的 dm_pad_layout_json）：控件的位置与大小 */
    @JvmStatic external fun padLayout(): String

    /**
     * 虚拟手柄的输入：App 自己判定了每个手指落在哪个控件上，把结果告诉宿主——按键位、两个摇杆，
     * 以及属于游戏触摸屏的那些触点（窗口坐标，宿主自己换算到画面里）。
     */
    @JvmStatic external fun padInput(buttons: Long, lx: Int, ly: Int, rx: Int, ry: Int,
                                     ids: IntArray, xs: FloatArray, ys: FloatArray)

    /** 布局编辑（App 的编辑器）：控件的新位置与大小系数、恢复默认、保存进数据包的 settings.txt */
    @JvmStatic external fun padLayoutSet(ctl: Int, x: Float, y: Float, size: Float)
    @JvmStatic external fun padLayoutReset()
    @JvmStatic external fun padLayoutSave()

    /** App 自己的面板开着（布局编辑器）：游戏先等着 */
    @JvmStatic external fun hold(on: Boolean)

    /**
     * 手柄的显示与浓淡：App 的偏好是唯一真值，宿主只留运行时的副本（settings.txt 不再写这两项）。
     */
    @JvmStatic external fun padPrefs(shown: Boolean, opacity: Int)

    /** 正在使用的物理手柄个数（App 监听系统的输入设备后告诉宿主；宿主不再自己轮询设备列表） */
    @JvmStatic external fun padDevices(count: Int)

    /**
     * 游戏的文本输入：宿主挂起游戏的线程并通知 App，App 用系统输入法问玩家，再把文字交回宿主
     * （textResult 唤醒游戏的线程）。textRequest 是要问的内容（JSON：title/text/max）。
     */
    @JvmStatic external fun textRequest(): String
    @JvmStatic external fun textResult(ok: Boolean, text: String)

    /**
     * 宿主的前端版本（能力位）：旧前端的宿主没有这个入口（调用即抛 UnsatisfiedLinkError），App 据此
     * 认出「这个数据包是用旧前端编的」，请玩家重新移植。位：1 = 玩家前端（App 画手柄，宿主不画面板）。
     */
    @JvmStatic external fun frontCaps(): Int
}
