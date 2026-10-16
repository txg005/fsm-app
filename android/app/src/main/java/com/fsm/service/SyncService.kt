package com.fsm.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.fsm.FsmApp
import com.fsm.MainActivity
import com.fsm.data.Event
import kotlinx.coroutines.*

class SyncService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var job:Job?=null
    private val app get()=application as FsmApp
    private fun notification(sent:Int):Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,1,Intent(this,SyncService::class.java).setAction("CANCEL"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this,"sync").setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("FSM: синхронизация").setContentText("Подтверждено операций: $sent").setOngoing(true).setContentIntent(open).addAction(0,"Остановить",stop).build()
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("sync","Передача работ",NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="CANCEL") { app.activeId()?.let(app::cancelBackground); job?.cancel(); finish(); return START_NOT_STICKY }
        try {
            ServiceCompat.startForeground(this,41,notification(0),if(Build.VERSION.SDK_INT>=29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch(_:RuntimeException) {
            // Background/budget/permission rejection must not crash the process or clear pending.
            android.util.Log.w("FSM","FGS_START_BLOCKED")
            finish(); return START_NOT_STICKY
        }
        if(job?.isActive==true) return START_NOT_STICKY
        val repo=app.repository ?: run { finish(); return START_NOT_STICKY }
        job=scope.launch {
            var permitRetry=true
            try {
                withTimeout(600000) { app.engine(repo).run { sent -> getSystemService(NotificationManager::class.java).notify(41,notification(sent)) } }
            } catch(_:CancellationException) {
                permitRetry=false
                withContext(NonCancellable) { repo.logger.record(Event.SYNC_CANCELLED) }
            } catch(_:Exception) { repo.logger.record(Event.OP_BLOCKED) }
            finally { if(permitRetry && app.activeId()==repo.employeeId) app.schedule(repo.employeeId); finish() }
        }
        return START_NOT_STICKY
    }
    private fun finish() { ServiceCompat.stopForeground(this,ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onTimeout(startId:Int,fgsType:Int) { job?.cancel(); finish() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent:Intent?):IBinder?=null
}
