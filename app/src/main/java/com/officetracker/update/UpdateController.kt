package com.officetracker.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class UpdateUiState(
    val available: UpdateInfo? = null,
    val checking: Boolean = false,
    val progress: Float? = null,
    val downloaded: File? = null,
    val message: String? = null,
    val showDialog: Boolean = false,
)

/** App-wide update state, so the prompt and the Profile screen share one source of truth. */
class UpdateController(private val manager: UpdateManager, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()
    private var job: Job? = null

    fun check(force: Boolean) {
        if (_state.value.checking) return
        // Already downloaded or showing: don't ask again, just re-show the dialog.
        if (!force && _state.value.available != null) {
            _state.update { it.copy(showDialog = true) }
            return
        }
        _state.update { it.copy(checking = true, message = null) }
        scope.launch {
            manager.checkForUpdate(force)
                .onSuccess { info ->
                    _state.update {
                        it.copy(
                            checking = false, available = info, showDialog = info != null,
                            message = if (force && info == null) "You have the latest version." else null,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(checking = false, message = if (force) "Could not check for updates: ${e.message}" else null) }
                }
        }
    }

    fun download() {
        val info = _state.value.available ?: return
        if (job?.isActive == true) return
        _state.update { it.copy(progress = 0f, message = null) }
        job = scope.launch {
            manager.download(info) { p -> _state.update { it.copy(progress = p) } }
                .onSuccess { file -> _state.update { it.copy(progress = null, downloaded = file) } }
                .onFailure { e -> _state.update { it.copy(progress = null, message = e.message ?: "Download failed.") } }
        }
    }

    fun install(): Boolean {
        val file = _state.value.downloaded ?: return false
        if (!manager.canInstallPackages()) return false
        manager.install(file)
        return true
    }

    fun installPermissionIntent() = manager.installPermissionIntent()

    fun dismiss(skipVersion: Boolean) {
        val version = _state.value.available?.version
        _state.update { it.copy(showDialog = false) }
        if (skipVersion && version != null) scope.launch { manager.skip(version) }
    }

    fun reopen() = _state.update { it.copy(showDialog = it.available != null) }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
