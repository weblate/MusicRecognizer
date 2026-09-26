package com.mrsep.musicrecognizer.core.domain.usecase

import com.mrsep.musicrecognizer.core.domain.recognition.EnqueuedRecognitionRepository
import com.mrsep.musicrecognizer.core.domain.recognition.EnqueuedRecognitionScheduler
import javax.inject.Inject

class DeleteEnqueuedRecognition @Inject constructor(
    private val enqueuedRecognitionRepository: EnqueuedRecognitionRepository,
    private val enqueuedRecognitionScheduler: EnqueuedRecognitionScheduler,
) {

    suspend operator fun invoke(recognitionIds: List<Int>) {
        enqueuedRecognitionScheduler.cancel(recognitionIds)
        enqueuedRecognitionRepository.delete(recognitionIds)
    }
}
