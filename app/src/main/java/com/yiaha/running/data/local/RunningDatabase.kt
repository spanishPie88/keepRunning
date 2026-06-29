package com.yiaha.running.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [RunPointEntity::class, RunSessionEntity::class],
    version = 3,
    exportSchema = false
)
abstract class RunningDatabase : RoomDatabase() {
    abstract fun runPointDao(): RunPointDao
    abstract fun runSessionDao(): RunSessionDao

    companion object {
        @Volatile
        private var instance: RunningDatabase? = null

        fun getInstance(context: Context): RunningDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RunningDatabase::class.java,
                    "running.db"
                )
                    .build()
                    .also { instance = it }
            }
        }
    }
}
