package com.mrsep.musicrecognizer.feature.recognition.domain

import com.mrsep.musicrecognizer.core.common.di.ApplicationScope
import com.mrsep.musicrecognizer.core.domain.recognition.EnqueuedRecognitionRepository
import com.mrsep.musicrecognizer.core.domain.recognition.EnqueuedRecognitionScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import javax.inject.Inject

internal class DeleteEnqueuedRecognition @Inject constructor(
    private val enqueuedRecognitionScheduler: EnqueuedRecognitionScheduler,
    private val enqueuedRecognitionRepository: EnqueuedRecognitionRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    private val persistentCoroutineContext = appScope.coroutineContext

    suspend operator fun invoke(recognitionIds: List<Int>) {
        if (recognitionIds.isEmpty()) return
        withContext(persistentCoroutineContext) {
            enqueuedRecognitionScheduler.cancel(recognitionIds)
            enqueuedRecognitionRepository.delete(recognitionIds)
        }
    }
}
