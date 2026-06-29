package com.yiaha.running.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RunPointDao {
    @Insert
    suspend fun insert(point: RunPointEntity)

    @Insert
    fun insertBlocking(point: RunPointEntity)

    @Query("SELECT * FROM run_points WHERE sessionId = :sessionId ORDER BY wallClockMillis ASC")
    suspend fun getPoints(sessionId: String): List<RunPointEntity>

    @Query("SELECT * FROM run_points WHERE sessionId = :sessionId ORDER BY wallClockMillis ASC")
    fun observePoints(sessionId: String): Flow<List<RunPointEntity>>

    @Query("SELECT * FROM run_points WHERE sessionId = :sessionId ORDER BY wallClockMillis ASC")
    fun getPointsBlocking(sessionId: String): List<RunPointEntity>

    @Query("SELECT COUNT(*) FROM run_points WHERE sessionId = :sessionId")
    suspend fun count(sessionId: String): Int
}
