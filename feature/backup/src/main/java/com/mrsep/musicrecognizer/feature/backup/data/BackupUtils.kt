package com.mrsep.musicrecognizer.feature.backup.data

import android.content.ContentResolver
import android.net.Uri
import java.io.OutputStream

// Prefer "wt", it truncates and replaces the existing document
// Some providers (e.g. Dropbox) may create a duplicate file when using "w"
// Fall back to default "w" if the content provider does not support "wt"
@Throws(Exception::class)
internal fun ContentResolver.openOutputStreamPreferTruncate(uri: Uri): OutputStream {
    return try {
        requireNotNull(openOutputStream(uri, "wt"))
    } catch (_: Exception) {
        requireNotNull(openOutputStream(uri, "w"))
    }
}
