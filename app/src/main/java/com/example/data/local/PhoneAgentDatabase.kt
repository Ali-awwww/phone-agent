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
        HabitLogEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class PhoneAgentDatabase : RoomDatabase() {
    abstract fun actionLogDao(): ActionLogDao
    abstract fun appOrganizationDao(): AppOrganizationDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun agentTaskDao(): AgentTaskDao
    abstract fun userMemoryDao(): UserMemoryDao
    abstract fun habitLogDao(): HabitLogDao

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

        fun getInstance(context: Context): PhoneAgentDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PhoneAgentDatabase::class.java,
                    "phone_agent.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
