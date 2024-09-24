package com.mrsep.musicrecognizer.feature.preferences.presentation.experimental

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mrsep.musicrecognizer.core.ui.R
import com.mrsep.musicrecognizer.feature.preferences.domain.RestoreResult
import com.mrsep.musicrecognizer.feature.preferences.presentation.common.PreferenceClickableItem
import com.mrsep.musicrecognizer.feature.preferences.presentation.common.PreferenceGroup
import java.io.FileNotFoundException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import com.mrsep.musicrecognizer.core.strings.R as StringsR

/* Work in progress */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExperimentalFeaturesScreen(
    onBackPressed: () -> Unit,
    viewModel: ExperimentalFeaturesViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val backupUiState by viewModel.backupState.collectAsStateWithLifecycle()
    val restoreUiState by viewModel.restoreUiState.collectAsStateWithLifecycle()

    val backupUriLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { resultUri ->
        if (resultUri == null) {
            Toast.makeText(context, "No file selected", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        viewModel.estimateEntriesToBackup(resultUri)
    }

    val restoreUriLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { resultUri ->
        if (resultUri == null) {
            Toast.makeText(context, "No file selected", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        viewModel.validateBackup(resultUri)
    }

    backupUiState?.let { backupState ->
        BackupDialog(
            backupState = backupState,
            onBackupClick = viewModel::backup,
            onDismissClick = {
                when (backupState) {
                    is BackupUiState.EstimatingEntries,
                    is BackupUiState.Ready,
                    -> {
                        if (!context.deleteUriFile(backupState.uri)) {
                            Toast.makeText(
                                context,
                                "Failed to delete unfinished backup file",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        viewModel.cancelBackupScopeJobs()
                    }

                    is BackupUiState.InProgress,
                    is BackupUiState.Result,
                    -> {
                        viewModel.cancelBackupScopeJobs()
                    }
                }
            }
        )
    }
    restoreUiState?.let { restoreState ->
        RestoreDialog(
            restoreState = restoreState,
            onAppRestartRequest = context::restartApplication,
            onRestoreClick = viewModel::restore,
            onDismissRequest = when (restoreState) {
                is RestoreUiState.ValidatingBackup,
                is RestoreUiState.BackupMetadata,
                -> viewModel::cancelRestoreScopeJobs

                is RestoreUiState.InProgress -> null /* main restore task is not cancelable */
                is RestoreUiState.Result -> when (restoreState.result) {
                    is RestoreResult.Success -> if (!restoreState.result.appRestartRequired) {
                        viewModel::cancelRestoreScopeJobs
                    } else {
                        null /* await app restart */
                    }

                    RestoreResult.FileNotFound,
                    RestoreResult.MalformedBackup,
                    RestoreResult.NewerVersionBackup,
                    RestoreResult.NotBackupFile,
                    RestoreResult.UnhandledError,
                    -> viewModel::cancelRestoreScopeJobs
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .background(color = MaterialTheme.colorScheme.surface)
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(StringsR.string.experimental_features),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = onBackPressed) {
                    Icon(
                        painter = painterResource(R.drawable.outline_arrow_back_24),
                        contentDescription = stringResource(StringsR.string.back)
                    )
                }
            },
        )
        PreferenceGroup(
            title = stringResource(StringsR.string.backup_and_restore_title)
        ) {
            PreferenceClickableItem(
                title = stringResource(StringsR.string.backup),
                subtitle = stringResource(StringsR.string.create_backup_subtitle),
                onItemClick = {
                    backupUriLauncher.launch(context.getBackupName())
                }
            )
            PreferenceClickableItem(
                title = stringResource(StringsR.string.restore),
                subtitle = stringResource(StringsR.string.restore_from_backup_subtitle),
                onItemClick = {
                    restoreUriLauncher.launch(arrayOf("*/*"))
                }
            )
        }
    }
}

private fun Context.deleteUriFile(uri: Uri): Boolean = try {
    DocumentsContract.deleteDocument(contentResolver, uri)
    true
} catch (e: FileNotFoundException) {
    false
}

private fun Context.getBackupName(): String = getString(StringsR.string.app_name) + "_Backup" +
        "_${DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").format(ZonedDateTime.now())}"

private fun Context.restartApplication() {
    val intent = packageManager.getLaunchIntentForPackage(packageName)
    val componentName = intent!!.component
    val mainIntent = Intent.makeRestartActivityTask(componentName)
    // Required for API 34 and later
    // Ref: https://developer.android.com/about/versions/14/behavior-changes-14#safer-intents
    mainIntent.setPackage(packageName)
    startActivity(mainIntent)
    Runtime.getRuntime().exit(0)
}