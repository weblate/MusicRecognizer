package com.mrsep.musicrecognizer.feature.backup

import android.content.Context
import android.util.Log
import com.mrsep.musicrecognizer.core.data.enqueued.AudioSampleFiles
import com.mrsep.musicrecognizer.core.database.ApplicationDatabase
import com.mrsep.musicrecognizer.core.datastore.USER_PREFERENCES_STORE
import java.io.File

/**
 * Crash-safe restore staging, applied from [android.app.Application.attachBaseContext]
 * before Hilt opens Room/DataStore.
 */
object RestoreStaging {

    private const val TAG = "RestoreStaging"
    private const val FLAG_RESTORE_IN_PROGRESS = "restore_in_progress"
    private const val FLAG_STAGING_READY = "restore_staging_ready"
    private const val STAGING_DIR = "restore_staging"
    private const val RECORDINGS_DIR = "audio_recordings"

    fun recoverIfNeeded(context: Context) {
        val inProgressFlagFile = flagFileInProgress(context)
        if (!inProgressFlagFile.exists()) return
        val entries = readEntries(context).ifEmpty { BackupEntry.entries.toSet() }
        val readyFlagFile = flagFileStagingReady(context)
        if (readyFlagFile.exists()) {
            Log.i(TAG, "Applying restore staging for $entries")
            applyStaging(context, entries)
        } else {
            Log.w(TAG, "Interrupted restore detected, deleting user data for $entries")
            deleteOriginals(context, entries)
        }
        stagingDir(context).deleteRecursively()
        readyFlagFile.delete()
        inProgressFlagFile.delete()
    }

    internal fun markInProgress(context: Context, entries: Set<BackupEntry>) {
        stagingDir(context).deleteRecursively()
        flagFileStagingReady(context).delete()
        flagFileInProgress(context).writeText(entries.joinToString("\n") { it.name })
    }

    internal fun markReady(context: Context) {
        flagFileStagingReady(context).writeText("")
    }

    internal fun stagingDir(context: Context): File =
        File(context.noBackupFilesDir, STAGING_DIR)

    internal fun stagingDatabaseFile(context: Context): File =
        File(stagingDir(context), ApplicationDatabase.DATABASE_NAME)

    internal fun stagingPreferencesFile(context: Context): File =
        File(stagingDir(context), USER_PREFERENCES_STORE)

    internal fun stagingRecordingsDir(context: Context): File =
        File(stagingDir(context), RECORDINGS_DIR)

    private fun applyStaging(context: Context, entries: Set<BackupEntry>) {
        if (BackupEntry.Data in entries) {
            val stagedDb = stagingDatabaseFile(context)
            if (stagedDb.exists()) {
                context.deleteDatabase(ApplicationDatabase.DATABASE_NAME)
                replaceFile(stagedDb, context.getDatabasePath(ApplicationDatabase.DATABASE_NAME))
            }
            AudioSampleFiles.restoreFrom(context, stagingRecordingsDir(context))
        }
        if (BackupEntry.Preferences in entries) {
            val stagedPrefs = stagingPreferencesFile(context)
            if (stagedPrefs.exists()) {
                replaceFile(stagedPrefs, context.dataStoreFile(USER_PREFERENCES_STORE))
            }
        }
    }

    private fun deleteOriginals(context: Context, entries: Set<BackupEntry>) {
        if (BackupEntry.Data in entries) {
            runCatching { context.deleteDatabase(ApplicationDatabase.DATABASE_NAME) }
            runCatching { AudioSampleFiles.deleteAll(context) }
        }
        if (BackupEntry.Preferences in entries) {
            runCatching { context.dataStoreFile(USER_PREFERENCES_STORE).delete() }
        }
    }

    // Avoid using the extension from androidx.datastore because it calls
    // applicationContext, which causes a NullPointerException during attachBaseContext
    private fun Context.dataStoreFile(fileName: String): File {
        return File(this.filesDir, "datastore/$fileName")
    }

    private fun replaceFile(staging: File, original: File) {
        original.parentFile?.mkdirs()
        if (original.exists()) original.delete()
        if (!staging.renameTo(original)) {
            staging.copyTo(original, overwrite = true)
            staging.delete()
        }
    }

    private fun readEntries(context: Context): Set<BackupEntry> {
        return runCatching {
            flagFileInProgress(context).readText()
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map(BackupEntry::valueOf)
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun flagFileInProgress(context: Context) = File(context.noBackupFilesDir, FLAG_RESTORE_IN_PROGRESS)

    private fun flagFileStagingReady(context: Context) = File(context.noBackupFilesDir, FLAG_STAGING_READY)
}
