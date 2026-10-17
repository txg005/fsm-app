package com.fsm

import android.app.Application
import androidx.room.Room
import androidx.work.*
import com.fsm.data.*
import com.fsm.sync.SyncEngine
import com.fsm.service.SyncWorker
import java.io.File
import java.util.concurrent.TimeUnit

class FsmApp:Application() {
    private val client by lazy { Api.client() }
    private val prefs by lazy { getSharedPreferences("account",MODE_PRIVATE) }
    @Volatile var repository:Repository?=null; private set
    fun activeId():String?=prefs.getString("active",null)
    override fun onCreate() { super.onCreate(); activeId()?.let { open(it) } }
    @Synchronized fun open(id:String):Repository {
        require(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(id))
        repository?.takeIf { it.employeeId==id }?.let { return it }
        val db=Room.databaseBuilder(this,FsmDatabase::class.java,"fsm_$id.db").build()
        val files=File(filesDir,"accounts/$id/photos").apply { mkdirs() }
        val api=Api(client,BuildConfig.API_URL,TokenVault(this,id),id)
        return Repository(db,api,id,files).also { repository=it; check(prefs.edit().putString("active",id).commit()) }
    }
    suspend fun login(login:String,password:String):Repository {
        val token=Api(client,BuildConfig.API_URL,null,null).login(login,password)
        val repo=open(token.employee.id); repo.api.accept(token)
        repo.store.employee(Employee(token.employee.id,wire.encodeToString(EmployeeDto.serializer(),token.employee)))
        return repo
    }
    fun clearActive() { activeId()?.let { TokenVault(this,it).clear() }; check(prefs.edit().remove("active").commit()); repository=null }
    fun engine(repo:Repository)=SyncEngine(repo) { activeId()==repo.employeeId && repository===repo }
    fun cancelBackground(id:String) { WorkManager.getInstance(this).cancelUniqueWork("sync_$id") }
    fun schedule(id:String) {
        val request=OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf("account" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,10,TimeUnit.SECONDS).build()
        WorkManager.getInstance(this).enqueueUniqueWork("sync_$id",ExistingWorkPolicy.KEEP,request)
    }
}
