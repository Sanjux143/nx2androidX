package com.nx.android.feature.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.nx.android.R
import com.nx.android.appstate.RuntimeUpdatePhase
import com.nx.android.appstate.RuntimeUpdateState

@Composable
fun RuntimeUpdateDialog(state: RuntimeUpdateState, onRetry: () -> Unit,
                        onUseCurrentProfile: () -> Unit, onFinished: () -> Unit) {
    LaunchedEffect(state.phase) {
        if (state.phase == RuntimeUpdatePhase.SUCCEEDED) {
            state.dismiss()
            onFinished()
        }
    }
    if (state.phase != RuntimeUpdatePhase.RUNNING && state.phase != RuntimeUpdatePhase.FAILED) return
    AlertDialog(
        onDismissRequest = { state.dismiss() },
        properties = DialogProperties(dismissOnBackPress = !state.running, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.runtime_update)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(state.name)
                if (state.running) {
                    CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(8.dp))
                    Text(stringResource(R.string.runtime_updating))
                } else {
                    // the reason the update stopped: the porter's own line says why (a changed profile, a missing
                    // tool, a failed compile); without it the player only sees that it failed
                    val reason = state.reason
                    if (reason.isNotBlank()) Text(reason)
                    else Text(stringResource(if (state.interrupted) R.string.runtime_interrupted else R.string.runtime_update_failed))
                    // 档案对不上：说清继续意味着什么（重建用当前档案，游戏表现可能与移植时不同）
                    if (state.profileDrift) {
                        Text(stringResource(R.string.runtime_profile_drift_hint),
                             style = MaterialTheme.typography.bodySmall)
                    }
                    // a pack that could not be checked is rebuilt with the profile at hand: if the game misbehaves
                    // afterwards, porting it again is the way out
                    Text(stringResource(R.string.runtime_update_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (!state.running) {
                // 档案对不上是确定性的失败：重试还是同一个原因，只有「继续」（用当前档案）这一条路。
                // 别的失败（编译失败、工具缺失）重试有意义。
                if (state.profileDrift) {
                    TextButton(onClick = onUseCurrentProfile) { Text(stringResource(R.string.common_continue)) }
                } else {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.runtime_retry)) }
                }
            }
        },
        dismissButton = {
            // 重新移植在游戏详情里做：这个弹框只管这一次更新
            if (!state.running) TextButton(onClick = { state.dismiss() }) { Text(stringResource(R.string.common_close)) }
        },
    )
}
