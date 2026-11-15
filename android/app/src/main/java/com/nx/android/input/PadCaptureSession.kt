package com.nx.android.input

/**
 * 捕获式改键的状态机：玩家按下想绑的那个物理键，我们记住它。
 *
 * 一次只抓一只设备的一个键：抓到了就交出去（回调），别的按键照旧进游戏（除非手柄中心开着，
 * 那时中心自己吞掉整个手柄的输入）。
 */
class PadCaptureSession(private val timeoutMs: Long = TIMEOUT_MS) {

    companion object {
        const val TIMEOUT_MS = 10_000L
    }

    /** 一次喂入的结果：这个事件被吃掉了吗（吃掉了就不下泄给游戏）。 */
    data class Offer(val consumed: Boolean, val cancelled: Boolean = false)

    private data class Capture(val deviceId: Int, val startedAt: Long, val onCaptured: (String) -> Unit)

    private data class PendingRelease(val deviceId: Int, val scanCode: Int, val keyCode: Int)

    private var capture: Capture? = null
    private val pendingReleases = mutableSetOf<PendingRelease>()

    /** 开始抓：抓到的来源（"scan:305" 或 "key:96"）交给回调。 */
    fun start(deviceId: Int, now: Long, onCaptured: (String) -> Unit) {
        capture = Capture(deviceId, now, onCaptured)
    }

    /** 一个按键事件进来。 */
    fun offerKey(
        deviceId: Int,
        isGamepad: Boolean,
        down: Boolean,
        up: Boolean,
        repeatCount: Int,
        scanCode: Int,
        keyCode: Int,
        now: Long,
    ): Offer {
        val key = PendingRelease(deviceId, scanCode, keyCode)
        if (key in pendingReleases) {
            if (up) pendingReleases.remove(key)
            return Offer(consumed = true)
        }
        val active = capture ?: return Offer(consumed = false)
        if (!isGamepad) return Offer(consumed = false)
        if (now - active.startedAt >= timeoutMs) {
            capture = null
            if (down && repeatCount == 0) pendingReleases += key
            return Offer(consumed = true, cancelled = true)
        }
        if (deviceId != active.deviceId) {
            if (down && repeatCount == 0) pendingReleases += key
            return Offer(consumed = true)
        }
        if (down && repeatCount == 0) {
            pendingReleases += key
            capture = null
            active.onCaptured(sourceOf(scanCode, keyCode))
        }
        return Offer(consumed = true)
    }

    /** 一根模拟轴进来（扳机推到底、十字键帽扳到边）。 */
    fun offerAxis(
        deviceId: Int,
        isGamepad: Boolean,
        axisValues: List<Pair<Int, Float>>,
        now: Long,
    ): Offer {
        val active = capture ?: return Offer(consumed = false)
        if (!isGamepad) return Offer(consumed = false)
        if (now - active.startedAt >= timeoutMs) {
            capture = null
            return Offer(consumed = true, cancelled = true)
        }
        if (deviceId != active.deviceId) return Offer(consumed = true)
        axisValues.firstOrNull { PadSources.isCapturableAxis(it.first) && kotlin.math.abs(it.second) >= 0.7f }?.let { (axis, value) ->
            capture = null
            active.onCaptured(PadSources.axis(axis, value))
        }
        return Offer(consumed = true)
    }

    /** 不抓了（玩家按了取消）。 */
    fun cancel(): Boolean {
        if (capture == null) return false
        capture = null
        return true
    }

    /** 那只手柄拔了：抓它的会话作废。 */
    fun cancelDisconnectedDevice(deviceId: Int): Boolean {
        pendingReleases.removeAll { it.deviceId == deviceId }
        if (capture?.deviceId != deviceId) return false
        capture = null
        return true
    }

    /** 物理键的来源：扫描码优先（跨手机稳定），没有就用 keyCode。 */
    private fun sourceOf(scanCode: Int, keyCode: Int): String =
        if (scanCode > 0) PadSources.scan(scanCode) else PadSources.key(keyCode)
}
