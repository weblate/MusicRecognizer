package com.mrsep.musicrecognizer.core.datastore

import androidx.datastore.core.DataMigration

internal object AutoBackupPreferencesMigration : DataMigration<UserPreferencesProto> {

    override suspend fun shouldMigrate(currentData: UserPreferencesProto) =
        !currentData.hasAutoBackup()

    override suspend fun migrate(currentData: UserPreferencesProto) = currentData.copy {
        autoBackup = autoBackupPreferencesProto {
            enabled = false
            treeUri = ""
            intervalDays = DEFAULT_INTERVAL_DAYS
            keepCount = DEFAULT_KEEP_COUNT
        }
        clearLastAutoBackupResult()
        autoBackupConsecutiveFailures = 0
    }

    override suspend fun cleanUp() = Unit

    private const val DEFAULT_INTERVAL_DAYS = 7
    private const val DEFAULT_KEEP_COUNT = 3
}
