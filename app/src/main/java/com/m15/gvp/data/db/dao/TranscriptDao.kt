package com.m15.gvp.data.db.dao

import androidx.room.*
import com.m15.gvp.data.db.TranscriptChunk

@Dao
interface TranscriptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(c: TranscriptChunk)

    @Query("DELETE FROM TranscriptChunk WHERE sessionId = :sid AND isFinal = 0")
    suspend fun clearNonFinal(sid: String)

    @Query("DELETE FROM TranscriptChunk")
    suspend fun clearAll()
}
