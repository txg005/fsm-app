package com.fsm.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fsm.FsmApp
import com.fsm.sync.SyncGate
import kotlinx.coroutines.withTimeoutOrNull

class SyncWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val app=applicationContext as FsmApp
        val id=inputData.getString("account") ?: return Result.failure()
        if(app.activeId()!=id) return Result.success()
        val repo=app.repository?.takeIf { it.employeeId==id } ?: return Result.success()
        if(SyncGate.mutex.isLocked) return Result.retry()
        withTimeoutOrNull(120000) { app.engine(repo).run() }
        return if(repo.store.operations().any { it.state in listOf("PENDING","RETRY_WAIT","RUNNING") }) Result.retry() else Result.success()
    }
}
