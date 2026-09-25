package com.phi3chat.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

enum class Role { SYSTEM, USER, ASSISTANT }

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("conversation_id")],
)
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
    val role: Role,
    val content: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** Tokens/second recorded for this reply, or null when not measured. */
    @ColumnInfo(name = "tokens_per_second") val tokensPerSecond: Double? = null,
    @ColumnInfo(name = "generated_tokens") val generatedTokens: Int? = null,
)

/** A message that is still being streamed: not yet persisted, held in memory. */
data class StreamingMessage(
    val role: Role,
    val content: String,
    val isGenerating: Boolean,
)
