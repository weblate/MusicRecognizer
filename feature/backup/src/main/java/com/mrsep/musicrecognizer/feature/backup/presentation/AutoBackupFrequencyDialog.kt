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
internal fun AutoBackupFrequencyDialog(
    selectedDays: Int,
    onSelectDays: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        title = {
            Text(text = stringResource(StringsR.string.backup_frequency_dialog_title))
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(StringsR.string.close))
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                AutoBackupPreferences.INTERVAL_DAYS.forEach { days ->
                    DialogRadioButton(
                        title = frequencyOptionLabel(days),
                        selected = selectedDays == days,
                        onClick = { onSelectDays(days) }
                    )
                }
            }
        },
        onDismissRequest = onDismiss,
    )
}

@Composable
internal fun frequencyOptionLabel(days: Int): String {
    return if (days == 1) {
        stringResource(StringsR.string.pref_subtitle_backup_frequency_one)
    } else {
        pluralStringResource(StringsR.plurals.pref_subtitle_backup_frequency, days, days)
    }
}
