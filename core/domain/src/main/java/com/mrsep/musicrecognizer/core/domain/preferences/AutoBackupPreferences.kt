package com.mrsep.musicrecognizer.core.domain.preferences

import java.time.Instant

data class AutoBackupPreferences(
    val enabled: Boolean,
    val treeUri: String,
    val intervalDays: Int,
    val keepCount: Int,
) {
    companion object {
        val INTERVAL_DAYS = listOf(1, 3, 7, 14, 30)
        val KEEP_COUNTS = listOf(1, 3, 5, 0)
        const val DEFAULT_INTERVAL_DAYS = 7
        const val DEFAULT_KEEP_COUNT = 3
        const val KEEP_UNLIMITED = 0
    }
}

sealed class AutoBackupResult {
    abstract val timestamp: Instant

    data class Success(
        override val timestamp: Instant,
    ) : AutoBackupResult()

    data class Failure(
        override val timestamp: Instant,
        val reason: AutoBackupError,
        val message: String? = null,
    ) : AutoBackupResult()
}

enum class AutoBackupError {
    AccessDenied,
    FileNotFound,
    Unhandled,
}
