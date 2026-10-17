package com.mrsep.musicrecognizer.core.domain.recognition

import com.mrsep.musicrecognizer.core.domain.recognition.model.ClaimedEnqueuedRecognition
import com.mrsep.musicrecognizer.core.domain.recognition.model.EnqueuedRecognition
import com.mrsep.musicrecognizer.core.domain.recognition.model.RemoteRecognitionResult
import com.mrsep.musicrecognizer.core.domain.track.model.Track
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.time.Instant

interface EnqueuedRecognitionRepository {

    suspend fun updateTitle(recognitionId: Int, newTitle: String)

    suspend fun delete(recognitionIds: List<Int>)

    suspend fun deleteAll()

    suspend fun createRecognition(sample: AudioSample, title: String): Int?

    suspend fun getAudioSampleFile(recognitionId: Int): File?

    suspend fun getAudioSample(recognitionId: Int): AudioSample?

    fun getRecognitionFlow(recognitionId: Int): Flow<EnqueuedRecognition?>

    fun getAllRecognitionsFlow(): Flow<List<EnqueuedRecognition>>


    suspend fun markQueued(recognitionIds: List<Int>, queuedAt: Instant)

    suspend fun queuedNextAttempt(): Instant?

    fun queuedNextAttemptFlow(): Flow<Instant?>

    suspend fun claimNext(now: Instant): ClaimedEnqueuedRecognition?

    suspend fun finishRun(
        recognitionId: Int,
        result: RemoteRecognitionResult?,
        resultDate: Instant?,
    ): Boolean

    suspend fun finishSuccessfulRun(
        recognitionId: Int,
        track: Track,
        resultDate: Instant,
    ): Track?

    suspend fun holdForRetry(
        recognitionId: Int,
        nextAttemptAt: Instant,
        result: RemoteRecognitionResult,
        resultDate: Instant,
    ): Boolean

    suspend fun returnToQueued(recognitionId: Int): Boolean

    suspend fun cancelWork(recognitionIds: List<Int>)

    suspend fun cancelAllWork()

    suspend fun resetStaleRunningBeforeClaim()

    suspend fun resetStaleRunningOnAppStart()
}
