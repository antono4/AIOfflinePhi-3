package com.phi3chat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Query("SELECT * FROM conversations ORDER BY updated_at DESC")
    fun observeConversations(): Flow<List<Conversation>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun conversation(id: Long): Conversation?

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeConversation(id: Long): Flow<Conversation?>

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY created_at ASC, id ASC")
    fun observeMessages(conversationId: Long): Flow<List<Message>>

    @Insert
    suspend fun insertConversation(conversation: Conversation): Long

    @Insert
    suspend fun insertMessage(message: Message): Long

    @Query("UPDATE conversations SET title = :title, updated_at = :timestamp WHERE id = :id")
    suspend fun renameConversation(id: Long, title: String, timestamp: Long)

    @Query("UPDATE conversations SET updated_at = :timestamp WHERE id = :id")
    suspend fun touchConversation(id: Long, timestamp: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long)

    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversations()

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId AND id > :afterId")
    suspend fun deleteMessagesAfter(conversationId: Long, afterId: Long)

    @Transaction
    suspend fun createConversation(title: String, now: Long): Long =
        insertConversation(Conversation(title = title, createdAt = now, updatedAt = now))
}
