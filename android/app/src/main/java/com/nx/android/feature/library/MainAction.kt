package com.nx.android.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nx.android.R
import com.nx.android.domain.GameStatus

/**
 * 库页的主动作：把游戏跑起来 / 去构建 / 看构建的进度。
 *
 * 只有这一份实现——触屏的主按钮、手柄的确认键、底部提示条上的文案都走它，
 * 手柄与手指不可能走样。
 */
internal enum class MainAction { RUN, BUILD, VIEW_BUILD }

/**
 * 这个游戏的主动作是什么。
 *
 * @param status 游戏现在的状态（跑着的那个游戏在状态里也算 PORTING）
 * @param stale 旧版宿主编的包：主按钮变成「更新运行库」，走重新移植那条路
 */
internal fun mainActionOf(status: GameStatus, stale: Boolean): MainAction = when {
    status == GameStatus.PORTING -> MainAction.VIEW_BUILD
    status == GameStatus.DONE && !stale -> MainAction.RUN
    else -> MainAction.BUILD
}

/** 主动作的文案：与左下角主按钮上写的完全一样。 */
@Composable
internal fun mainActionLabel(action: MainAction, stale: Boolean): String = when {
    action == MainAction.VIEW_BUILD -> stringResource(R.string.lib_view_builds)
    stale -> stringResource(R.string.runtime_update)
    action == MainAction.RUN -> stringResource(R.string.set_run_onboarding)
    else -> stringResource(R.string.common_build)
}
