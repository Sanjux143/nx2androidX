package com.nx.android.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nx.android.R
import com.nx.android.core.ui.PreviewSurface
import com.nx.android.core.ui.components.StatusDot
import com.nx.android.core.ui.components.rememberGameIcon
import com.nx.android.core.ui.theme.NxPalette
import com.nx.android.domain.GameEntry
import com.nx.android.domain.GameStatus

/**
 * 主机式行视图的两块：选中游戏铺满全屏的背景，和横向的瓦片行。
 *
 * 都是无状态的：状态与进度由外面给进来，图片读不到时走渐变与首字母。
 */

/** 选中游戏的全屏呈现：已完成的游戏用产物 APK 里的图标实图，其余用名字的渐变与首字母水印 */
@Composable
internal fun HeroBackground(sel: GameEntry?) {
    if (sel == null) return
    val icon by rememberGameIcon(sel.pack?.icon?.absolutePath, 1280)

    Box(Modifier.fillMaxSize()) {
        val bitmap = icon
        if (bitmap != null) {
            // 磨砂玻璃：小图标拉满全屏（放大裁掉模糊暗角）+ 高斯模糊，压暗层保持文字可读
            androidx.compose.foundation.Image(
                bitmap, null,
                Modifier.fillMaxSize().scale(1.15f).blur(32.dp, BlurredEdgeTreatment.Unbounded),
                contentScale = ContentScale.Crop, alpha = 0.6f,
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(coverColors(sel.label).map { it.copy(alpha = 0.45f) })
                )
            )
            Text(
                sel.label.take(1).ifEmpty { "?" },
                fontSize = 180.sp, fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.10f),
                modifier = Modifier.align(Alignment.Center),
            )
        }
        // 压暗：文字要保持可读
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xEE0E0E0F), 0.65f to Color(0x220E0E0F))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to NxPalette.Background)))
    }
}

/** 预览：选中游戏铺满全屏的背景 */
@Preview(name = "Background, the selected game", widthDp = 1280, heightDp = 800, showBackground = true)
@Composable
private fun HeroBackgroundPreview() {
    PreviewSurface {
        HeroBackground(previewGames[0].entry)
    }
}

/**
 * 横向瓦片行：选中的放大（88 → 128），状态点跟着瓦片走。
 *
 * [statusOf] 与 [portProgress] 由外面给：这一层不知道状态从哪来，只管画。
 */
@Composable
internal fun HeroRow(
    entries: List<GameEntry>,
    heroIndex: Int,
    statusOf: (GameEntry) -> GameStatus,
    portProgress: Float,
    onSelect: (Int) -> Unit,
) {
    val state = rememberLazyListState()
    // 选中项移到第三格之后行才滚动，选中的前后都留在视野里
    LaunchedEffect(heroIndex) {
        if (entries.isNotEmpty()) state.animateScrollToItem((heroIndex - 2).coerceIn(0, entries.size - 1))
    }
    LazyRow(
        state = state,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(end = 80.dp),
        modifier = Modifier.fillMaxWidth().height(132.dp),
    ) {
        itemsIndexed(entries, key = { _, e -> e.key }) { index, e ->
            val size by androidx.compose.animation.core.animateDpAsState(
                if (index == heroIndex) 128.dp else 88.dp, label = "tileSize"
            )
            HeroTile(e, statusOf(e), index == heroIndex, portProgress, Modifier.size(size)) { onSelect(index) }
        }
    }
}

/** 预览：横向瓦片行（选中的那个放大，四种状态各一块） */
@Preview(name = "Tiles, the four states", widthDp = 1280, heightDp = 190, showBackground = true)
@Composable
private fun HeroRowPreview() {
    PreviewSurface {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp)) {
            HeroRow(previewGames.map { it.entry }, 1, previewStatus, 0.42f) {}
        }
    }
}

/** 一块瓦片：封面（或渐变与首字母）、选中时加粗的描边、右上角的状态点（构建中还有百分比） */
@Composable
private fun HeroTile(
    entry: GameEntry,
    status: GameStatus,
    selected: Boolean,
    portProgress: Float,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val icon by rememberGameIcon(entry.pack?.icon?.absolutePath, 256)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Brush.linearGradient(coverColors(entry.label)))
            .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else NxPalette.Unbuilt.copy(alpha = 0.4f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = icon
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap, entry.label, Modifier.padding(2.dp).fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                entry.label.take(1).ifEmpty { "?" },
                fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.85f),
            )
        }
        Surface(shape = RoundedCornerShape(50), color = NxPalette.Background.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusDot(statusColor(status), 6.dp)
                if (status == GameStatus.PORTING) {
                    Text("${(portProgress * 100).toInt()}%", fontSize = 10.sp, color = statusColor(status))
                }
            }
        }
        // 导入的包：角上写明它是别人导来的（App 自己做的包没有这个标记）
        if (entry.isImported) {
            Surface(shape = RoundedCornerShape(50), color = NxPalette.Background.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)) {
                Text(stringResource(R.string.lib_pack_imported_badge), fontSize = 10.sp,
                     color = Color.White.copy(alpha = 0.85f),
                     modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
    }
}
