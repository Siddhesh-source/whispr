package dev.whispr.android.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.whispr.android.files.PublicFiles
import dev.whispr.data.backup.BackupState
import dev.whispr.data.backup.ChatBackup
import dev.whispr.data.backup.RestoreResult
import dev.whispr.domain.repository.SettingsRepository
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The last backup attempt in this process. */
sealed interface BackupRun {
    data object Idle : BackupRun
    data object Working : BackupRun
    data class Saved(val where: String) : BackupRun
    data object NeedsFolder : BackupRun
    data object Failed : BackupRun
}

/**
 * Writes encrypted chat backups where they outlive the app: Downloads/Whispr
 * (or the folder picked in Settings). Once a day when the app runs, and on
 * demand. Only the two newest backups in Downloads/Whispr are kept.
 */
@Singleton
class BackupController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backup: ChatBackup,
    private val settings: SettingsRepository,
) {
    private val mutex = Mutex()
    private val runState = MutableStateFlow<BackupRun>(BackupRun.Idle)
    val run: StateFlow<BackupRun> = runState.asStateFlow()

    fun observe() = backup.observe()

    suspend fun backUpNow(): BackupRun = mutex.withLock {
        runState.value = BackupRun.Working
        val tmp = File(context.cacheDir, "backup-out.wbk")
        val result = try {
            backup.write(FileOutputStream(tmp))
            val folder = settings.observePrivacy().first().saveFolder
            when (val r = PublicFiles.save(context, tmp, "$PREFIX${LocalDate.now()}.wbk", MIME, folder)) {
                is PublicFiles.Result.Saved -> {
                    if (folder == null) PublicFiles.prune(context, PREFIX, KEEP)
                    BackupRun.Saved(r.where)
                }
                PublicFiles.Result.NeedsFolder -> BackupRun.NeedsFolder
                PublicFiles.Result.Failed -> BackupRun.Failed
            }
        } catch (_: IOException) {
            BackupRun.Failed
        } finally {
            tmp.delete()
        }
        runState.value = result
        result
    }

    /** Called on start: backs up if backups are on and the last one is a day old. */
    suspend fun backUpIfDue(now: Long = System.currentTimeMillis()) {
        val s = backup.observe().first()
        if (!s.enabled) return
        val last = s.lastBackupAt
        if (last == null || now - last >= DAY_MS) backUpNow()
    }

    private companion object {
        const val PREFIX = "whispr-backup-"
        const val MIME = "application/octet-stream"
        const val KEEP = 2
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}

data class BackupUiState(
    val state: BackupState = BackupState(),
    val run: BackupRun = BackupRun.Idle,
    val saveFolder: String? = null,
    /** Shown once when backups are turned on, and on request. */
    val showingKey: String? = null,
)

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val controller: BackupController,
    private val backup: ChatBackup,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val key = MutableStateFlow<String?>(null)

    val state: StateFlow<BackupUiState> = combine(
        controller.observe(),
        controller.run,
        settings.observePrivacy(),
        key,
    ) { s, run, privacy, k -> BackupUiState(s, run, privacy.saveFolder, k) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), BackupUiState())

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                key.value = backup.enable()
                controller.backUpNow()
            } else {
                backup.disable()
            }
        }
    }

    fun backUpNow() {
        viewModelScope.launch { controller.backUpNow() }
    }

    fun showKey() {
        viewModelScope.launch { key.value = backup.recoveryKey() }
    }

    fun hideKey() {
        key.value = null
    }

    /** A folder picked with the system picker; we keep access to it across restarts. */
    fun setFolder(context: Context, uri: Uri?) {
        viewModelScope.launch {
            if (uri != null) {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            settings.setSaveFolder(uri?.toString())
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

sealed interface RestoreUiState {
    data object Idle : RestoreUiState

    /** A file was picked; waiting for the recovery key. */
    data class NeedsKey(val uri: Uri, val wrongKey: Boolean = false) : RestoreUiState
    data object Working : RestoreUiState
    data object Damaged : RestoreUiState

    /** The app restarts into the restored account. */
    data object Done : RestoreUiState
}

@HiltViewModel
class RestoreViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backup: ChatBackup,
) : ViewModel() {
    private val ui = MutableStateFlow<RestoreUiState>(RestoreUiState.Idle)
    val state: StateFlow<RestoreUiState> = ui.asStateFlow()

    fun picked(uri: Uri?) {
        ui.value = uri?.let { RestoreUiState.NeedsKey(it) } ?: RestoreUiState.Idle
    }

    fun restore(recoveryKey: String) {
        val uri = (ui.value as? RestoreUiState.NeedsKey)?.uri ?: return
        ui.value = RestoreUiState.Working
        viewModelScope.launch {
            val result = try {
                context.contentResolver.openInputStream(uri)?.use { backup.stage(it, recoveryKey) }
                    ?: RestoreResult.Damaged
            } catch (_: IOException) {
                RestoreResult.Damaged
            } catch (_: SecurityException) {
                RestoreResult.Damaged
            }
            ui.value = when (result) {
                RestoreResult.Ok -> RestoreUiState.Done
                RestoreResult.WrongKey -> RestoreUiState.NeedsKey(uri, wrongKey = true)
                RestoreResult.Damaged -> RestoreUiState.Damaged
            }
        }
    }

    fun dismiss() {
        ui.value = RestoreUiState.Idle
    }
}
