package com.nx.android.core.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 手柄 → 界面这一路上的总线。
 *
 * 活动层（`MainActivity`）把物理按键变成 [NavKey] 发进来，页面订阅它。
 * 没有订阅者时事件丢掉：它只对当前看得见的那个界面有意义。
 */
object UiInput {

    private val _keys = MutableSharedFlow<NavKey>(extraBufferCapacity = 16)
    val keys: SharedFlow<NavKey> = _keys.asSharedFlow()

    /** 发一个导航动作。 */
    fun emit(key: NavKey): Boolean = _keys.tryEmit(key)
}

/**
 * 收手柄的导航动作。
 *
 * [enabled] 为 false 时不收：上层浮层打开时，底下的页面把它关掉——
 * 谁在最上面，谁拿走手柄（这个判断只在页面的根做一次，见 `MainScreen`）。
 */
@Composable
fun OnNavKey(enabled: Boolean = true, handler: (NavKey) -> Unit) {
    val current = rememberUpdatedState(handler)
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        UiInput.keys.collect { current.value(it) }
    }
}
