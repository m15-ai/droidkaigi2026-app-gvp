package com.m15.gvp.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class ChatSession(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long
)

@Entity
data class MessageItem(
    @PrimaryKey val messageId: String,
    val sessionId: String,
    val role: String,
    val text: String,
    val createdAt: Long
)

@Entity
data class TranscriptChunk(
    @PrimaryKey val id: String,
    val sessionId: String,
    val fromMs: Long,
    val toMs: Long,
    val text: String,
    val isFinal: Boolean
)
