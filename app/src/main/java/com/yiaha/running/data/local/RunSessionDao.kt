package com.yiaha.running.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RunSessionDao {
    @Upsert
    fun upsertBlocking(session: RunSessionEntity)

    @Query(
        """
        SELECT * FROM run_sessions
        WHERE state = 'Finished'
        ORDER BY endedAtMillis DESC
        LIMIT :limit
        """
    )
    fun observeFinishedSessions(limit: Int = 20): Flow<List<RunSessionEntity>>

    @Query(
        """
        SELECT * FROM run_sessions
        WHERE state IN ('Running', 'Paused', 'Recovering')
        ORDER BY startedAtMillis DESC
        LIMIT 1
        """
    )
    fun observeActiveSession(): Flow<RunSessionEntity?>

    @Query(
        """
        SELECT * FROM run_sessions
        WHERE state IN ('Running', 'Paused', 'Recovering')
        ORDER BY startedAtMillis DESC
        LIMIT 1
        """
    )
    fun getActiveSessionBlocking(): RunSessionEntity?

    @Query("SELECT * FROM run_sessions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): RunSessionEntity?

    @Query("SELECT * FROM run_sessions WHERE id = :id LIMIT 1")
    fun getByIdBlocking(id: String): RunSessionEntity?

    @Query(
        """
        UPDATE run_sessions
        SET state = 'Finished',
            endedAtMillis = :endedAtMillis
        WHERE state IN ('Running', 'Paused', 'Recovering')
        """
    )
    fun finishAllActiveBlocking(endedAtMillis: Long)

    @Query(
        """
        UPDATE run_sessions
        SET state = 'Finished',
            endedAtMillis = :endedAtMillis
        WHERE state IN ('Running', 'Paused', 'Recovering')
          AND id != :currentSessionId
        """
    )
    fun finishOtherActiveBlocking(currentSessionId: String, endedAtMillis: Long)
}
