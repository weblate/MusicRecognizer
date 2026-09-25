package com.mrsep.musicrecognizer.core.data.enqueued

import com.mrsep.musicrecognizer.core.domain.recognition.AudioSample
import java.io.File
import java.time.Instant

interface AudioSampleDataSource {

    fun getFiles(): Array<File>

    fun getTotalSize(): Long

    suspend fun copy(sample: AudioSample): AudioSample?

    suspend fun read(file: File, timestamp: Instant): AudioSample?

    suspend fun delete(file: File): Boolean

    suspend fun deleteAll(): Boolean
}
