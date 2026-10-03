package com.mrsep.musicrecognizer.feature.backup.data

import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object BackupFileNames {

    const val PREFIX = "Audile_Backup_"
    const val AUTO_PREFIX = "Audile_AutoBackup_"
    const val MIME_TYPE = "application/octet-stream"
    const val EXTENSION = "audilebak"

    const val CSV_PREFIX = "Audile_Songs_"
    const val CSV_EXTENSION = "csv"

    // Offset as +0200 (no colon) so the name stays filesystem-safe
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss_xx", Locale.ROOT)
    private val autoBackupNameRegex = Regex(
        """^${Regex.escape(AUTO_PREFIX)}(\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}_[+-]\d{4})(?: \(\d+\))?\.${Regex.escape(EXTENSION)}(?: \(\d+\))?$"""
    )

    fun newBackupDisplayName(now: ZonedDateTime = ZonedDateTime.now()): String {
        return PREFIX + timestampFormatter.format(now) + ".$EXTENSION"
    }

    fun newAutoBackupDisplayName(now: ZonedDateTime = ZonedDateTime.now()): String {
        return AUTO_PREFIX + timestampFormatter.format(now) + ".$EXTENSION"
    }

    fun newCsvDisplayName(now: ZonedDateTime = ZonedDateTime.now()): String {
        return CSV_PREFIX + timestampFormatter.format(now) + ".$CSV_EXTENSION"
    }

    fun isAutoBackupFileName(displayName: String): Boolean = autoBackupNameRegex.matches(displayName)

    fun parseAutoBackupTimestampMillis(displayName: String): Long? {
        val match = autoBackupNameRegex.matchEntire(displayName) ?: return null
        return try {
            OffsetDateTime.parse(match.groupValues[1], timestampFormatter)
                .toInstant()
                .toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
}
