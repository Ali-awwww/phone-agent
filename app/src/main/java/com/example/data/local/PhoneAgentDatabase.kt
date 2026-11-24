package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ActionLogEntity::class, AppOrganizationEntity::class],
    version = 1,
    exportSchema = false
)
abstract class PhoneAgentDatabase : RoomDatabase() {
    abstract fun actionLogDao(): ActionLogDao
    abstract fun appOrganizationDao(): AppOrganizationDao

    companion object {
        @Volatile
        private var INSTANCE: PhoneAgentDatabase? = null

        fun getInstance(context: Context): PhoneAgentDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PhoneAgentDatabase::class.java,
                    "phone_agent.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
