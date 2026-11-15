package com.nx.android.input.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nx.android.R
import com.nx.android.core.ui.theme.NxPalette
import com.nx.android.input.GamepadScan
import com.nx.android.input.PadMapping
import com.nx.android.input.PadProfileStore
import com.nx.android.input.PadSources
import com.nx.android.input.PadTargets

/**
 * 改键：哪个物理键是哪个游戏键。
 *
 * 每个游戏键一行：现在绑着的源（没有就显示出厂的那个）、改键、清除。底下是「恢复默认」。
 * 改键是抓式的：点「改键」，再按手柄上那颗想用的键；抓到的键跟别人的冲突时，
 * 问玩家是替换、交换还是取消（不替他决定）。
 */
@Composable
fun PadMappingPanel(
    controller: PadCenterController,
    deviceId: Int?,
) {
    val devices by GamepadScan.devices.collectAsState()
    val selected = devices.firstOrNull { it.id == deviceId } ?: devices.firstOrNull()

    if (selected == null) {
        Text(
            stringResource(R.string.pad_center_none),
            style = MaterialTheme.typography.bodySmall,
            color = NxPalette.Unbuilt,
        )
        return
    }

    val profile = controller.profile(selected)
    Column(Modifier.fillMaxWidth()) {
        Text(
            selected.name + " · " + stringResource(
                if (selected.vibration) R.string.pad_center_vibration else R.string.pad_center_no_vibration,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = NxPalette.Unbuilt,
        )
        if (controller.capturing != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.pad_center_capture),
                style = MaterialTheme.typography.bodyMedium,
                color = NxPalette.Brand,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(8.dp))

        for (target in PadTargets.ALL) {
            val bound = profile.bindings.entries.firstOrNull { it.value == target }?.key
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    target.uppercase(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier.width(52.dp),
                )
                Text(
                    bindingText(target, bound, profile),
                    style = MaterialTheme.typography.bodySmall,
                    color = NxPalette.Unbuilt,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.pad_center_rebind),
                    style = MaterialTheme.typography.bodySmall,
                    color = NxPalette.Brand,
                    modifier = Modifier
                        .clickable { controller.capture(selected.id, target) }
                        .padding(6.dp),
                )
                Text(
                    stringResource(R.string.pad_center_clear),
                    style = MaterialTheme.typography.bodySmall,
                    color = NxPalette.Unbuilt,
                    modifier = Modifier
                        .clickable { bound?.let { controller.clearBinding(selected, it) } }
                        .padding(6.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 一键：面键按 Xbox 的印字（它只是写四条绑定）
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(NxPalette.Surface)
                    .clickable { controller.applyXboxPrint(selected) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.BrightnessHigh, null, Modifier.width(18.dp).height(18.dp), tint = NxPalette.Brand)
                Text(
                    stringResource(R.string.pad_center_xbox_print),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 全部绑定恢复出厂
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(NxPalette.Surface)
                    .clickable { controller.resetBindings(selected) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Refresh, null, Modifier.width(18.dp).height(18.dp), tint = NxPalette.Brand)
                Text(stringResource(R.string.pad_center_reset), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.pad_center_print_hint),
            style = MaterialTheme.typography.bodySmall,
            color = NxPalette.Unbuilt,
        )
    }

    // 撞车了：让玩家选（替换 / 交换 / 取消）
    controller.conflict?.let { hit ->
        AlertDialog(
            onDismissRequest = { controller.resolveConflict(replace = false, swap = false) },
            title = { Text(stringResource(R.string.pad_conflict_title)) },
            text = {
                Text(stringResource(R.string.pad_conflict_text, hit.source, hit.target))
            },
            confirmButton = {
                TextButton(onClick = { controller.resolveConflict(replace = true, swap = false) }) {
                    Text(stringResource(R.string.pad_conflict_replace))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { controller.resolveConflict(replace = false, swap = true) }) {
                        Text(stringResource(R.string.pad_conflict_swap))
                    }
                    TextButton(onClick = { controller.resolveConflict(replace = false, swap = false) }) {
                        Text(stringResource(R.string.pad_conflict_cancel))
                    }
                }
            },
        )
    }
}

/** 一个绑定显示成什么：玩家自己绑的源，没有就显示出厂的那个。 */
@Composable
private fun bindingText(target: String, bound: String?, profile: PadProfileStore.Profile): String = when {
    bound != null && bound != PadSources.DISABLED -> bound
    bound == PadSources.DISABLED -> stringResource(R.string.pad_center_disabled)
    else -> {
        val default = PadMapping.defaultSourceFor(target, profile.labels)
        if (default != null) stringResource(R.string.pad_center_default) + " · " + default
        else stringResource(R.string.pad_center_unbound)
    }
}
