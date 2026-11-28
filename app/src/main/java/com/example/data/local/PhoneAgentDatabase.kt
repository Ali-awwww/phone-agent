package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ActionLogEntity::class,
        AppOrganizationEntity::class,
        ChatMessageEntity::class,
        AgentTaskEntity::class,
        UserMemoryEntity::class,
        HabitLogEntity::class,
        ConversationSummaryEntity::class,
        QueuedCommandEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class PhoneAgentDatabase : RoomDatabase() {
    abstract fun actionLogDao(): ActionLogDao
    abstract fun appOrganizationDao(): AppOrganizationDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun agentTaskDao(): AgentTaskDao
    abstract fun userMemoryDao(): UserMemoryDao
    abstract fun habitLogDao(): HabitLogDao
    abstract fun conversationSummaryDao(): ConversationSummaryDao
    abstract fun queuedCommandDao(): QueuedCommandDao

    companion object {
        @Volatile
        private var INSTANCE: PhoneAgentDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chat_messages` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sender` TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        `messageType` TEXT NOT NULL,
                        `payloadJson` TEXT,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_tasks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `taskType` TEXT NOT NULL,
                        `dueAtMillis` INTEGER,
                        `hour` INTEGER,
                        `minute` INTEGER,
                        `alarmRequestCode` INTEGER,
                        `isCompleted` INTEGER NOT NULL,
                        `isCancelled` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `user_memory` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `fact` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `confidence` REAL NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `habit_logs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `actionName` TEXT NOT NULL,
                        `hourOfDay` INTEGER NOT NULL,
                        `dayOfWeek` INTEGER NOT NULL,
                        `frequencyCount` INTEGER NOT NULL,
                        `lastTimestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversation_summaries` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `summaryText` TEXT NOT NULL,
                        `turnsCount` INTEGER NOT NULL,
                        `summaryType` TEXT NOT NULL,
                        `fromTimestamp` INTEGER NOT NULL,
                        `toTimestamp` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `queued_commands` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `commandText` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `errorMessage` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `executedAt` INTEGER
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): PhoneAgentDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PhoneAgentDatabase::class.java,
                    "phone_agent.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
