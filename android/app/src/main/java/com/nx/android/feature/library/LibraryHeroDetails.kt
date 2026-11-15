package com.nx.android.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nx.android.R
import com.nx.android.core.ui.PreviewSurface
import com.nx.android.core.ui.theme.NxPalette
import com.nx.android.data.library.GameProfile
import com.nx.android.domain.GameEntry
import com.nx.android.domain.GameStatus

/**
 * 左下详情：大标题、Title ID · 状态、主按钮（未移植 ▶ 构建 / 已完成 ▶ 运行）与圆形次级钮。
 *
 * 无状态：这一层只画，按钮要做的事由外面给（[onBuild] / [onRun] / [onViewBuild] / [onConfigure]）。
 */
@Composable
internal fun HeroDetails(
    entry: GameEntry,
    profile: GameProfile? = null,
    status: GameStatus,
    stale: Boolean,
    locked: Boolean,
    runningGameName: String,
    portProgress: Float,
    onBuild: () -> Unit,
    onRun: () -> Unit,
    onViewBuild: () -> Unit,
    onConfigure: () -> Unit,
) {
    val ported = entry.pack
    // Title ID：游戏的身份（还没构建过的还没有）
    val titleId = entry.titleId
    // 主按钮的三种走法：正在构建（去看进度）、已完成（去运行）、其余（去构建）
    // 主动作只有这一份实现：触屏的按钮、手柄的确认键、提示条上的文案都走它
    val action = mainActionOf(status, stale)
    val actionLabel = mainActionLabel(action, stale)
    // 已经移植完的：配置钮写的是「运行配置」，其余是「移植配置」
    val running = status == GameStatus.DONE

    Column(Modifier.widthIn(max = 640.dp)) {
        Text(
            entry.label,
            style = MaterialTheme.typography.displaySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            (listOfNotNull(titleId, versionTag(ported), statusLabel(status) +
                if (status == GameStatus.PORTING) " ${(portProgress * 100).toInt()}%" else "",
                experimentalTag(ported), reportTag(ported)) + needsTags(profile, ported)).joinToString("  ·  "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            // 主按钮：未移植 ▶ 构建（携带游戏与配置跳到构建页），移植中变「查看构建」，
            // 已完成 ▶ 运行（App 内直接进游戏）；旧版宿主编的包要重新移植，主按钮变成「更新运行库」
            Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (locked) NxPalette.Unbuilt else NxPalette.Cream)
                        .clickable(enabled = !locked) {
                            when (action) {
                                MainAction.VIEW_BUILD -> onViewBuild()
                                MainAction.RUN -> onRun()
                                MainAction.BUILD -> onBuild()
                            }
                        }
                        .padding(horizontal = 30.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp),
                         tint = if (locked) NxPalette.Background else Color.Black)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        actionLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (locked) NxPalette.Background else Color.Black,
                    )
            }
            // 配置圆钮
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.2f))
                    .clickable { onConfigure() }.padding(11.dp),
            ) {
                Icon(Icons.Filled.Tune, if (running) stringResource(R.string.lib_run_config) else stringResource(R.string.lib_port_config), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onBackground)
            }
        }
        if (locked) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.lib_close_current_game, runningGameName), style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 预览：还没构建过的游戏（主按钮是「构建」） */
@Preview(name = "Details, not built", widthDp = 700, heightDp = 220, showBackground = true)
@Composable
private fun HeroDetailsNotBuiltPreview() {
    PreviewSurface {
        HeroDetails(
            entry = previewGames[2].entry,
            status = GameStatus.NEEDS_BUILD,
            stale = false,
            locked = false,
            runningGameName = "",
            portProgress = 0f,
            onBuild = {},
            onRun = {},
            onViewBuild = {},
            onConfigure = {},
        )
    }
}

/** 预览：能玩的游戏（主按钮是「运行」） */
@Preview(name = "Details, ready to run", widthDp = 700, heightDp = 220, showBackground = true)
@Composable
private fun HeroDetailsReadyPreview() {
    PreviewSurface {
        HeroDetails(
            entry = previewGames[0].entry,
            status = GameStatus.DONE,
            stale = false,
            locked = false,
            runningGameName = "",
            portProgress = 0f,
            onBuild = {},
            onRun = {},
            onViewBuild = {},
            onConfigure = {},
        )
    }
}

/** 预览：别的游戏在玩（主按钮置灰，并说明要先关掉那个） */
@Preview(name = "Details, another game runs", widthDp = 700, heightDp = 240, showBackground = true)
@Composable
private fun HeroDetailsLockedPreview() {
    PreviewSurface {
        HeroDetails(
            entry = previewGames[0].entry,
            status = GameStatus.DONE,
            stale = false,
            locked = true,
            runningGameName = "Hades II",
            portProgress = 0f,
            onBuild = {},
            onRun = {},
            onViewBuild = {},
            onConfigure = {},
        )
    }
}

/** 预览：旧版宿主编的包（主按钮是「重新移植」） */
@Preview(name = "Details, needs a new port", widthDp = 700, heightDp = 220, showBackground = true)
@Composable
private fun HeroDetailsStalePreview() {
    PreviewSurface {
        HeroDetails(
            entry = previewGames[3].entry,
            status = GameStatus.NEEDS_BUILD,
            stale = true,
            locked = false,
            runningGameName = "",
            portProgress = 0f,
            onBuild = {},
            onRun = {},
            onViewBuild = {},
            onConfigure = {},
        )
    }
}
