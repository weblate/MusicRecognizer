package com.mrsep.musicrecognizer.feature.backup.presentation

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrsep.musicrecognizer.core.common.di.IoDispatcher
import com.mrsep.musicrecognizer.core.common.util.AppDateTimeFormatter
import com.mrsep.musicrecognizer.core.domain.preferences.AutoBackupPreferences
import com.mrsep.musicrecognizer.core.domain.preferences.AutoBackupResult
import com.mrsep.musicrecognizer.core.domain.preferences.FavoritesMode
import com.mrsep.musicrecognizer.core.domain.preferences.PreferencesRepository
import com.mrsep.musicrecognizer.core.domain.track.model.MusicService
import com.mrsep.musicrecognizer.feature.backup.AppBackupManager
import com.mrsep.musicrecognizer.feature.backup.AppRestartManager
import com.mrsep.musicrecognizer.feature.backup.AutoBackupScheduler
import com.mrsep.musicrecognizer.feature.backup.BackupEntry
import com.mrsep.musicrecognizer.feature.backup.BackupMetadataResult
import com.mrsep.musicrecognizer.feature.backup.BackupNotificationHelper
import com.mrsep.musicrecognizer.feature.backup.BackupResult
import com.mrsep.musicrecognizer.feature.backup.CsvExportParams
import com.mrsep.musicrecognizer.feature.backup.CsvExporter
import com.mrsep.musicrecognizer.feature.backup.CsvField
import com.mrsep.musicrecognizer.feature.backup.ExportResult
import com.mrsep.musicrecognizer.feature.backup.RestoreResult
import com.mrsep.musicrecognizer.feature.backup.TrackField
import com.mrsep.musicrecognizer.feature.backup.TrackLinkField
import com.mrsep.musicrecognizer.feature.backup.data.BackupTreeDocuments
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import javax.inject.Inject

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
internal class BackupRestoreViewModel @Inject constructor(
    private val appBackupManager: AppBackupManager,
    private val appRestartManager: AppRestartManager,
    private val csvExporter: CsvExporter,
    private val preferencesRepository: PreferencesRepository,
    private val autoBackupScheduler: AutoBackupScheduler,
    private val treeDocuments: BackupTreeDocuments,
    private val dateTimeFormatter: AppDateTimeFormatter,
    private val backupNotificationHelper: BackupNotificationHelper,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _backupUiState = MutableStateFlow<BackupUiState?>(null)
    val backupState = _backupUiState.asStateFlow()

    private val _restoreUiState = MutableStateFlow<RestoreUiState?>(null)
    val restoreUiState = _restoreUiState.asStateFlow()

    private val _csvExportUiState = MutableStateFlow<CsvExportUiState?>(null)
    val csvExportUiState = _csvExportUiState.asStateFlow()

    private val _events = Channel<BackupRestoreEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // Keep slow SAF related query separated
    private val currentAutoBackupTreeUri = MutableStateFlow<String?>(null)
    private val autoBackupLocationName = currentAutoBackupTreeUri
        .mapLatest { uriString ->
            uriString?.takeIf { it.isNotEmpty() }?.toUri()?.let { treeUri ->
                treeDocuments.friendlyName(treeUri)
            }
        }
        .flowOn(ioDispatcher)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            null
        )

    val autoBackupUiState = combine(
        preferencesRepository.userPreferencesFlow,
        autoBackupScheduler.isAutoBackupRunning(),
        autoBackupLocationName,
    ) { preferences, isRunning, locationName ->
        val uri = preferences.autoBackup.treeUri
        val name = locationName.takeIf { currentAutoBackupTreeUri.value == uri }
        currentAutoBackupTreeUri.value = uri
        AutoBackupUiState.Ready(
            preferences = preferences.autoBackup,
            lastResult = preferences.autoBackupLastResult,
            isRunning = isRunning,
            hasWritePermission = uri.takeIf { it.isNotEmpty() }?.toUri()
                ?.let(treeDocuments::hasWritePermission) ?: false,
            locationName = name,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AutoBackupUiState.Loading,
    )

    val dateFormatter: AppDateTimeFormatter get() = dateTimeFormatter

    private val backupMasterJob = SupervisorJob()
    private val backupScope = viewModelScope + backupMasterJob

    private val restoreMasterJob = SupervisorJob()
    private val restoreScope = viewModelScope + backupMasterJob

    private val csvExportMasterJob = SupervisorJob()
    private val csvExportScope = viewModelScope + csvExportMasterJob

    /* Backup */

    fun estimateEntriesToBackup() {
        if (backupMasterJob.children.any { !it.isCompleted }) return
        if (_backupUiState.value != null) return
        backupScope.launch {
            _backupUiState.value = BackupUiState.EstimatingEntries
            val entries = appBackupManager.estimateAppDataSize()
            _backupUiState.value = BackupUiState.Ready(
                entriesUncompressedSize = entries,
                selectedEntries = entries.keys
            )
        }
    }

    fun backup(uri: Uri) {
        if (backupMasterJob.children.any { !it.isCompleted }) return
        val currentState = _backupUiState.value
        if (currentState !is BackupUiState.Ready) return
        if (currentState.selectedEntries.isEmpty()) return
        backupScope.launch {
            _backupUiState.value = BackupUiState.InProgress(uri)
            val backupResult = appBackupManager.backup(uri, currentState.selectedEntries)
            _backupUiState.value = BackupUiState.Result(uri, backupResult)
        }
    }

    fun cancelBackupScopeJobs() {
        viewModelScope.launch {
            backupMasterJob.children.forEach { it.cancelAndJoin() }
            _backupUiState.value = null
        }
    }

    /* Restore */

    fun validateBackup(uri: Uri) {
        if (restoreMasterJob.children.any { !it.isCompleted }) return
        if (_restoreUiState.value != null) return
        restoreScope.launch {
            _restoreUiState.value = RestoreUiState.ValidatingBackup(uri)
            val metadataResult = appBackupManager.readBackupMetadata(uri)
            _restoreUiState.value = RestoreUiState.Ready(
                uri = uri,
                metadata = metadataResult,
                selectedEntries = when (metadataResult) {
                    is BackupMetadataResult.Success -> metadataResult.entryUncompressedSize.keys
                    else -> emptySet()
                }
            )
        }
    }

    fun restore(uri: Uri) {
        if (restoreMasterJob.children.any { !it.isCompleted }) return
        val currentState = restoreUiState.value
        if (currentState !is RestoreUiState.Ready) return
        val metadata = currentState.metadata as? BackupMetadataResult.Success ?: return
        if (currentState.selectedEntries.isEmpty()) return
        check(metadata.entryUncompressedSize.keys.containsAll(currentState.selectedEntries))
        restoreScope.launch {
            _restoreUiState.value = RestoreUiState.InProgress(uri)
            val restoreResult = appBackupManager.restore(uri, currentState.selectedEntries)
            _restoreUiState.value = RestoreUiState.Result(uri, restoreResult)
        }
    }

    fun cancelRestoreScopeJobs() {
        viewModelScope.launch {
            restoreMasterJob.children.forEach { it.cancelAndJoin() }
            _restoreUiState.value = null
        }
    }

    fun restartApplicationOnRestore() {
        appRestartManager.restartApplicationOnRestore()
    }

    fun onChangeSelectedBackupEntry(entry: BackupEntry, selected: Boolean) {
        val currentState = _backupUiState.value
        val currentSelectedEntries = when (currentState) {
            is BackupUiState.Ready -> currentState.selectedEntries
            else -> return
        }
        _backupUiState.value = currentState.copy(
            selectedEntries = currentSelectedEntries
                .run { if (selected) plus(entry) else minus(entry) }
        )
    }

    fun onChangeSelectedRestoreEntry(entry: BackupEntry, selected: Boolean) {
        val currentState = _restoreUiState.value
        val currentSelectedEntries = when (currentState) {
            is RestoreUiState.Ready -> currentState.selectedEntries
            else -> return
        }
        _restoreUiState.value = currentState.copy(
            selectedEntries = currentSelectedEntries
                .run { if (selected) plus(entry) else minus(entry) }
        )
    }

    fun prepareForCsvExport() {
        if (csvExportMasterJob.children.any { !it.isCompleted }) return
        if (_csvExportUiState.value is CsvExportUiState.InProgress) return
        _csvExportUiState.value = CsvExportUiState.Ready()
    }

    fun onChangeExportState(state: CsvExportUiState.Ready) {
        if (_csvExportUiState.value !is CsvExportUiState.Ready) return
        _csvExportUiState.value = state
    }

    fun exportToCsv(destination: Uri) {
        if (csvExportMasterJob.children.any { !it.isCompleted }) return
        val currentState = _csvExportUiState.value
        if (currentState !is CsvExportUiState.Ready) return
        val exportParams = currentState.toExportParams()
        if (exportParams.exportFields.isEmpty()) return
        csvExportScope.launch {
            _csvExportUiState.value = CsvExportUiState.InProgress(destination)
            val exportResult = csvExporter.export(destination, exportParams)
            _csvExportUiState.value = CsvExportUiState.Result(destination, exportResult)
        }
    }

    fun cancelCsvExportScopeJobs() {
        viewModelScope.launch {
            csvExportMasterJob.children.forEach { it.cancelAndJoin() }
            _csvExportUiState.value = null
        }
    }

    /* Automatic backup */

    fun enableAutoBackup() {
        viewModelScope.launch {
            val autoBackup = preferencesRepository.userPreferencesFlow.first().autoBackup
            if (autoBackup.treeUri.isEmpty()) return@launch
            preferencesRepository.setAutoBackupLastResult(null)
            backupNotificationHelper.cancelFailure()
            preferencesRepository.setAutoBackupEnabled(true)
            autoBackupScheduler.schedule(autoBackup.intervalDays, autoBackup.treeUri.toUri())
        }
    }

    fun disableAutoBackup() {
        viewModelScope.launch {
            preferencesRepository.setAutoBackupLastResult(null)
            backupNotificationHelper.cancelFailure()
            preferencesRepository.setAutoBackupEnabled(false)
            autoBackupScheduler.cancel()
        }
    }

    fun onBackupTreePicked(uri: Uri, enable: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            if (!treeDocuments.takePersistableWritePermission(uri)) {
                _events.send(BackupRestoreEvent.PersistableUriPermissionDenied)
                return@launch
            }
            val autoBackup = preferencesRepository.userPreferencesFlow.first().autoBackup
            val oldUri = autoBackup.treeUri
            preferencesRepository.setAutoBackupTreeUri(uri.toString())
            if (oldUri.isNotEmpty() && oldUri != uri.toString()) {
                treeDocuments.releasePersistableWritePermission(oldUri.toUri())
            }
            when {
                enable -> {
                    preferencesRepository.setAutoBackupLastResult(null)
                    backupNotificationHelper.cancelFailure()
                    preferencesRepository.setAutoBackupEnabled(true)
                    autoBackupScheduler.schedule(autoBackup.intervalDays, uri)
                }
                autoBackup.enabled -> {
                    autoBackupScheduler.schedule(autoBackup.intervalDays, uri)
                }
            }
        }
    }

    fun setAutoBackupIntervalDays(days: Int) {
        viewModelScope.launch {
            preferencesRepository.setAutoBackupIntervalDays(days)
            val autoBackup = preferencesRepository.userPreferencesFlow.first().autoBackup
            if (autoBackup.enabled) {
                autoBackupScheduler.schedule(days, autoBackup.treeUri.toUri())
            }
        }
    }

    fun setAutoBackupKeepCount(keepCount: Int) {
        viewModelScope.launch {
            preferencesRepository.setAutoBackupKeepCount(keepCount)
        }
    }

    fun resetLastAutoBackupResult() {
        viewModelScope.launch {
            preferencesRepository.setAutoBackupLastResult(null)
            backupNotificationHelper.cancelFailure()
        }
    }
}

internal sealed class BackupRestoreEvent {
    data object PersistableUriPermissionDenied : BackupRestoreEvent()
}

@Stable
internal sealed class AutoBackupUiState {
    data object Loading : AutoBackupUiState()

    data class Ready(
        val preferences: AutoBackupPreferences,
        val lastResult: AutoBackupResult?,
        val isRunning: Boolean,
        val hasWritePermission: Boolean,
        val locationName: String?,
    ) : AutoBackupUiState()
}

internal fun AutoBackupUiState.Ready.shouldShowError(): Boolean {
    return !isRunning && lastResult is AutoBackupResult.Failure
}

internal fun AutoBackupUiState.Ready.shouldShowStatus(): Boolean {
    return preferences.enabled || shouldShowError()
}

internal fun AutoBackupUiState.Ready.needsTreePickerToEnable(): Boolean {
    return preferences.treeUri.isEmpty() || !hasWritePermission
}

@Stable
internal sealed class RestoreUiState {
    abstract val uri: Uri

    data class ValidatingBackup(override val uri: Uri) : RestoreUiState()

    data class Ready(
        override val uri: Uri,
        val metadata: BackupMetadataResult,
        val selectedEntries: Set<BackupEntry>,
    ) : RestoreUiState()

    data class InProgress(override val uri: Uri) : RestoreUiState()

    data class Result(
        override val uri: Uri,
        val result: RestoreResult,
    ) : RestoreUiState()
}

@Stable
internal sealed class BackupUiState {

    data object EstimatingEntries : BackupUiState()

    data class Ready(
        val entriesUncompressedSize: Map<BackupEntry, Long>,
        val selectedEntries: Set<BackupEntry>,
    ) : BackupUiState()

    data class InProgress(val uri: Uri) : BackupUiState()

    data class Result(
        val uri: Uri,
        val result: BackupResult,
    ) : BackupUiState()
}


@Stable
internal sealed class CsvExportUiState {

    data class Ready(
        val exportFields: List<SelectableCsvField> = SelectableCsvField.DEFAULT_LIST,
        val writeHeader: Boolean = false,
        val exportOnlyFavorites: Boolean = false,
    ) : CsvExportUiState() {

        fun toExportParams() = CsvExportParams(
            exportFields = exportFields
                .mapNotNull { selectable -> selectable.field.takeIf { selectable.selected } },
            writeHeader = writeHeader,
            favoritesMode = if (exportOnlyFavorites) FavoritesMode.OnlyFavorites else FavoritesMode.All
        )
    }

    data class InProgress(val uri: Uri) : CsvExportUiState()

    data class Result(
        val uri: Uri,
        val result: ExportResult,
    ) : CsvExportUiState()
}

@Stable
internal data class SelectableCsvField(
    val field: CsvField,
    val selected: Boolean,
) {
    companion object {
        val DEFAULT_LIST get() = (TrackField.entries + MusicService.entries.map(::TrackLinkField))
            .map { field ->
                val selected = field is TrackField &&
                        field == TrackField.TITLE ||
                        field == TrackField.ARTIST
                SelectableCsvField(field, selected = selected)
            }
    }
}
