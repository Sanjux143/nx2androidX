package com.nx.android.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nx.android.R
import com.nx.android.core.ui.PreviewSurface
import com.nx.android.core.ui.components.FirmwareHint
import com.nx.android.core.ui.components.FirmwareRow
import com.nx.android.core.ui.components.Hint
import com.nx.android.core.ui.components.QuietButton
import com.nx.android.core.ui.components.Section
import com.nx.android.core.ui.components.StatusDot
import com.nx.android.core.ui.theme.NxPalette
import com.nx.android.domain.ObFacts
import com.nx.android.domain.ObStep

/**
 * 引导的四个步骤：欢迎（权限、空间与后台运行）、环境、keys、好了。
 *
 * 都是无状态的：看到的东西从 [OnboardingUiState] 来，要做的事是外面给的回调。
 */

@Composable
internal fun WelcomeStep(facts: ObFacts, onAskStorage: () -> Unit, onAskBattery: () -> Unit) {
    Section(stringResource(R.string.onb_storage_access)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusDot(if (facts.storageOk) NxPalette.Ready else NxPalette.Danger)
            Text(
                if (facts.storageOk) stringResource(R.string.onb_storage_granted)
                else stringResource(R.string.onb_storage_why),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (!facts.storageOk) QuietButton(stringResource(R.string.onb_grant_access), onAskStorage)
        }
        Hint(stringResource(R.string.onb_grant_access_where))
    }
    Section(stringResource(R.string.onb_disk_space)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusDot(if (facts.freeGB >= facts.neededGB) NxPalette.Ready else NxPalette.Danger)
            Text(
                stringResource(R.string.onb_free_space, facts.freeGB),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Hint(if (facts.freeGB < facts.neededGB) stringResource(R.string.onb_space_short) else stringResource(R.string.onb_space_enough))
    }
    // The battery: a hint, not a gate. The dot is amber, not red - the setup goes on either way, but
    // without the exemption the power saving can stop the unpack or a port halfway.
    Section(stringResource(R.string.onb_battery_access)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusDot(if (facts.batteryOk) NxPalette.Ready else NxPalette.Building)
            Text(
                if (facts.batteryOk) stringResource(R.string.onb_battery_ok)
                else stringResource(R.string.onb_battery_why),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (!facts.batteryOk) QuietButton(stringResource(R.string.onb_open_battery_settings), onAskBattery)
        }
        Hint(stringResource(R.string.onb_battery_hint))
    }
}

/** 预览：还没拿到「所有文件访问」（红点与授予按钮） */
@Preview(name = "Step, storage not granted", widthDp = 1280, heightDp = 420, showBackground = true)
@Composable
private fun WelcomeStepPreview() {
    PreviewSurface {
        Column(Modifier.fillMaxWidth()) {
            WelcomeStep(
                facts = previewState(ObStep.WELCOME, storageOk = false, freeGB = 42).facts,
                onAskStorage = {},
                onAskBattery = {},
            )
        }
    }
}

/** 预览：还没拿到电池豁免（黄点与设置按钮） */
@Preview(name = "Step, battery not exempt", widthDp = 1280, heightDp = 520, showBackground = true)
@Composable
private fun WelcomeStepBatteryPreview() {
    PreviewSurface {
        Column(Modifier.fillMaxWidth()) {
            WelcomeStep(
                facts = previewState(ObStep.WELCOME, batteryOk = false).facts,
                onAskStorage = {},
                onAskBattery = {},
            )
        }
    }
}

@Composable
internal fun EnvStep(uiState: OnboardingUiState, onUnpack: () -> Unit) {
    val ready = uiState.envReady
    Section(stringResource(R.string.onb_runtime)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (uiState.unpacking) {
                CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp, color = NxPalette.Cream)
            } else {
                StatusDot(if (ready) NxPalette.Ready else NxPalette.Building)
            }
            Text(
                when {
                    ready -> stringResource(R.string.env_ready)
                    uiState.unpacking -> stringResource(R.string.onb_unpacking, uiState.unpackStep)
                    else -> stringResource(R.string.onb_no_env)
                },
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
            )
            if (!ready && !uiState.unpacking) QuietButton(stringResource(R.string.onb_prepare_env), onUnpack)
        }
        if (uiState.unpacking) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(5.dp), color = NxPalette.Building)
        }
        if (uiState.unpackError.isNotEmpty()) {
            Text(
                stringResource(R.string.onb_prepare_failed, uiState.unpackError),
                color = NxPalette.Danger,
                style = MaterialTheme.typography.bodySmall,
            )
            Hint(stringResource(R.string.onb_retry_hint))
        }
    }
    Hint(stringResource(R.string.onb_unpack_once))
}

/** 预览：正在解包（转圈 + 进度条 + 当前那一步） */
@Preview(name = "Step, unpacking", widthDp = 1280, heightDp = 300, showBackground = true)
@Composable
private fun EnvStepUnpackingPreview() {
    PreviewSurface {
        Column(Modifier.fillMaxWidth()) {
            EnvStep(
                uiState = previewState(ObStep.ENV, unpacking = true, unpackStep = "rootfs"),
                onUnpack = {},
            )
        }
    }
}

/** 预览：解包失败（红字与重试的提示） */
@Preview(name = "Step, unpack failed", widthDp = 1280, heightDp = 300, showBackground = true)
@Composable
private fun EnvStepErrorPreview() {
    PreviewSurface {
        Column(Modifier.fillMaxWidth()) {
            EnvStep(
                uiState = previewState(ObStep.ENV, unpackError = "IOException: no space left on device"),
                onUnpack = {},
            )
        }
    }
}

@Composable
internal fun KeysStep(
    uiState: OnboardingUiState,
    onChooseKeysFile: () -> Unit,
    onKeysSkippedChange: (Boolean) -> Unit,
    firmware: String = "",
    onChooseFirmwareFile: () -> Unit = {},
) {
    Section(stringResource(R.string.onb_prod_keys)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusDot(
                when {
                    uiState.keysOk -> NxPalette.Ready
                    uiState.keysChecking -> NxPalette.Building
                    uiState.keysPath.isNotBlank() -> NxPalette.Danger
                    else -> NxPalette.Unbuilt
                }
            )
            Column(Modifier.weight(1f)) {
                Text(
                    uiState.keysPath.ifEmpty { stringResource(R.string.onb_no_keys_file) },
                    style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                )
                if (uiState.keysDetail.isNotEmpty()) {
                    Hint(uiState.keysDetail)
                }
            }
            if (uiState.keysChecking) {
                CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp, color = NxPalette.Cream)
            } else {
                QuietButton(stringResource(R.string.onb_choose_file), onClick = onChooseKeysFile)
            }
        }
        Hint(stringResource(R.string.onb_keys_common_places))
        // 固件（可选）：整机级的一份。读主机系统数据的游戏才需要它，导入了就用，不导入也能过
        FirmwareRow(
            imported = firmware,
            onImport = onChooseFirmwareFile,
            onClear = {},
        )
        FirmwareHint()
    }
//    if (!uiState.keysOk && !uiState.keysChecking) {
//        Row(verticalAlignment = Alignment.CenterVertically) {
//            Checkbox(checked = uiState.keysSkipped, onCheckedChange = onKeysSkippedChange)
//            Text(stringResource(R.string.onb_skip_keys), style = MaterialTheme.typography.bodyMedium)
//        }
//    }
}

/** 预览：正在检查那个文件（转圈） */
@Preview(name = "Step, checking the keys", widthDp = 1280, heightDp = 300, showBackground = true)
@Composable
private fun KeysStepCheckingPreview() {
    PreviewSurface {
        Column(Modifier.fillMaxWidth()) {
            KeysStep(
                uiState = previewState(ObStep.KEYS, keysChecking = true, keysPath = "/sdcard/switch/prod.keys"),
                onChooseKeysFile = {},
                onKeysSkippedChange = {},
            )
        }
    }
}

/** 预览：那个文件不行（红点与说明） */
@Preview(name = "Step, the keys file is wrong", widthDp = 1280, heightDp = 320, showBackground = true)
@Composable
private fun KeysStepWrongPreview() {
    PreviewSurface {
        Column(Modifier.fillMaxWidth()) {
            KeysStep(
                uiState = previewState(
                    ObStep.KEYS,
                    keysPath = "/sdcard/switch/prod.keys",
                    keysDetail = "prod.keys: no console found",
                ),
                onChooseKeysFile = {},
                onKeysSkippedChange = {},
            )
        }
    }
}

@Composable
internal fun DoneStep() {
    Section(stringResource(R.string.onb_done)) {
        Text(
            stringResource(R.string.onb_done_text) + "\n" +
                stringResource(R.string.onb_done_next),
            style = MaterialTheme.typography.bodyMedium,
        )
        Hint(stringResource(R.string.onb_skipped_steps))
    }
}
