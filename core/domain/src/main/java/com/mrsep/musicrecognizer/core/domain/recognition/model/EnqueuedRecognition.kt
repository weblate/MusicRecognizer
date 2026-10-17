package com.mrsep.musicrecognizer.core.domain.recognition.model

import java.io.File
import java.time.Instant

data class EnqueuedRecognition(
    val id: Int,
    val title: String,
    val sampleFile: File,
    val creationDate: Instant,
    val result: RemoteRecognitionResult?,
    val resultDate: Instant?,
    val workStatus: RecognitionWorkStatus,
)

enum class RecognitionWorkStatus {
    IDLE,
    QUEUED,
    RUNNING,
}

data class ClaimedEnqueuedRecognition(
    val id: Int,
    val attemptCount: Int,
)
