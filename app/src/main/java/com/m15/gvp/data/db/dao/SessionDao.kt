package com.m15.gvp.data.db.dao

import androidx.room.*
import com.m15.gvp.data.db.ChatSession
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: ChatSession)

    @Query("SELECT * FROM ChatSession ORDER BY createdAt DESC LIMIT 20")
    fun recent(): Flow<List<ChatSession>>

    @Query("DELETE FROM ChatSession")
    suspend fun clearAll()
}
