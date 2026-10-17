package com.mrsep.musicrecognizer.core.domain.recognition

interface EnqueuedRecognitionScheduler {

    suspend fun enqueue(recognitionIds: List<Int>)

    suspend fun cancel(recognitionIds: List<Int>)

    suspend fun cancelAll()

    suspend fun scheduleIfNeededOnAppStart()
}
