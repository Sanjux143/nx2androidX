package com.nx.android.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nx.android.R
import com.nx.android.core.ui.theme.NxPalette

/**
 * 固件那一行：整机级的一份（可选）。
 *
 * 状态点 + 现在是什么（没导入 / 导入的 zip 名）+ 导入 / 清除。
 * 导入是设备级的事：环境清单、设置页的环境一节、引导与构建页都指向这一行，
 * 导入的东西在 App 的私有目录里，移植时统一带给移植器。
 */
@Composable
fun FirmwareRow(
    imported: String,
    onImport: () -> Unit,
    onClear: () -> Unit,
) {
    InfoRow(
        title = stringResource(R.string.fw_title),
        value = imported.ifEmpty { stringResource(R.string.fw_not_imported) },
        leading = { StatusDot(if (imported.isNotBlank()) NxPalette.Ready else NxPalette.Unbuilt, 8.dp) },
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                QuietButton(stringResource(R.string.fw_import), onClick = onImport)
                if (imported.isNotBlank()) QuietButton(stringResource(R.string.builds_clear), onClick = onClear)
            }
        },
    )
}

/** 固件那一行下面的一句：它是可选的，什么时候才需要它 */
@Composable
fun FirmwareHint() {
    Text(
        stringResource(R.string.fw_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
    )
}
