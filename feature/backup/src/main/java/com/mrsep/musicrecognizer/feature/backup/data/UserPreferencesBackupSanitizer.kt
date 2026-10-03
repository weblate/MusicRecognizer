package com.mrsep.musicrecognizer.feature.backup.data

import com.mrsep.musicrecognizer.core.datastore.UserPreferencesProto
import com.mrsep.musicrecognizer.core.datastore.copy

internal fun UserPreferencesProto.sanitizedForBackup(): UserPreferencesProto = copy {
    autoBackup = this@sanitizedForBackup.autoBackup.copy {
        enabled = false
        treeUri = ""
    }
    clearLastAutoBackupResult()
    autoBackupConsecutiveFailures = 0
}
