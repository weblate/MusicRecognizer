package com.mrsep.musicrecognizer.feature.backup.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.mrsep.musicrecognizer.core.domain.preferences.AutoBackupPreferences
import com.mrsep.musicrecognizer.core.ui.components.DialogRadioButton
import com.mrsep.musicrecognizer.core.strings.R as StringsR

@Composable
internal fun AutoBackupRetentionDialog(
    selectedKeepCount: Int,
    onSelectKeepCount: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        title = {
            Text(text = stringResource(StringsR.string.backup_retention_dialog_title))
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(StringsR.string.close))
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                AutoBackupPreferences.KEEP_COUNTS.forEach { keepCount ->
                    DialogRadioButton(
                        title = retentionOptionLabel(keepCount),
                        selected = selectedKeepCount == keepCount,
                        onClick = {
                            onSelectKeepCount(keepCount)
                        }
                    )
                }
            }
        },
        onDismissRequest = onDismiss,
    )
}

@Composable
internal fun retentionOptionLabel(keepCount: Int): String {
    return when (keepCount) {
        AutoBackupPreferences.KEEP_UNLIMITED ->
            stringResource(StringsR.string.pref_subtitle_backup_retention_unlimited)
        else -> pluralStringResource(StringsR.plurals.pref_subtitle_backup_retention, keepCount, keepCount)
    }
}
