package com.m15.gvp.data.db.dao

import androidx.room.*
import com.m15.gvp.data.db.MessageItem
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(m: MessageItem)

    @Query("SELECT * FROM MessageItem WHERE sessionId = :sid ORDER BY createdAt ASC")
    fun stream(sid: String): Flow<List<MessageItem>>

    @Query("DELETE FROM MessageItem")
    suspend fun clearAll()
}
