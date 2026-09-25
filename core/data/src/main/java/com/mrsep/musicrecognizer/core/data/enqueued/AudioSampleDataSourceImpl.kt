package com.mrsep.musicrecognizer.core.data.enqueued

import android.content.Context
import android.media.MediaFormat
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.MediaExtractorCompat
import com.mrsep.musicrecognizer.core.common.di.IoDispatcher
import com.mrsep.musicrecognizer.core.domain.recognition.AudioSample
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

private const val SAMPLES_DIR = "audio_samples"
private const val SAMPLES_DIR_LEGACY = "enqueued_records"

internal class AudioSampleDataSourceImpl @Inject constructor(
    @ApplicationContext private val appContext: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AudioSampleDataSource {

    private val samplesDir by lazy {
        File(appContext.filesDir, SAMPLES_DIR).apply { if (!exists()) mkdirs() }
    }

    override fun getFiles(): Array<File> {
        return requireNotNull(samplesDir.listFiles())
    }

    override fun getTotalSize(): Long {
        return getFiles().fold(0L) { acc, file -> acc + file.length() }
    }

    override suspend fun copy(sample: AudioSample): AudioSample? {
        val sampleName = getNewSampleName(sample.mimeType)
        val persistentSample = sample.copy(file = samplesDir.resolve(sampleName))
        return try {
            withContext(ioDispatcher) {
                Files.copy(
                    sample.file.toPath(),
                    persistentSample.file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
                persistentSample
            }
        } catch (e: IOException) {
            Log.e(this::class.simpleName, "Failed to write sample file ($sampleName)", e)
            persistentSample.file.delete()
            null
        }
    }

    @OptIn(UnstableApi::class)
    override suspend fun read(file: File, timestamp: Instant): AudioSample? = withContext(ioDispatcher) {
        val extractor = MediaExtractorCompat(appContext)
        try {
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mimeType = format.getString(MediaFormat.KEY_MIME)
                if (MimeTypes.isAudio(mimeType)) {
                    trackIndex = i
                    break
                }
            }
            require(trackIndex >= 0) { "No audio track found" }
            extractor.selectTrack(trackIndex)
            val mediaFormat = extractor.getTrackFormat(trackIndex)
            AudioSample(
                file = file,
                timestamp = timestamp,
                duration = mediaFormat.getLong(MediaFormat.KEY_DURATION).let { durationUs ->
                    ((durationUs + 500) / 1000).milliseconds
                },
                sampleRate = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                mimeType = file.parseSampleMimeType(),
            )
        } catch (e: Exception) {
            ensureActive()
            Log.e(this::class.simpleName, "Failed to read sample file", e)
            null
        } finally {
            extractor.release()
        }
    }

    override suspend fun delete(file: File): Boolean = withContext(ioDispatcher) {
        var numTries = 0
        val maxTries = 3
        while (numTries < maxTries) {
            try {
                if (file.exists() && file.delete()) return@withContext true
            } catch (e: Exception) {
                Log.e(this::class.simpleName, "Failed to delete sample file ${file.path}", e)
            }
            numTries++
            delay(500.milliseconds)
        }
        false
    }

    override suspend fun deleteAll(): Boolean = withContext(ioDispatcher) {
        var allDeleted = true
        samplesDir.listFiles()?.forEach { file ->
            if (!delete(file)) allDeleted = false
        }
        allDeleted
    }

    private fun getNewSampleName(mimeType: String): String {
        return "${UUID.randomUUID()}.${mimeType.mimeTypeToFileExtension()}"
    }

    private fun String.mimeTypeToFileExtension(): String = when (lowercase()) {
        "audio/mp4" -> "m4a"
        "audio/x-wav" -> "wav"
        else -> error("Unexpected mime type")
    }

    private fun File.parseSampleMimeType(): String = when (extension.lowercase()) {
        "m4a" -> "audio/mp4"
        "wav" -> "audio/x-wav"
        "" -> "audio/aac" // Legacy sample format
        else -> error("Unexpected file extension")
    }
}

/**
 * Restore staging audio samples from [android.app.Application.attachBaseContext]
 * before the datasource singleton exists.
 */
object AudioSampleFiles {

    fun deleteAll(context: Context) {
        File(context.filesDir, SAMPLES_DIR).deleteRecursively()
        File(context.filesDir, SAMPLES_DIR_LEGACY).deleteRecursively()
    }

    fun restoreFrom(context: Context, recordingsDir: File) {
        if (!recordingsDir.exists()) return
        recordingsDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val destDir = destDirFor(context, file.name).apply { mkdirs() }
            val dest = File(destDir, file.name)
            if (dest.exists()) dest.delete()
            if (!file.renameTo(dest)) {
                file.copyTo(dest, overwrite = true)
                file.delete()
            }
        }
    }

    private fun destDirFor(context: Context, filename: String): File {
        val dirName = when (filename.substringAfterLast('.', "")) {
            "m4a" -> SAMPLES_DIR
            else -> SAMPLES_DIR_LEGACY
        }
        return File(context.filesDir, dirName)
    }
}
