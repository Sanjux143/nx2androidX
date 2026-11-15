package com.nx.android.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nx.android.R
import com.nx.android.app.AppActions
import com.nx.android.core.ui.LocalAppLocale
import com.nx.android.core.ui.PreviewSurface
import com.nx.android.core.ui.components.CapsuleButton
import com.nx.android.core.ui.components.QuietButton
import com.nx.android.core.ui.theme.NxPalette
import com.nx.android.domain.ObStep
import com.nx.android.domain.OnboardingLogic
import com.nx.android.feature.filepicker.PickerMode
import com.nx.android.feature.filepicker.PickerRequest

/**
 * The guided setup, five steps: the permission and the space, the environment's unpack, the keys, the games'
 * folder, done. The gates are OnboardingLogic's; this view only draws and sends intents.
 *
 * 这一层是有状态的（它读 ViewModel，把动作接上）；画的那部分在 [OnboardingContent] 里，是无状态的，
 * 所以预览可以拿假状态单独看它。
 */
@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel, actions: AppActions, onRequest: (PickerRequest) -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val locale = LocalAppLocale.current

    // the back key walks the flow backwards, it does not leave the app
    BackHandler(enabled = uiState.step > ObStep.WELCOME) { viewModel.back() }

    OnboardingContent(
        uiState = uiState,
        actions = OnboardingActions(
            onNext = { viewModel.next() },
            onBack = { viewModel.back() },
            onOpenLibrary = { viewModel.openLibrary() },
            onAskStorage = { actions.onAskStorage() },
            onAskBattery = { actions.onAskBattery() },
            onUnpack = { viewModel.unpack() },
            // the picker's own title is a word of the app's language: it is read here, not in the step
            onChooseKeysFile = {
                onRequest(PickerRequest(PickerMode.FILE, setOf("keys"), uiState.keysStartDir,
                    locale.str(R.string.onb_choose_prod_keys),
                    onPicked = { f -> viewModel.chooseKeys(f.path) }))
            },
            onKeysSkippedChange = { if (it) viewModel.skipKeys() else viewModel.unskipKeys() },
            firmware = actions.firmware(),
            onChooseFirmwareFile = {
                onRequest(PickerRequest(PickerMode.FILE, setOf("zip"), null,
                    locale.str(R.string.fw_pick),
                    onPicked = { f -> actions.onImportFirmware(f) }))
            },
        ),
    )
}

/** 页面上的动作：下一步、上一步、进入游戏库、要权限、要电池豁免、解包、选 keys、跳过 keys */
internal data class OnboardingActions(
    val onNext: () -> Unit,
    val onBack: () -> Unit,
    val onOpenLibrary: () -> Unit,
    val onAskStorage: () -> Unit,
    val onAskBattery: () -> Unit,
    val onUnpack: () -> Unit,
    val onChooseKeysFile: () -> Unit,
    val onKeysSkippedChange: (Boolean) -> Unit,
    // 固件（可选）：整机级的一份，导入 zip 解到 App 的私有目录
    val firmware: String = "",
    val onChooseFirmwareFile: () -> Unit = {},
)

/**
 * 引导页画的那一层：头（走到哪一步）、那一步、尾（上一步与这一步的主动作）。
 *
 * 无状态——只有 [uiState] 与动作；闸门（这一步能不能往下走）是纯函数算的。
 */
@Composable
internal fun OnboardingContent(uiState: OnboardingUiState, actions: OnboardingActions) {
    val can = OnboardingLogic.canAct(uiState.step, uiState.facts)

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
           verticalArrangement = Arrangement.spacedBy(14.dp)) {

        // the header: where the player is
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stepTitle(uiState.step), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.onb_step_of, uiState.step.ordinal + 1, ObStep.entries.size - 1),
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (s in ObStep.entries) {
                val i = s.ordinal
                Box(
                Modifier.width(if (i == uiState.step.ordinal) 22.dp else 8.dp).height(8.dp)
                        .background(
                            when {
                                i < uiState.step.ordinal -> NxPalette.Ready
                                i == uiState.step.ordinal -> NxPalette.Cream
                                else -> NxPalette.Unbuilt.copy(alpha = 0.4f)
                            },
                            RoundedCornerShape(50)
                        )
                )
            }
        }

        Box(Modifier.weight(1f)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (uiState.step) {
                    ObStep.WELCOME -> WelcomeStep(uiState.facts, actions.onAskStorage, actions.onAskBattery)
                    ObStep.ENV -> EnvStep(uiState, actions.onUnpack)
                    ObStep.KEYS -> KeysStep(uiState, actions.onChooseKeysFile, actions.onKeysSkippedChange,
                        actions.firmware, actions.onChooseFirmwareFile)
                    ObStep.DONE -> DoneStep()
                }
            }
        }

        // the footer: back and the step's main action
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (uiState.step > ObStep.WELCOME) {
                QuietButton(stringResource(R.string.common_back), actions.onBack)
            }
            Spacer(Modifier.weight(1f))
            CapsuleButton(
                text = primaryLabel(uiState.step, can),
                onClick = { if (uiState.step == ObStep.DONE) actions.onOpenLibrary() else actions.onNext() },
                enabled = can,
            )
        }
    }
}

@Composable
private fun stepTitle(s: ObStep): String = when (s) {
    ObStep.WELCOME -> stringResource(R.string.onb_welcome, stringResource(R.string.app_name))
    ObStep.ENV -> stringResource(R.string.onb_prepare_env)
    ObStep.KEYS -> "prod.keys"
    ObStep.DONE -> stringResource(R.string.onb_all_ready)
}

@Composable
private fun primaryLabel(s: ObStep, can: Boolean): String = when (s) {
    ObStep.WELCOME -> stringResource(R.string.common_start)
    ObStep.ENV -> if (can) stringResource(R.string.common_continue) else stringResource(R.string.onb_waiting_env)
    ObStep.KEYS -> if (can) stringResource(R.string.common_continue) else stringResource(R.string.onb_waiting_check)
    ObStep.DONE -> stringResource(R.string.onb_enter_library)
}

/** 预览：第一步（权限与空间） */
@Preview(name = "Step, welcome", widthDp = 1280, heightDp = 800, showBackground = true)
@Composable
private fun OnboardingWelcomePreview() {
    PreviewSurface {
        OnboardingContent(previewState(ObStep.WELCOME), previewActions())
    }
}

/** 预览：第二步（环境还没准备好，主动作是灰的） */
@Preview(name = "Step, environment", widthDp = 1280, heightDp = 800, showBackground = true)
@Composable
private fun OnboardingEnvPreview() {
    PreviewSurface {
        OnboardingContent(previewState(ObStep.ENV), previewActions())
    }
}

/** 预览：第三步（keys） */
@Preview(name = "Step, keys", widthDp = 1280, heightDp = 800, showBackground = true)
@Composable
private fun OnboardingKeysPreview() {
    PreviewSurface {
        OnboardingContent(previewState(ObStep.KEYS), previewActions())
    }
}

/** 预览：最后一步（进入游戏库） */
@Preview(name = "Step, done", widthDp = 1280, heightDp = 800, showBackground = true)
@Composable
private fun OnboardingDonePreview() {
    PreviewSurface {
        OnboardingContent(previewState(ObStep.DONE), previewActions())
    }
}
