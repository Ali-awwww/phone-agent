package com.example.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

enum class ChatSender {
    USER,
    AGENT
}

enum class ChatMessageType {
    TEXT,
    APPS_LIST,
    TASKS_LIST,
    SCREEN_READ,
    PLAN,
    GAME_STATUS,
    FEATURE_HIGHLIGHT,
    STOPPED,
    ERROR
}

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sender: String,
    val text: String,
    val messageType: String = ChatMessageType.TEXT.name,
    val payloadJson: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages ORDER BY createdAt ASC, id ASC")
    fun observeAllMessages(): Flow<List<ChatMessageEntity>>

    @Query(
        "SELECT * FROM (SELECT * FROM chat_messages ORDER BY createdAt DESC, id DESC LIMIT :limit) " +
            "ORDER BY createdAt ASC, id ASC"
    )
    fun observeRecentMessages(limit: Int): Flow<List<ChatMessageEntity>>

    @Query("SELECT COUNT(*) FROM chat_messages")
    fun observeMessagesCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Query("DELETE FROM chat_messages")
    suspend fun clearAllMessages()
}

enum class AgentTaskType(val titleAr: String) {
    REMINDER("تذكير"),
    TASK("مهمة"),
    ALARM("منبه")
}

@Entity(tableName = "agent_tasks")
data class AgentTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val taskType: String = AgentTaskType.REMINDER.name,
    val dueAtMillis: Long? = null,
    val hour: Int? = null,
    val minute: Int? = null,
    val alarmRequestCode: Int? = null,
    val isCompleted: Boolean = false,
    val isCancelled: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface AgentTaskDao {
    @Query("SELECT * FROM agent_tasks WHERE isCancelled = 0 ORDER BY isCompleted ASC, COALESCE(dueAtMillis, createdAt) ASC")
    fun observeActiveTasks(): Flow<List<AgentTaskEntity>>

    @Query("SELECT * FROM agent_tasks WHERE isCancelled = 0 ORDER BY isCompleted ASC, COALESCE(dueAtMillis, createdAt) ASC")
    suspend fun getActiveTasks(): List<AgentTaskEntity>

    @Query("SELECT * FROM agent_tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: Long): AgentTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: AgentTaskEntity): Long

    @Query("UPDATE agent_tasks SET isCompleted = :completed WHERE id = :id")
    suspend fun markCompleted(id: Long, completed: Boolean = true)

    @Query("UPDATE agent_tasks SET isCancelled = 1 WHERE id = :id")
    suspend fun markCancelled(id: Long)
}

class ChatHistoryRepository(
    private val chatMessageDao: ChatMessageDao,
    private val agentTaskDao: AgentTaskDao
) {
    val messagesFlow: Flow<List<ChatMessageEntity>> = chatMessageDao.observeAllMessages()
    val messagesCountFlow: Flow<Int> = chatMessageDao.observeMessagesCount()
    val activeTasksFlow: Flow<List<AgentTaskEntity>> = agentTaskDao.observeActiveTasks()

    fun observeRecentMessages(limit: Int = DEFAULT_CHAT_PAGE_SIZE): Flow<List<ChatMessageEntity>> =
        chatMessageDao.observeRecentMessages(limit.coerceAtLeast(1))

    companion object {
        const val DEFAULT_CHAT_PAGE_SIZE = 60
    }

    suspend fun addUserMessage(text: String): Long {
        return chatMessageDao.insertMessage(
            ChatMessageEntity(
                sender = ChatSender.USER.name,
                text = text,
                messageType = ChatMessageType.TEXT.name
            )
        )
    }

    suspend fun addAgentMessage(
        text: String,
        type: ChatMessageType = ChatMessageType.TEXT,
        payloadJson: String? = null
    ): Long {
        return chatMessageDao.insertMessage(
            ChatMessageEntity(
                sender = ChatSender.AGENT.name,
                text = text,
                messageType = type.name,
                payloadJson = payloadJson
            )
        )
    }

    suspend fun clearChatHistory() {
        chatMessageDao.clearAllMessages()
    }

    suspend fun createTask(task: AgentTaskEntity): Long {
        return agentTaskDao.insertTask(task)
    }

    suspend fun getActiveTasks(): List<AgentTaskEntity> {
        return agentTaskDao.getActiveTasks()
    }

    suspend fun getTaskById(id: Long): AgentTaskEntity? {
        return agentTaskDao.getTaskById(id)
    }

    suspend fun setTaskCompleted(id: Long, completed: Boolean) {
        agentTaskDao.markCompleted(id, completed)
    }

    suspend fun cancelTask(id: Long) {
        agentTaskDao.markCancelled(id)
    }
}
