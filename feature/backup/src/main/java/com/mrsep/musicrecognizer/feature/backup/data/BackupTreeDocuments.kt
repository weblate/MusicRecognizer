package com.mrsep.musicrecognizer.feature.backup.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.mrsep.musicrecognizer.core.domain.preferences.AutoBackupPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

private const val TAG = "BackupTreeDocuments"

internal class BackupTreeDocuments @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {

    private val resolver get() = appContext.contentResolver

    fun hasWritePermission(treeUri: Uri): Boolean {
        return resolver.persistedUriPermissions.any { permission ->
            permission.uri == treeUri && permission.isWritePermission
        }
    }

    fun requiresNetwork(treeUri: Uri): Boolean {
        val authority = treeUri.authority ?: return true
        return authority !in LOCAL_AUTHORITIES
    }

    fun takePersistableWritePermission(uri: Uri): Boolean {
        return try {
            resolver.takePersistableUriPermission(uri, PERSISTABLE_URI_FLAGS)
            hasWritePermission(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist URI permission", e)
            false
        }
    }

    fun releasePersistableWritePermission(uri: Uri) {
        try {
            resolver.releasePersistableUriPermission(uri, PERSISTABLE_URI_FLAGS)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release URI permission", e)
        }
    }

    fun friendlyName(treeUri: Uri): String {
        val authority = treeUri.authority
        val docId = treeDocumentIdOrNull(treeUri)

        // volume:relative/path is only meaningful for the system document providers
        if (authority in LOCAL_AUTHORITIES && docId != null) {
            val colon = docId.indexOf(':')
            if (colon >= 0 && colon < docId.lastIndex) {
                val path = docId.substring(colon + 1)
                if (path.isNotBlank()) return path
            }
        }

        queryTreeDisplayName(treeUri, docId)
            ?.takeIf { it != docId }
            ?.let { return it }

        // Cloud providers often omit DISPLAY_NAME for tree roots, so use app label
        providerAppLabel(authority)?.let { return it }

        return treeUri.toString()
    }

    fun createBackupDocument(treeUri: Uri): Uri? {
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        return DocumentsContract.createDocument(
            resolver,
            parent,
            BackupFileNames.MIME_TYPE,
            BackupFileNames.newAutoBackupDisplayName(),
        )
    }

    fun deleteDocument(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete document $uri", e)
        }
    }

    fun enforceRetention(treeUri: Uri, keepCount: Int, justCreatedUri: Uri) {
        if (keepCount == AutoBackupPreferences.KEEP_UNLIMITED) return
        val listed = listBackupDocuments(treeUri) ?: return
        val justCreatedId = documentIdOrNull(justCreatedUri)
        val timestampsToKeep = listed
            .asSequence()
            .map { it.timestampEpochMillis }
            .distinct()
            .sortedDescending()
            .take(keepCount)
            .toSet()
        listed
            .filter { document -> document.timestampEpochMillis !in timestampsToKeep }
            .filter { document ->
                document.uri != justCreatedUri &&
                        (justCreatedId == null || document.documentId != justCreatedId)
            }
            .forEach { document ->
                try {
                    DocumentsContract.deleteDocument(resolver, document.uri)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete old backup ${document.uri}", e)
                }
            }
    }

    /**
     * Returns null when the listing is incomplete or failed, so callers skip deletion.
     */
    private fun listBackupDocuments(treeUri: Uri): List<BackupDocument>? {
        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build children URI", e)
            return null
        }
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
        return try {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val loading = cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)
                if (loading) return null
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                if (idIndex < 0 || nameIndex < 0) return null
                buildList {
                    while (cursor.moveToNext()) {
                        val mimeType = if (mimeIndex >= 0) cursor.getString(mimeIndex) else null
                        val flags = if (flagsIndex >= 0) cursor.getInt(flagsIndex) else 0
                        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) continue
                        if (flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0) continue
                        val displayName = cursor.getString(nameIndex) ?: continue
                        if (!BackupFileNames.isAutoBackupFileName(displayName)) continue
                        val documentId = cursor.getString(idIndex) ?: continue
                        val parsedTime = BackupFileNames.parseAutoBackupTimestampMillis(displayName) ?: continue
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                        add(
                            BackupDocument(
                                uri = uri,
                                documentId = documentId,
                                timestampEpochMillis = parsedTime,
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to list backup documents", e)
            null
        }
    }

    private fun queryTreeDisplayName(treeUri: Uri, docId: String?): String? {
        val treeDocUri = try {
            val id = docId ?: DocumentsContract.getTreeDocumentId(treeUri)
            DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
        } catch (_: Exception) {
            return null
        }
        return try {
            resolver.query(
                treeDocUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (nameIndex < 0) return@use null
                cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read tree display name", e)
            null
        }
    }

    private fun providerAppLabel(authority: String?): String? {
        if (authority.isNullOrBlank()) return null
        return try {
            val pm = appContext.packageManager
            val provider = pm.resolveContentProvider(authority, 0) ?: return null
            val fromApp = provider.applicationInfo?.loadLabel(pm)?.toString()
            fromApp?.takeIf { it.isNotBlank() }
                ?: provider.loadLabel(pm).toString().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve provider label for $authority", e)
            null
        }
    }

    private fun treeDocumentIdOrNull(treeUri: Uri): String? {
        return try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Exception) {
            null
        }
    }

    private fun documentIdOrNull(uri: Uri): String? {
        return try {
            DocumentsContract.getDocumentId(uri)
        } catch (_: Exception) {
            null
        }
    }

    private data class BackupDocument(
        val uri: Uri,
        val documentId: String,
        val timestampEpochMillis: Long,
    )

    companion object {
        private const val PERSISTABLE_URI_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        private val LOCAL_AUTHORITIES = setOf(
            "com.android.externalstorage.documents",
            "com.android.providers.downloads.documents",
            "com.android.providers.media.documents",
            "com.android.mtp.documents",
        )
    }
}
