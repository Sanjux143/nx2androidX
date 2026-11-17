package com.nx.android.feature.storage

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nx.android.R
import com.nx.android.domain.StorageUsage
import com.nx.android.feature.storage.StorageViewModel
import com.nx.android.core.ui.LocalAppLocale
import com.nx.android.core.ui.components.ActionRow
import com.nx.android.core.ui.components.InfoRow
import com.nx.android.core.ui.components.NoteRow
import com.nx.android.core.ui.components.rememberGameIcon
import com.nx.android.core.ui.components.sizeText
import com.nx.android.core.ui.theme.NxPalette
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 存储：库里每个游戏占了多少（构建工作区 + 数据包），一共占了多少。
 *
 * 这一页只报数字（清理构建缓存与删除游戏仍然在游戏库里做）。统计是**故意**的一次文件
 * 遍历，所以它有三个规矩：
 *
 * - **闸门**：这一节可见不足 [StorageUsage.SCAN_GATE_MS] 就离开 ⇒ 一次遍历都没发生
 *   （闸门挂在协程的挂起点上，被取消即什么都不做）；
 * - **可取消**：离开这一节、切换分区、关闭设置页、应用退到后台 ⇒ 遍历在下一个取消点
 *   立刻停下（`domain/StorageUsage` 里的 `ensureActive()`）；已经测出的那一段留下当下界，
 *   标为「部分统计」；
 * - **当天复用**：当天量过、且量的是现在这份内容 ⇒ 直接用落盘的那份，不重复遍历。
 *
 * 数字逐行呈现：量完一段先出数，其余随后补齐，不拿空白的合计堵住整页。
 *
 * 排版：汇总一行大字，每行一个缩略图 + 名字 + 拆分，底下一条占比条——谁大谁小一眼就
 * 看出来，不必去比数字。
 */
@Composable
fun StorageSection(viewModel: StorageViewModel) {

    // 应用退到后台：这一节不再算数，统计立刻停下（协程被取消 ⇒ 遍历在下一个取消点停）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) viewModel.setForeground(true)
            if (event == Lifecycle.Event.ON_STOP) viewModel.setForeground(false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); viewModel.setVisible(false) }
    }
    DisposableEffect(viewModel) {
        viewModel.setVisible(true)
        onDispose { viewModel.setVisible(false) }
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snapshot = uiState.snapshot
    val items = snapshot.items
    val biggest = items.maxOfOrNull { it.bytes } ?: 0L
    // 合计在这里算：它与下面各行取的是同一份清单，同一帧里必然对得上
    // （以前合计与各行各算各的，统计进行中时会各说各话）。
    val total = items.sumOf { it.bytes }
    val lowerBound = items.any { it.partial }

    SummaryRow(total, lowerBound)
    // 统计进行中：已经量完几项，一眼可见
    if (snapshot.scanning) {
        val measured = items.count { item ->
            item.parts.none { it.state == StorageUsage.State.PENDING || it.state == StorageUsage.State.RUNNING }
        }
        NoteRow(stringResource(R.string.storage_measuring_progress, measured, items.size))
    }
    for (item in items) StorageItemRow(item, biggest)

    ActionRow(
        title = stringResource(R.string.storage_measure_title),
        label = stringResource(R.string.storage_measure_action),
        enabled = !snapshot.scanning,
    ) {
        viewModel.measureNow()
    }
    InfoRow(title = stringResource(R.string.storage_measured_at), value = measuredAtText(snapshot.scannedAt))
    NoteRow(stringResource(R.string.storage_note_scope))
    NoteRow(stringResource(R.string.storage_note_rules))
}

/** 汇总：合计（大字 + 品牌色） */
@Composable
private fun SummaryRow(total: Long, lowerBound: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.storage_total), style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f))
        Text(
            if (lowerBound) stringResource(R.string.storage_partial_value, sizeText(total))
            else sizeText(total),
            style = MaterialTheme.typography.titleMedium, color = NxPalette.Cream,
        )
    }
}

/** 一个游戏的一行：缩略图、名字、拆分与路径、占用；底下那条是它占的比例 */
@Composable
private fun StorageItemRow(item: StorageUsage.Item, biggest: Long) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Thumb(item)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                itemName(item),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val breakdown = breakdownText(item)
            if (breakdown.isNotEmpty()) {
                Text(breakdown, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val paths = pathText(item)
            if (paths.isNotEmpty()) {
                Text(paths, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(valueText(item), style = MaterialTheme.typography.titleMedium, color = valueColor(item))
    }
    // 占比条：相对最大的一项，谁大谁小一眼就看出来
    if (item.bytes > 0L && biggest > 0L) {
        Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp)
            .clip(RoundedCornerShape(2.dp)).background(NxPalette.Background)) {
            Box(Modifier.fillMaxWidth(item.bytes.toFloat() / biggest.toFloat())
                .fillMaxHeight().background(NxPalette.Cream))
        }
    }
}

/** 缩略图：包里的封面，没有就用名字的首字母；兜底那一项用文件夹图标 */
@Composable
private fun Thumb(item: StorageUsage.Item) {
    val icon by rememberGameIcon(item.icon.takeIf { it.isNotEmpty() }, 128)
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(NxPalette.Background),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = icon
        if (bitmap != null) {
            Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else if (item.id == StorageUsage.ORPHAN_ID) {
            Icon(Icons.Filled.Folder, null, Modifier.size(20.dp), tint = NxPalette.Cream)
        } else {
            Text(
                item.name.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 这一项叫什么：游戏名；兜底那一项是「未登记的包与残留」 */
@Composable
private fun itemName(item: StorageUsage.Item): String =
    if (item.id == StorageUsage.ORPHAN_ID) stringResource(R.string.storage_orphan)
    else item.name

/** 这一项的数字：量到了多少；量不到就说明白 */
@Composable
private fun valueText(item: StorageUsage.Item): String {
    if (item.parts.isEmpty()) return sizeText(0L)
    val unknown = item.parts.any { it.role != StorageUsage.Role.OUTSIDE && it.state == StorageUsage.State.UNKNOWN }
    val failed = item.parts.any { it.role != StorageUsage.Role.OUTSIDE && it.state == StorageUsage.State.FAILED }
    val measured = item.parts.any { it.state == StorageUsage.State.DONE || it.state == StorageUsage.State.PARTIAL }
    // 一样都没量到、且有够不着的：那是「大小未知」，不是 0
    if (unknown && !measured) return stringResource(R.string.storage_unreachable)
    // 有一段只量了一半 / 够不着 / 失败：给出的数是下界
    if (unknown || failed || item.partial) return stringResource(R.string.storage_partial_value, sizeText(item.bytes))
    return sizeText(item.bytes)
}

/** 数字的颜色：下界与量不到的要让人看出来 */
@Composable
private fun valueColor(item: StorageUsage.Item) = when {
    item.parts.any { it.role != StorageUsage.Role.OUTSIDE && it.state == StorageUsage.State.UNKNOWN } -> MaterialTheme.colorScheme.onSurfaceVariant
    item.partial -> NxPalette.Building
    else -> MaterialTheme.colorScheme.onSurface
}

/** 拆分：工作区多少、数据包多少 */
@Composable
private fun breakdownText(item: StorageUsage.Item): String {
    val bits = ArrayList<String>()
    for (p in item.parts) {
        val role = stringResource(
            when (p.role) {
                StorageUsage.Role.WORKSPACE -> R.string.storage_role_workspace
                StorageUsage.Role.OUTSIDE -> R.string.storage_role_outside
                else -> R.string.storage_role_pack
            })
        bits += when (p.state) {
            StorageUsage.State.DONE -> "$role ${sizeText(p.bytes)}"
            StorageUsage.State.PARTIAL -> "$role ${stringResource(R.string.storage_partial_value, sizeText(p.bytes))}"
            StorageUsage.State.PENDING -> "$role ${stringResource(R.string.storage_not_measured)}"
            StorageUsage.State.RUNNING -> "$role ${stringResource(R.string.storage_measuring)}"
            StorageUsage.State.FAILED -> "$role ${stringResource(R.string.storage_failed)}"
            // App 之外的文件：不算进合计，只说明它在哪
            StorageUsage.State.UNKNOWN -> if (p.role == StorageUsage.Role.OUTSIDE) "$role ${stringResource(R.string.storage_outside_not_counted)}"
                else "$role ${stringResource(R.string.storage_unreachable)}"
        }
    }
    return bits.joinToString(" · ")
}

/** 路径：被统计的目录在哪（面板上要能查） */
@Composable
private fun pathText(item: StorageUsage.Item): String {
    val paths = ArrayList<String>()
    for (p in item.parts) paths += p.path
    return paths.joinToString(" · ")
}

/** 统计的时间：今天的只给时刻，别的日子连日期一起 */
@Composable
private fun measuredAtText(scannedAt: Long): String {
    if (scannedAt <= 0L) return stringResource(R.string.storage_not_measured)
    val loc = LocalAppLocale.current.locale ?: LocalConfiguration.current.locales[0]
    val date = Date(scannedAt)
    return if (StorageUsage.isToday(scannedAt)) SimpleDateFormat("HH:mm", loc).format(date)
    else SimpleDateFormat("yyyy-MM-dd HH:mm", loc).format(date)
}
