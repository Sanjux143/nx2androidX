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
 *
 * 没有第四种「更新运行库」：运行库过期/缺失的包一样点「构建」（门放行后游戏页的 Loading 自己重建），
 * 完成的包永远只有一个「运行」。
 */
internal fun mainActionOf(status: GameStatus): MainAction = when {
    status == GameStatus.PORTING -> MainAction.VIEW_BUILD
    status == GameStatus.DONE -> MainAction.RUN
    else -> MainAction.BUILD
}

/** 主动作的文案：与左下角主按钮上写的完全一样。 */
@Composable
internal fun mainActionLabel(action: MainAction): String = when (action) {
    MainAction.VIEW_BUILD -> stringResource(R.string.lib_view_builds)
    MainAction.RUN -> stringResource(R.string.set_run_onboarding)
    MainAction.BUILD -> stringResource(R.string.common_build)
}
