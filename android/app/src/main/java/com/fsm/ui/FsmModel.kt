package com.fsm.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.fsm.FsmApp
import com.fsm.data.*
import com.fsm.service.SyncService
import com.fsm.sync.SyncGate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FsmModel(application:Application,private val saved:SavedStateHandle):AndroidViewModel(application) {
    val app=application as FsmApp
    var repo by mutableStateOf(app.repository); private set
    var route by mutableStateOf(if(repo==null) "login" else (saved.get<String>("route") ?: "list")); private set
    var selected by mutableStateOf(saved.get<String>("selected")); private set
    var orders by mutableStateOf<List<WorkOrder>>(emptyList()); private set
    var results by mutableStateOf<List<InspectionResult>>(emptyList()); private set
    var photos by mutableStateOf<List<Photo>>(emptyList()); private set
    var operations by mutableStateOf<List<Operation>>(emptyList()); private set
    var logs by mutableStateOf<List<AppLog>>(emptyList()); private set
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false); private set
    var saving by mutableStateOf(false); private set
    var edits by mutableStateOf<ResultWriteDto?>(null); private set
    var shadow by mutableStateOf<String?>(null); private set
    private val editLock=Mutex()
    private var pendingSaves=0
    private var collectors:Job?=null
    init { observe(); repo?.let { app.schedule(it.employeeId) } }
    private fun observe() {
        collectors?.cancel()
        val current=repo ?: return
        collectors=viewModelScope.launch {
            launch(Dispatchers.IO) { try { current.cleanupOrphans() } catch(_:Exception) { current.logger.record(Event.STORAGE_ERROR) } }
            launch { current.store.orders().collect { orders=it } }
            launch { current.store.results().collect { results=it } }
            launch { current.store.photosFlow().collect { photos=it } }
            launch { current.store.operationsFlow().collect { operations=it } }
            launch { current.store.logs().collect { logs=it } }
        }
    }
    fun go(to:String,id:String?=selected) { selected=id; saved["selected"]=id; route=to; saved["route"]=to; error=null }
    fun action(block:suspend ()->Unit) {
        if(busy) return
        busy=true; error=null
        viewModelScope.launch {
            try { block() }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { error=UiText.actionError(e) }
            finally { busy=false }
        }
    }
    fun login(login:String,password:String)=action {
        repo=app.login(login,password); edits=null; observe(); go("list",null)
        // A snapshot error must not turn a successful login into fake metadata.
        requireNotNull(repo).snapshot(); app.schedule(requireNotNull(repo).employeeId)
    }
    fun begin()=action {
        val r=requireNotNull(repo); val id=requireNotNull(selected)
        editLock.withLock { r.create(id); edits=wire.decodeFromString(requireNotNull(r.store.result(id)).json) }
        go("form")
    }
    fun loadForm()=action {
        editLock.withLock { edits=repo?.store?.result(requireNotNull(selected))?.let { wire.decodeFromString(it.json) } }
        go("form")
    }
    fun edit(value:ResultWriteDto) {
        val current=repo ?: return; val id=selected ?: return
        edits=value
        viewModelScope.launch {
            pendingSaves++; saving=true
            try { editLock.withLock { current.save(id,value) } }
            catch(e:CancellationException) { throw e }
            catch(_:Exception) { error="Не удалось сохранить ввод на устройстве" }
            finally { pendingSaves--; saving=pendingSaves>0 }
        }
    }
    fun deleteDraft()=action {
        editLock.withLock { requireNotNull(repo).deleteDraft(requireNotNull(selected)); edits=null }; go("detail")
    }
    fun importPhoto(uri:Uri,orderId:String)=action { Media.importPhoto(app,requireNotNull(repo),orderId,uri) }
    fun removePhoto(id:String)=action { requireNotNull(repo).removePhoto(requireNotNull(selected),id) }
    fun close()=action {
        val r=requireNotNull(repo); val id=requireNotNull(selected)
        editLock.withLock {
            val current=requireNotNull(r.store.result(id))
            val order=wire.decodeFromString<WorkOrderDto>(requireNotNull(r.store.order(id)).json)
            val validation=Validation.errors(order,wire.decodeFromString(current.json))
            if(validation.isNotEmpty()) throw InvalidInspection(validation)
        }
        val location=Media.location(app)
        editLock.withLock { r.enqueue(id,location) }
        r.logger.record(Event.QUEUED); app.schedule(r.employeeId); edits=null; go("detail")
    }
    fun sync() {
        error=null
        try { androidx.core.content.ContextCompat.startForegroundService(app,Intent(app,SyncService::class.java)) }
        catch(_:Exception) { error="Не удалось запустить передачу. Очередь сохранена. Повторите из открытого приложения." }
    }
    fun stopSync() { repo?.let { app.cancelBackground(it.employeeId) }; app.stopService(Intent(app,SyncService::class.java)) }
    fun refresh()=action { SyncGate.mutex.withLock { requireNotNull(repo).snapshot() } }
    fun conflict()=action {
        shadow=null; go("conflict")
        SyncGate.mutex.withLock { shadow=requireNotNull(repo).freshCanonical(requireNotNull(selected)) }
    }
    fun resolve(server:Boolean)=action {
        val shown=shadow; shadow=null
        try {
            SyncGate.mutex.withLock { if(server) requireNotNull(repo).acceptServer(requireNotNull(selected),shown) else requireNotNull(repo).returnToDraft(requireNotNull(selected),shown) }
            edits=null; go("detail")
        } catch(e:CanonicalChanged) {
            shadow=e.canonical; go("conflict")
            error="Серверная версия изменилась. Сравните показанные данные и подтвердите выбор заново. Локальные данные и очередь сохранены."
        }
    }
    fun logout()=action {
        stopSync()
        val r=repo
        r?.let { WorkManager.getInstance(app).cancelUniqueWork("sync_${it.employeeId}") }
        SyncGate.mutex.withLock {
            try { withTimeout(10000) { r?.api?.logout() } } catch(_:Exception) { /* Offline logout does not promise server revocation. */ }
            r?.api?.clearAccess(); app.clearActive(); repo=null
        }
        collectors?.cancel(); orders=emptyList(); results=emptyList(); photos=emptyList(); operations=emptyList(); logs=emptyList(); edits=null; shadow=null; go("login",null)
    }
}
