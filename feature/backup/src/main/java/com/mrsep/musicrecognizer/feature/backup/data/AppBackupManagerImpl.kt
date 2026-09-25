package com.mrsep.musicrecognizer.feature.backup.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.work.WorkManager
import coil3.imageLoader
import com.mrsep.musicrecognizer.core.common.di.ApplicationScope
import com.mrsep.musicrecognizer.core.common.di.IoDispatcher
import com.mrsep.musicrecognizer.core.common.util.getAppVersionCode
import com.mrsep.musicrecognizer.core.data.PersistentStoreLock
import com.mrsep.musicrecognizer.core.data.enqueued.AudioSampleDataSource
import com.mrsep.musicrecognizer.core.database.ApplicationDatabase
import com.mrsep.musicrecognizer.core.database.ApplicationDatabase.Companion.DATABASE_NAME
import com.mrsep.musicrecognizer.core.datastore.UserPreferencesProto
import com.mrsep.musicrecognizer.core.domain.maintenance.DataMaintenanceOperation
import com.mrsep.musicrecognizer.core.domain.recognition.RecognitionInteractor
import com.mrsep.musicrecognizer.core.domain.recognition.model.RecognitionStatus
import com.mrsep.musicrecognizer.feature.backup.AppBackupManager
import com.mrsep.musicrecognizer.feature.backup.BackupEntry
import com.mrsep.musicrecognizer.feature.backup.BackupMetadata
import com.mrsep.musicrecognizer.feature.backup.BackupMetadataResult
import com.mrsep.musicrecognizer.feature.backup.BackupResult
import com.mrsep.musicrecognizer.feature.backup.RestoreStaging
import com.mrsep.musicrecognizer.feature.backup.RestoreResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import androidx.work.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import kotlin.io.path.exists

private const val TAG = "AppBackupManagerImpl"

internal class AppBackupManagerImpl @Inject constructor(
    private val database: ApplicationDatabase,
    private val audioSampleDataSource: AudioSampleDataSource,
    private val userPreferencesDataStore: DataStore<UserPreferencesProto>,
    private val storeLock: PersistentStoreLock,
    private val recognitionInteractor: RecognitionInteractor,
    @ApplicationContext private val appContext: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope,
    private val json: Json,
) : AppBackupManager {

    private val restoreCoroutineContext = appScope.coroutineContext + ioDispatcher

    override suspend fun estimateAppDataSize(): Map<BackupEntry, Long> {
        return withContext(ioDispatcher) {
            val databaseSize = database.getDataSize()
            val recordingsSize = audioSampleDataSource.getTotalSize()
            val preferencesSize = userPreferencesDataStore.data.first().serializedSize.toLong()
            mapOf(
                BackupEntry.Data to databaseSize + recordingsSize,
                BackupEntry.Preferences to preferencesSize,
            )
        }
    }

    override suspend fun backup(
        destination: Uri,
        entries: Set<BackupEntry>,
    ): BackupResult = withContext(ioDispatcher) {
        check(entries.isNotEmpty()) { "At least one BackupEntry must be provided" }
        val outputStream = try {
            requireNotNull(appContext.contentResolver.openOutputStream(destination))
        } catch (_: Exception) {
            return@withContext BackupResult.FileNotFound
        }
        try {
            outputStream.buffered().use { buffered ->
                stopOngoingRecognition()
                storeLock.withExclusive(DataMaintenanceOperation.Backup) {
                    ZipOutputStream(buffered).use { zipOutputStream ->
                        writeMetadata(zipOutputStream, entries.toSet())
                        if (entries.contains(BackupEntry.Data)) {
                            exportDatabase(zipOutputStream)
                            exportRecordings(zipOutputStream)
                        }
                        if (entries.contains(BackupEntry.Preferences)) {
                            exportUserPreferences(zipOutputStream)
                        }
                    }
                    BackupResult.Success
                }
            }
        } catch (e: CancellationException) {
            deleteUnfinishedBackup(destination)
            throw e
        } catch (e: Exception) { // potential ZipException or IOException
            Log.e(TAG, "Fatal error while creating backup", e)
            deleteUnfinishedBackup(destination)
            BackupResult.UnhandledError(message = e.message)
        }
    }

    private fun deleteUnfinishedBackup(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(appContext.contentResolver, uri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete unfinished backup file", e)
        }
    }

    private suspend fun exportDatabase(zipOutputStream: ZipOutputStream) {
        currentCoroutineContext().ensureActive()
        val appDatabasePath = appContext.getDatabasePath(DATABASE_NAME).toPath()
        check(appDatabasePath.exists()) { "App database file is not found" }
        check(database.checkoutWithRetry()) { "DB checkpoint was not performed, database is busy" }
        with(zipOutputStream) {
            putNextEntry(ZipEntry(DATABASE_ZIP_ENTRY))
            Files.copy(appDatabasePath, this)
            closeEntry()
        }
    }

    private suspend fun exportRecordings(zipOutputStream: ZipOutputStream) {
        val recordings = audioSampleDataSource.getFiles()
        with(zipOutputStream) {
            for (recording in recordings) {
                if (!recording.isFile) continue
                currentCoroutineContext().ensureActive()
                putNextEntry(ZipEntry("$RECORDINGS_DIR_ZIP_ENTRY${recording.name}"))
                Files.copy(recording.toPath(), this)
                closeEntry()
            }
        }
    }

    private suspend fun exportUserPreferences(zipOutputStream: ZipOutputStream) {
        currentCoroutineContext().ensureActive()
        val preferences = userPreferencesDataStore.data.first()
        with(zipOutputStream) {
            putNextEntry(ZipEntry(PREFERENCES_ZIP_ENTRY))
            preferences.writeTo(this)
            closeEntry()
        }
    }

    override suspend fun readBackupMetadata(
        source: Uri,
    ): BackupMetadataResult = withContext(ioDispatcher) {
        try {
            val inputStream = try {
                requireNotNull(appContext.contentResolver.openInputStream(source))
            } catch (_: Exception) {
                return@withContext BackupMetadataResult.FileNotFound
            }
            ZipInputStream(inputStream.buffered()).use { zipInputStream ->
                var metadata: BackupMetadata? = null
                val foundEntries = mutableMapOf<BackupEntry, Long>()

                var currentEntry: ZipEntry? = zipInputStream.nextEntry
                // The first entry must be metadata file
                if (currentEntry?.name == METADATA_ZIP_ENTRY) {
                    metadata = readMetadata(zipInputStream)
                }
                if (metadata == null) return@withContext BackupMetadataResult.NotBackupFile
                zipInputStream.closeEntry()

                currentEntry = zipInputStream.nextEntry
                while (currentEntry != null) {
                    // Metadata fields in the ZipEntry are only available after the entry data has been read
                    // This is because the metadata follows the data in the Zip format
                    zipInputStream.closeEntry()
                    when (currentEntry.name) {
                        DATABASE_ZIP_ENTRY -> {
                            foundEntries[BackupEntry.Data] = currentEntry.size
                        }

                        RECORDINGS_DIR_ZIP_ENTRY -> {}  // just skip

                        PREFERENCES_ZIP_ENTRY -> {
                            foundEntries[BackupEntry.Preferences] = currentEntry.size
                        }

                        else -> {
                            findRecordingName(currentEntry)
                                ?: return@withContext BackupMetadataResult.MalformedBackup
                            // Database file must precede audio recordings
                            val dataSize = foundEntries[BackupEntry.Data]
                                ?: return@withContext BackupMetadataResult.MalformedBackup
                            foundEntries[BackupEntry.Data] = dataSize + currentEntry.size
                        }
                    }
                    currentEntry = zipInputStream.nextEntry
                }
                BackupMetadataResult.Success(
                    metadata = metadata,
                    entryUncompressedSize = foundEntries,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error while reading backup metadata", e)
            BackupMetadataResult.UnhandledError(message = e.message)
        }
    }

    override suspend fun restore(
        source: Uri,
        entries: Set<BackupEntry>,
    ): RestoreResult = withContext(restoreCoroutineContext) {
        check(entries.isNotEmpty()) { "At least one BackupEntry must be provided" }
        val inputStream = try {
            requireNotNull(appContext.contentResolver.openInputStream(source))
        } catch (_: Exception) {
            return@withContext RestoreResult.FileNotFound
        }
        var inProgressMarked = false
        try {
            ZipInputStream(inputStream).use { zipInputStream ->
                var metadata: BackupMetadata? = null
                // The first entry must be backup metadata
                val metadataEntry = zipInputStream.nextEntry
                if (metadataEntry?.name == METADATA_ZIP_ENTRY) {
                    metadata = readMetadata(zipInputStream)
                }
                if (metadata == null) return@withContext RestoreResult.NotBackupFile
                if (metadata.appVersionCode > appContext.getAppVersionCode()) {
                    return@withContext RestoreResult.NewerVersionBackup
                }
                zipInputStream.closeEntry()

                stopOngoingRecognition()
                cancelAndPruneBackgroundWork()

                storeLock.withExclusive(DataMaintenanceOperation.Restore) {
                    RestoreStaging.markInProgress(appContext, entries)
                    inProgressMarked = true

                    if (BackupEntry.Data in entries) {
                        clearAppData()
                    }

                    var databaseStaged = false
                    while (true) {
                        val entry = zipInputStream.nextEntry ?: break
                        currentCoroutineContext().ensureActive()
                        when (entry.name) {
                            DATABASE_ZIP_ENTRY -> if (entries.contains(BackupEntry.Data)) {
                                importDatabaseToStaging(zipInputStream)
                                databaseStaged = true
                            }

                            RECORDINGS_DIR_ZIP_ENTRY -> {} // just skip

                            PREFERENCES_ZIP_ENTRY -> if (entries.contains(BackupEntry.Preferences)) {
                                importPreferencesToStaging(zipInputStream)
                            }

                            else -> {
                                val recordingName = findRecordingName(entry)
                                if (recordingName != null) {
                                    if (entries.contains(BackupEntry.Data) && databaseStaged) {
                                        importRecordingToStaging(zipInputStream, recordingName)
                                    }
                                } else {
                                    val msg = "Unknown backup entry \"${entry.name}\""
                                    Log.w(TAG, msg)
                                }
                            }
                        }
                        zipInputStream.closeEntry()
                    }
                    withContext(NonCancellable) {
                        RestoreStaging.markReady(appContext)
                    }
                }
            }
            RestoreResult.Success(appRestartRequired = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error while restoring data from backup", e)
            RestoreResult.UnhandledError(appRestartRequired = inProgressMarked, message = e.message)
        }
    }

    private suspend fun stopOngoingRecognition() {
        if (recognitionInteractor.status.value is RecognitionStatus.Recognizing) {
            recognitionInteractor.cancelAndJoin()
        }
    }

    private suspend fun cancelAndPruneBackgroundWork() {
        val workManager = WorkManager.getInstance(appContext)
        workManager.cancelAllWork().await()
        workManager.pruneWork().await()
    }

    private suspend fun clearAppData() {
        database.clearAllTables()
        audioSampleDataSource.deleteAll()
        with(appContext.imageLoader) {
            diskCache?.clear()
            memoryCache?.clear()
        }
    }

    private fun importDatabaseToStaging(zipInputStream: ZipInputStream) {
        val databasePath = RestoreStaging.stagingDatabaseFile(appContext).toPath()
        Files.createDirectories(databasePath.parent)
        Files.copy(zipInputStream, databasePath, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun importRecordingToStaging(zipInputStream: ZipInputStream, filename: String) {
        val targetDir = RestoreStaging.stagingRecordingsDir(appContext)
        targetDir.mkdirs()
        Files.copy(
            zipInputStream,
            File(targetDir, filename).toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
    }

    private fun importPreferencesToStaging(zipInputStream: ZipInputStream) {
        val preferencesPath = RestoreStaging.stagingPreferencesFile(appContext).toPath()
        Files.createDirectories(preferencesPath.parent)
        Files.copy(zipInputStream, preferencesPath, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun writeMetadata(zipOutputStream: ZipOutputStream, entries: Set<BackupEntry>) {
        val metadata = BackupMetadata(
            backupSignature = BACKUP_VERIFICATION_UUID,
            appVersionCode = appContext.getAppVersionCode(),
            creationDate = Instant.now(),
            entries = entries,
        )
        val prettyJson = Json(json) { prettyPrint = true }
        val metadataJson = prettyJson.encodeToString(metadata)
        with(zipOutputStream) {
            putNextEntry(ZipEntry(METADATA_ZIP_ENTRY))
            write(metadataJson.encodeToByteArray())
            closeEntry()
        }
    }

    private fun readMetadata(zipInputStream: ZipInputStream): BackupMetadata? {
        val metadataJson = zipInputStream.readBytes().decodeToString()
        return try {
            json.decodeFromString<BackupMetadata>(metadataJson)
                .takeIf { it.backupSignature == BACKUP_VERIFICATION_UUID }
        } catch (_: Exception) {
            null
        }
    }

    private fun findRecordingName(entry: ZipEntry): String? {
        return recordingEntryNamePattern.matchEntire(entry.name)?.groups?.get(1)?.value
    }

    companion object {

        private const val PREFERENCES_ZIP_ENTRY = "preferences"
        private const val DATABASE_ZIP_ENTRY = "database"
        private const val METADATA_ZIP_ENTRY = "metadata"
        private const val RECORDINGS_DIR_ZIP_ENTRY = "audio_recordings/"
        private const val BACKUP_VERIFICATION_UUID = "9530d0d1-8023-4c3a-99d0-7cbb084020f1"

        private val recordingEntryNamePattern = Regex("^$RECORDINGS_DIR_ZIP_ENTRY([^/]+)\$")
    }
}
