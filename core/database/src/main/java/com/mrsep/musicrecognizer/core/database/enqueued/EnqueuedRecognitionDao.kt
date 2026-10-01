package com.mrsep.musicrecognizer.core.database.enqueued

import androidx.room.*
import com.mrsep.musicrecognizer.core.database.DatabaseUtils.dbChunkedMap
import com.mrsep.musicrecognizer.core.database.DatabaseUtils.eachDbChunk
import com.mrsep.musicrecognizer.core.database.enqueued.model.EnqueuedRecognitionEntity
import com.mrsep.musicrecognizer.core.database.enqueued.model.EnqueuedRecognitionEntityWithTrack
import kotlinx.coroutines.flow.Flow

@Dao
interface EnqueuedRecognitionDao {

    @Insert
    suspend fun insert(recognition: EnqueuedRecognitionEntity): Long

    @Update
    suspend fun update(recognition: EnqueuedRecognitionEntity)

    @Query("SELECT * FROM enqueued_recognition WHERE id = :recognitionId")
    suspend fun getRecognition(recognitionId: Int): EnqueuedRecognitionEntity?

    @Query("UPDATE enqueued_recognition SET title = :newTitle WHERE id = :recognitionId")
    suspend fun updateTitle(recognitionId: Int, newTitle: String)

    @Query("SELECT record_file FROM enqueued_recognition WHERE id = :recognitionId")
    suspend fun getSampleFileName(recognitionId: Int): String?

    @Transaction
    suspend fun getSampleFileNames(recognitionIds: List<Int>): List<String> {
        return recognitionIds.dbChunkedMap(::getSampleFileNamesInternal)
    }

    @Query("SELECT record_file FROM enqueued_recognition WHERE id IN (:recognitionIds)")
    suspend fun getSampleFileNamesInternal(recognitionIds: List<Int>): List<String>

    @Transaction
    suspend fun delete(recognitionIds: List<Int>) {
        recognitionIds.eachDbChunk(::deleteInternal)
    }

    @Query("DELETE FROM enqueued_recognition WHERE id IN (:recognitionIds)")
    suspend fun deleteInternal(recognitionIds: List<Int>)

    @Query("DELETE FROM enqueued_recognition")
    suspend fun deleteAll()

    @Transaction
    @Query("SELECT * FROM enqueued_recognition WHERE id = :recognitionId")
    fun getRecognitionWithTrackFlow(recognitionId: Int): Flow<EnqueuedRecognitionEntityWithTrack?>

    @Transaction
    @Query("SELECT * FROM enqueued_recognition ORDER BY creation_date DESC")
    fun getAllRecognitionsWithTrackFlow(): Flow<List<EnqueuedRecognitionEntityWithTrack>>
}
