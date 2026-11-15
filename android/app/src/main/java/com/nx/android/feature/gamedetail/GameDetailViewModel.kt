package com.nx.android.feature.gamedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.nx.android.data.saves.SaveIo
import com.nx.android.domain.saves.SaveRepository
import com.nx.android.domain.playback.RunOptionsRepository
import com.nx.android.domain.playback.RunOptionsSnapshot
import javax.inject.Inject
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Only the small, restorable identity of the open detail panel belongs in its ViewModel. */
data class GameDetailUiState(
    val titleId: String? = null,
    val runOptions: RunOptionsSnapshot? = null,
    val runOptionsFailure: Throwable? = null,
)

@HiltViewModel
class GameDetailViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    val saveRepository: SaveRepository,
    val saveIo: SaveIo,
    private val runOptionsRepository: RunOptionsRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        GameDetailUiState(savedStateHandle[KEY_TITLE_ID]),
    )
    val uiState: StateFlow<GameDetailUiState> = mutableUiState
    private var activePack: File? = null
    private var runOptionsJob: Job? = null

    fun open(titleId: String, pack: File?) {
        val changed = mutableUiState.value.titleId != titleId
        savedStateHandle[KEY_TITLE_ID] = titleId
        activePack = pack
        if (changed) mutableUiState.value = GameDetailUiState(titleId)
        if (pack != null) {
            runOptionsJob?.cancel()
            runOptionsJob = viewModelScope.launch {
                runCatching { runOptionsRepository.read(pack) }.fold(
                    onSuccess = { snapshot ->
                        if (mutableUiState.value.titleId == titleId) mutableUiState.value =
                            mutableUiState.value.copy(runOptions = snapshot, runOptionsFailure = null)
                    },
                    onFailure = { failure ->
                        if (failure is CancellationException) throw failure
                        if (mutableUiState.value.titleId == titleId) mutableUiState.value =
                            mutableUiState.value.copy(runOptionsFailure = failure)
                    },
                )
            }
        }
    }

    fun selectLanguage(language: String?) = updateRunOptions(language, mutableUiState.value.runOptions?.driver)

    fun selectDriver(driver: String?) = updateRunOptions(mutableUiState.value.runOptions?.language, driver)

    private fun updateRunOptions(language: String?, driver: String?) {
        val pack = activePack ?: return
        val titleId = mutableUiState.value.titleId ?: return
        runOptionsJob?.cancel()
        runOptionsJob = viewModelScope.launch {
            runCatching { runOptionsRepository.update(pack, language, driver) }.fold(
                onSuccess = { snapshot ->
                    if (mutableUiState.value.titleId == titleId) mutableUiState.value =
                        mutableUiState.value.copy(runOptions = snapshot, runOptionsFailure = null)
                },
                onFailure = { failure ->
                    if (failure is CancellationException) throw failure
                    if (mutableUiState.value.titleId == titleId) mutableUiState.value =
                        mutableUiState.value.copy(runOptionsFailure = failure)
                },
            )
        }
    }

    fun close(titleId: String) {
        if (mutableUiState.value.titleId != titleId) return
        runOptionsJob?.cancel()
        runOptionsJob = null
        activePack = null
        savedStateHandle[KEY_TITLE_ID] = null
        mutableUiState.value = GameDetailUiState()
    }

    private companion object {
        const val KEY_TITLE_ID = "gameDetailTitleId"
    }
}
