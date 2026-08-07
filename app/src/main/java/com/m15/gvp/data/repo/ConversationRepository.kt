package com.m15.gvp.data.repo

import com.m15.gvp.data.db.AppDatabase
import com.m15.gvp.data.db.ChatSession
import com.m15.gvp.data.db.MessageItem
import java.util.UUID

class ConversationRepository(private val db: AppDatabase) {
    suspend fun newSession(title: String): String {
        val id = UUID.randomUUID().toString()
        db.sessionDao().upsert(ChatSession(id, title, System.currentTimeMillis()))
        return id
    }

    suspend fun addUserText(sessionId: String, text: String) {
        db.messageDao().insert(
            MessageItem(UUID.randomUUID().toString(), sessionId, "user", text, System.currentTimeMillis())
        )
    }

    suspend fun addAssistantText(sessionId: String, text: String) {
        db.messageDao().insert(
            MessageItem(UUID.randomUUID().toString(), sessionId, "assistant", text, System.currentTimeMillis())
        )
    }

    /** Delete all sessions, messages, and transcript chunks (Settings → Clear history). */
    suspend fun clearAll() {
        db.messageDao().clearAll()
        db.transcriptDao().clearAll()
        db.sessionDao().clearAll()
    }
}
