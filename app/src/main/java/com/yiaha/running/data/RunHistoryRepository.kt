package com.yiaha.running.data

import android.content.Context
import com.yiaha.running.data.local.RunPointEntity
import com.yiaha.running.data.local.RunSessionEntity
import com.yiaha.running.data.local.RunningDatabase
import kotlinx.coroutines.flow.Flow

class RunHistoryRepository(context: Context) {
    private val database = RunningDatabase.getInstance(context)

    fun observeRecentRuns(): Flow<List<RunSessionEntity>> {
        return database.runSessionDao().observeFinishedSessions()
    }

    fun observeActiveRun(): Flow<RunSessionEntity?> {
        return database.runSessionDao().observeActiveSession()
    }

    fun observeRunPoints(sessionId: String): Flow<List<RunPointEntity>> {
        return database.runPointDao().observePoints(sessionId)
    }
}
