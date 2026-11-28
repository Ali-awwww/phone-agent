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
    val isMissed: Boolean = false,
    val recurrencePattern: String? = null, // e.g. "DAILY"
    val conditionalPackage: String? = null, // e.g. "com.whatsapp"
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

@Entity(tableName = "user_memory")
data class UserMemoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fact: String,
    val source: String = "CONVERSATION",
    val confidence: Float = 1.0f,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface UserMemoryDao {
    @Query("SELECT * FROM user_memory ORDER BY createdAt DESC")
    fun observeAllMemories(): Flow<List<UserMemoryEntity>>

    @Query("SELECT * FROM user_memory ORDER BY createdAt DESC")
    suspend fun getAllMemories(): List<UserMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: UserMemoryEntity): Long

    @Query("DELETE FROM user_memory WHERE id = :id")
    suspend fun deleteMemory(id: Long)

    @Query("DELETE FROM user_memory")
    suspend fun clearAllMemories()
}

@Entity(tableName = "habit_logs")
data class HabitLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val actionName: String,
    val hourOfDay: Int,
    val dayOfWeek: Int,
    val frequencyCount: Int = 1,
    val lastTimestamp: Long = System.currentTimeMillis()
)

@Dao
interface HabitLogDao {
    @Query("SELECT * FROM habit_logs ORDER BY frequencyCount DESC, lastTimestamp DESC")
    suspend fun getAllHabits(): List<HabitLogEntity>

    @Query("SELECT * FROM habit_logs ORDER BY frequencyCount DESC, lastTimestamp DESC")
    fun observeAllHabits(): Flow<List<HabitLogEntity>>

    @Query("SELECT * FROM habit_logs WHERE actionName = :actionName AND hourOfDay = :hourOfDay AND dayOfWeek = :dayOfWeek LIMIT 1")
    suspend fun findHabitSlot(actionName: String, hourOfDay: Int, dayOfWeek: Int): HabitLogEntity?

    @Query("UPDATE habit_logs SET frequencyCount = frequencyCount + :delta, lastTimestamp = :lastTimestamp WHERE id = :id")
    suspend fun incrementHabitFrequency(id: Long, delta: Int = 1, lastTimestamp: Long = System.currentTimeMillis())

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHabit(habit: HabitLogEntity): Long

    @Query("DELETE FROM habit_logs")
    suspend fun clearAllHabits()
}

@Entity(tableName = "conversation_summaries")
data class ConversationSummaryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val summaryText: String,
    val turnsCount: Int,
    val summaryType: String = "TRUNCATION",
    val fromTimestamp: Long = System.currentTimeMillis(),
    val toTimestamp: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface ConversationSummaryDao {
    @Query("SELECT * FROM conversation_summaries ORDER BY createdAt DESC, id DESC")
    fun observeAllSummaries(): Flow<List<ConversationSummaryEntity>>

    @Query("SELECT * FROM conversation_summaries ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun getRecentSummaries(limit: Int = 5): List<ConversationSummaryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSummary(summary: ConversationSummaryEntity): Long

    @Query("DELETE FROM conversation_summaries")
    suspend fun clearAllSummaries()
}

enum class QueuedCommandStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED
}

@Entity(tableName = "queued_commands")
data class QueuedCommandEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val commandText: String,
    val status: String = QueuedCommandStatus.PENDING.name,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val executedAt: Long? = null
)

@Dao
interface QueuedCommandDao {
    @Query("SELECT * FROM queued_commands WHERE status = 'PENDING' ORDER BY createdAt ASC, id ASC")
    fun observePendingCommands(): Flow<List<QueuedCommandEntity>>

    @Query("SELECT COUNT(*) FROM queued_commands WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM queued_commands WHERE status = 'PENDING' ORDER BY createdAt ASC, id ASC")
    suspend fun getPendingCommands(): List<QueuedCommandEntity>

    @Query("SELECT * FROM queued_commands ORDER BY createdAt DESC, id DESC LIMIT 30")
    suspend fun getRecentCommands(): List<QueuedCommandEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(command: QueuedCommandEntity): Long

    @Query("UPDATE queued_commands SET status = :status, executedAt = :executedAt, errorMessage = :errorMessage WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, executedAt: Long? = null, errorMessage: String? = null)

    @Query("DELETE FROM queued_commands WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM queued_commands")
    suspend fun clearAll()
}

