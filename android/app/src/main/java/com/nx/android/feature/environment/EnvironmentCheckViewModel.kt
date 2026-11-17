package com.nx.android.feature.environment

import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nx.android.domain.EnvCheck
import com.nx.android.domain.PhantomCheck
import com.nx.android.domain.ProfilesPackage
import com.nx.android.appstate.EnvCheckState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

data class EnvironmentCheckUiState(
    val envReady: Boolean = false,
    val keysPath: String = "",
    val keysOk: Boolean = false,
    val known: Boolean = false,
    val chip: EnvCheck.Chip = EnvCheck.Chip.CHECKING,
    val running: Boolean = false,
    val checked: Boolean = false,
    val rows: List<EnvCheck.Row> = emptyList(),
    val noOutput: Boolean = false,
    val phantom: PhantomCheck.Status = PhantomCheck.Status.UNKNOWN,
    val phantomKnown: Boolean = false,
    val profilesCount: Int = 0,
    val profilesLabel: String = "",
    val profilesDiffer: Boolean = false,
    val importingProfiles: Boolean = false,
    val packageUpdate: ProfilesPackage.Update = ProfilesPackage.Update.Idle,
)

@HiltViewModel
class EnvironmentCheckViewModel @Inject constructor(
    val checks: EnvCheckState,
) : ViewModel() {
    val uiState: StateFlow<EnvironmentCheckUiState> = snapshotFlow {
        EnvironmentCheckUiState(
            envReady = checks.envReady,
            keysPath = checks.keysPath,
            keysOk = checks.keysOk,
            known = checks.known,
            chip = checks.chip,
            running = checks.running,
            checked = checks.checked,
            rows = checks.rows,
            noOutput = checks.noOutput,
            phantom = checks.phantom,
            phantomKnown = checks.phantomKnown,
            profilesCount = checks.profilesCount,
            profilesLabel = checks.profilesLabel,
            profilesDiffer = checks.profilesDiffer,
            importingProfiles = checks.importingProfiles,
            packageUpdate = checks.packageUpdate,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EnvironmentCheckUiState())

    fun refresh() = checks.refresh()
    fun runDoctor() = checks.runDoctor()
    fun refreshPhantom() = checks.refreshPhantom()

    /** Fetches the package of this build over the network and imports it */
    fun updateProfiles(onDone: (Boolean) -> Unit) = checks.updateProfiles(onDone)

    /** Calls the download off */
    fun cancelPackageUpdate() = checks.cancelPackageUpdate()
}
