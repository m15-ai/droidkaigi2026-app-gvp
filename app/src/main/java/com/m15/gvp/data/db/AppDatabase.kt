package com.m15.gvp.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.m15.gvp.data.db.dao.MessageDao
import com.m15.gvp.data.db.dao.SessionDao
import com.m15.gvp.data.db.dao.TranscriptDao

@Database(
    entities = [ChatSession::class, MessageItem::class, TranscriptChunk::class],
    version = 1
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun transcriptDao(): TranscriptDao

    companion object {
        @Volatile private var inst: AppDatabase? = null
        fun get(ctx: Context): AppDatabase =
            inst ?: synchronized(this) {
                inst ?: Room.databaseBuilder(ctx, AppDatabase::class.java, "gvp.db")
                    .fallbackToDestructiveMigration()
                    .build().also { inst = it }
            }
    }
}
