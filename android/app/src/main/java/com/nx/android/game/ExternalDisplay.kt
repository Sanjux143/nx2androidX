package com.nx.android.game

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import android.view.WindowInsetsController

/**
 * 外接屏：App 在一块接上的显示器（平板的 USB-C DP 输出、扩展坞）上开一个 presentation，
 * 它的 surface 交给宿主——游戏画到那块屏上，手机（平板）自己变成手柄。
 *
 * 移植自上游引擎的 org.nx2a.ExternalDisplay（nxnative/dm/android/java/org/nx2a/ExternalDisplay.java）：
 * 只用公开的 Android API（DisplayManager 的监听、Presentation、SurfaceView）。上游那一套自己画的
 * 虚拟手柄与菜单栅格不搬：App 本来就有自己的手柄与抽屉。
 *
 * 所有回调都在主线程上。
 */
class ExternalDisplay(private val activity: Activity, private val listener: Listener) :
    DisplayManager.DisplayListener {

    interface Listener {
        /** 那块屏的画面来了：宿主把游戏画到它上面 */
        fun surfaceChanged(surface: Surface, width: Int, height: Int)

        /** 那块屏没了（拔了、屏灭了）：宿主把游戏放回手机自己的画面 */
        fun surfaceLost()
    }

    private val displays: DisplayManager? =
        activity.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    private var presentation: Presentation? = null
    private var displayId = Display.INVALID_DISPLAY
    private var active = false
    private var surfaceLive = false

    /** 现在有没有可用的外接屏（插上了吗）：没有就别开 */
    fun available(): Boolean =
        displays?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.isNotEmpty() == true

    /** 开始找外接屏：找到了就把它的画面交给宿主 */
    fun start() {
        if (active || displays == null) return
        active = true
        displays.registerDisplayListener(this, Handler(Looper.getMainLooper()))
        update()
    }

    /** 不找外接屏了：那块屏的画面交还回去（宿主把游戏放回手机） */
    fun stop() {
        if (!active) return
        active = false
        displays?.unregisterDisplayListener(this)
        dismiss()
    }

    private fun dismiss() {
        val old = presentation
        presentation = null
        displayId = Display.INVALID_DISPLAY
        if (surfaceLive) {
            surfaceLive = false
            listener.surfaceLost()
        }
        old?.dismiss()
    }

    private fun update() {
        if (!active) return
        val candidates = displays?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION) ?: return
        val selected = candidates.firstOrNull()
        if (selected == null) {
            dismiss()
            return
        }
        if (presentation != null && selected.displayId == displayId) return
        dismiss()
        val next = Presentation(activity, selected)
        val view = SurfaceView(next.context)
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (presentation !== next || !active) return
                surfaceLive = true
                listener.surfaceChanged(holder.surface, width, height)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (presentation !== next || !surfaceLive) return
                surfaceLive = false
                listener.surfaceLost()
            }
        })
        next.setContentView(view)
        // 那块屏上只留画面：系统的状态栏与任务栏让给游戏
        next.window?.let { w ->
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) w.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        presentation = next
        displayId = selected.displayId
        try {
            next.show()
        } catch (e: RuntimeException) {
            dismiss()
        }
    }

    override fun onDisplayAdded(displayId: Int) { update() }
    override fun onDisplayRemoved(displayId: Int) { update() }
    override fun onDisplayChanged(displayId: Int) { update() }
}
