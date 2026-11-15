package com.nx.android.core.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nx.android.core.ui.theme.NxPalette

/** 提示条里的一项：一个键帽 + 这个键做什么。 */
data class NavHint(val cap: String, val label: String)

/**
 * 按键提示条：告诉玩家此刻能按什么。
 *
 * 只列**当前这一层真的能按**的键——列了没反应的键比不列更糟。
 * 键帽上画的是按键的**位置**（Ⓐ 在下、Ⓑ 在右），与游戏里手柄默认映射同一套约定。
 */
@Composable
fun NavHintBar(hints: List<NavHint>, modifier: Modifier = Modifier) {
    if (hints.isEmpty()) return
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(NxPalette.Surface.copy(alpha = 0.72f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HintItems(hints)
    }
}

@Composable
private fun HintItems(hints: List<NavHint>) {
    for (h in hints) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            KeyCap(h.cap)
            Text(
                h.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 键帽：一个小圆角方块，里面是键位的位置字形。 */
@Composable
fun KeyCap(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
