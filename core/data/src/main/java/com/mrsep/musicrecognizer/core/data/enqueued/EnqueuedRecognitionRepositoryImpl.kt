package com.mrsep.musicrecognizer.core.data.enqueued

import com.mrsep.musicrecognizer.core.common.di.ApplicationScope
import com.mrsep.musicrecognizer.core.common.di.IoDispatcher
import com.mrsep.musicrecognizer.core.data.PersistentStoreLock
import com.mrsep.musicrecognizer.core.database.ApplicationDatabase
import com.mrsep.musicrecognizer.core.database.enqueued.model.EnqueuedRecognitionEntity
import com.mrsep.musicrecognizer.core.domain.recognition.AudioSample
import com.mrsep.musicrecognizer.core.domain.recognition.EnqueuedRecognitionRepository
import com.mrsep.musicrecognizer.core.domain.recognition.model.EnqueuedRecognition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

internal class EnqueuedRecognitionRepositoryImpl @Inject constructor(
    private val audioSampleDataSource: AudioSampleDataSource,
    private val storeLock: PersistentStoreLock,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    database: ApplicationDatabase
) : EnqueuedRecognitionRepository {

    private val dao = database.enqueuedRecognitionDao()
    private val persistentCoroutineContext = appScope.coroutineContext + ioDispatcher

    override suspend fun createRecognition(sample: AudioSample, title: String): Int? {
        return withStoreWrite {
            audioSampleDataSource.copy(sample)?.let { sample ->
                val enqueued = EnqueuedRecognitionEntity(
                    id = 0,
                    title = title,
                    sampleFileName = sample.file.name,
                    creationDate = sample.timestamp
                )
                dao.insert(enqueued).toInt()
            }
        }
    }

    override suspend fun update(recognition: EnqueuedRecognition) {
        withStoreWrite {
            dao.update(recognition.toEntity())
        }
    }

    override suspend fun updateTitle(recognitionId: Int, newTitle: String) {
        withStoreWrite {
            dao.updateTitle(recognitionId, newTitle)
        }
    }

    override suspend fun getAudioSampleFile(recognitionId: Int): File? {
        return withContext(ioDispatcher) {
            dao.getSampleFileName(recognitionId)?.let(audioSampleDataSource::resolve)
        }
    }

    override suspend fun getAudioSample(recognitionId: Int): AudioSample? {
        return withContext(ioDispatcher) {
            dao.getRecognition(recognitionId)?.let { recognition ->
                audioSampleDataSource.read(
                    audioSampleDataSource.resolve(recognition.sampleFileName),
                    recognition.creationDate
                )
            }
        }
    }

    override suspend fun delete(recognitionIds: List<Int>) {
        withStoreWrite {
            val files = dao.getSampleFileNames(recognitionIds)
                .map(audioSampleDataSource::resolve)
            dao.delete(recognitionIds)
            files.forEach { file -> audioSampleDataSource.delete(file) }
        }
    }

    override suspend fun deleteAll() {
        withStoreWrite {
            dao.deleteAll()
            audioSampleDataSource.deleteAll()
        }
    }

    override fun getRecognitionFlow(recognitionId: Int): Flow<EnqueuedRecognition?> {
        return dao.getRecognitionWithTrackFlow(recognitionId)
            .map { entity ->
                entity?.toDomain(audioSampleDataSource.resolve(entity.enqueued.sampleFileName))
            }
            .flowOn(ioDispatcher)
    }

    override fun getAllRecognitionsFlow(): Flow<List<EnqueuedRecognition>> {
        return dao.getAllRecognitionsWithTrackFlow()
            .map { list ->
                list.map { entity ->
                    entity.toDomain(audioSampleDataSource.resolve(entity.enqueued.sampleFileName))
                }
            }
            .flowOn(ioDispatcher)
    }

    private suspend fun <T> withStoreWrite(block: suspend () -> T): T =
        withContext(persistentCoroutineContext) {
            storeLock.withShared(block)
        }
}
