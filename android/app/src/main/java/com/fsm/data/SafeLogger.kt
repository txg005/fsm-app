package com.fsm.data

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException

enum class Event(val label:String) {
    SYNC_STARTED("Синхронизация запущена"), SYNC_FINISHED("Синхронизация завершена"), OP_ACK("Операция подтверждена"), OP_RETRY("Запланирован повтор"), OP_BLOCKED("Операция остановлена"), SNAPSHOT_OK("Метаданные сохранены"), SYNC_CANCELLED("Синхронизация прервана"), LOCAL_SAVED("Сохранено на устройстве"), QUEUED("Закрытие в очереди"), STORAGE_ERROR("Ошибка хранилища")
}
class SafeLogger(private val db:FsmDatabase) {
    suspend fun record(event:Event, operationId:String?=null, requestId:String?=null, at:Long=System.currentTimeMillis()) {
        val valid=Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        val op=operationId?.takeIf { valid.matches(it) }; val req=requestId?.takeIf { valid.matches(it) }
        Log.i("FSM",event.name)
        try { db.withTransaction { db.store().log(AppLog(uuid(),at,"INFO",event.name,op,req,event.label)); db.store().prune(at-7*86400000L) } }
        catch(e:CancellationException) { throw e }
        catch(_:Exception) { Log.w("FSM","LOG_STORAGE_UNAVAILABLE") }
    }
}
