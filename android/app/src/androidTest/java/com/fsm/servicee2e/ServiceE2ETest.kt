package com.fsm.servicee2e

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import com.fsm.BuildConfig
import com.fsm.FsmApp
import com.fsm.MainActivity
import com.fsm.data.*
import com.fsm.service.SyncService
import com.fsm.service.SyncWorker
import com.fsm.sync.SyncGate
import com.fsm.sync.sha256
import com.fsm.ui.FsmModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Opt-in ONLY: atomically exclude from ordinary/restart discovery when adding this file.
 * Real UI send -> foreground Service -> real Netty/PG -> same-screen live CLOSED.
 * A delayed own KEEP reservation isolates Service attribution; this is NOT a Worker policy,
 * picker/import, GPS, cancel, process-death, or account-switch test.
 */
@RunWith(AndroidJUnit4::class)
class ServiceE2ETest {
    private val compose=createEmptyComposeRule()
    // Outermost: includes Compose rule setup/teardown, not merely assertions in the body.
    private val sanitized=TestRule {base,_ -> object:Statement() {
        override fun evaluate() {
            try {base.evaluate()} catch(_:Throwable) {throw AssertionError("SERVICE_E2E_FAILED")}
        }
    }}
    @get:Rule val rules:RuleChain=RuleChain.outerRule(sanitized).around(compose)
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as FsmApp
    private val wm get()=WorkManager.getInstance(app)
    private val root get()=File(app.filesDir,"service-e2e-v1")
    private val json=Json {encodeDefaults=true; ignoreUnknownKeys=false; isLenient=false}
    private val uuidPattern=Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val utcMillis=Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z")
    private val gateOwner=Any()
    private var ownsGate=false
    private var fixture:Fixture?=null
    private var emptyStorageProven=false
    private var loginAttempted=false
    private var serviceStartAttempted=false
    private var repo:Repository?=null
    private var scenario:ActivityScenario<MainActivity>?=null
    private var activity:MainActivity?=null
    private lateinit var model:FsmModel
    private var parkedId:UUID?=null
    private var workWatcher:Job?=null
    private val ownedPhotos=mutableSetOf<String>()
    private var deadlineElapsed=0L
    private var deadlineWall=0L

    @Serializable private data class Fixture(val schemaVersion:Int,val fixtureExpectationVersion:Int,
        val runNonce:String,val employeeId:String,val workOrderId:String,val checklistItemIds:List<String>,
        val expectedJpegSha256:String,val expectedJpegByteSize:Long,val login:String,val password:String)
    @Serializable private data class ServiceReady(val schemaVersion:Int,val runNonce:String,val servicePid:Int,
        val serviceActiveSince:Long,val workerQuiescent:Boolean,val workerReservationVerified:Boolean,
        val serviceForeground:Boolean,val notificationObserved:Boolean)
    @Serializable private data class ServiceRelease(val schemaVersion:Int,val runNonce:String,val servicePid:Int,
        val serviceActiveSince:Long,val dataSyncObserved:Boolean,val workerQuiescent:Boolean,val workerReservationVerified:Boolean)
    @Serializable private data class CloseCommitted(val schemaVersion:Int,val runNonce:String,val gateNonce:String,
        val closeOperationId:String,val hostCommittedAt:String,val releaseDeadlineAt:String)
    @Serializable private data class CloseObserved(val schemaVersion:Int,val runNonce:String,val gateNonce:String,
        val closeOperationId:String,val servicePid:Int,val serviceActiveSince:Long,val workerQuiescent:Boolean,
        val workerReservationVerified:Boolean,val serviceForeground:Boolean,val notificationObserved:Boolean,
        val uploadACK:Boolean,val closeRUNNING:Boolean,val liveUiQueued:Boolean)
    @Serializable private data class Result(val schemaVersion:Int,val fixtureExpectationVersion:Int,val runNonce:String,
        val uploadOperationId:String,val closeOperationId:String,val resultId:String,val localPhotoId:String,
        val serverPhotoId:String,val jpegSha256:String,val jpegByteSize:Long,val resultCapturedAt:String,
        val closeFrozenPayloadSha256:String,val closeWireBodySha256:String,val workerQuiescent:Boolean,
        val workerReservationVerified:Boolean,val serviceObservedForeground:Boolean,val serviceNotificationObserved:Boolean,
        val serviceSameInstanceAtClose:Boolean,val workerQuiescentAtClose:Boolean,val closeBarrierObserved:Boolean,
        val uploadACK:Boolean,val closeACK:Boolean,val liveUiClosed:Boolean,val finalLifecycle:String)

    /** Field types checked BEFORE decoding: Json tree decoding alone is not a strict type oracle. */
    private data class Shape(val strings:Set<String>,val integers:Set<String>,val flags:Set<String> = emptySet(),
        val stringArrays:Set<String> = emptySet())
    private val fixtureShape=Shape(setOf("runNonce","employeeId","workOrderId","expectedJpegSha256","login","password"),
        setOf("schemaVersion","fixtureExpectationVersion","expectedJpegByteSize"),stringArrays=setOf("checklistItemIds"))
    private val releaseShape=Shape(setOf("runNonce"),setOf("schemaVersion","servicePid","serviceActiveSince"),
        setOf("dataSyncObserved","workerQuiescent","workerReservationVerified"))
    private val committedShape=Shape(setOf("runNonce","gateNonce","closeOperationId","hostCommittedAt","releaseDeadlineAt"),setOf("schemaVersion"))

    private fun checkedPath(name:String):File {
        check(name in setOf("fixture.json","service-ready.json","release-service.json","close-committed.json","close-observed.json","result.json"))
        check(root.isDirectory && !Files.isSymbolicLink(root.toPath()))
        check(root.canonicalFile==File(app.filesDir.canonicalFile,"service-e2e-v1"))
        return File(root,name)
    }
    private val privatePermissions=setOf(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE)
    private fun ready(name:String):Boolean {
        checkedPath(name)
        val marker=File(root,"$name.ready").toPath()
        if(!Files.exists(marker,LinkOption.NOFOLLOW_LINKS)) return false
        check(Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS) &&
            Files.getPosixFilePermissions(marker,LinkOption.NOFOLLOW_LINKS)==privatePermissions && Files.size(marker)==0L)
        return true
    }
    private inline fun <reified T> read(name:String,shape:Shape):T {
        check(ready(name)) // Never consume a payload while its producer may still be writing.
        val file=checkedPath(name)
        check(Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS) &&
            Files.getPosixFilePermissions(file.toPath(),LinkOption.NOFOLLOW_LINKS)==privatePermissions)
        val bytes=FileChannel.open(file.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS).use {channel ->
            check(channel.size() in 1..16384)
            val buffer=ByteBuffer.allocate(channel.size().toInt())
            while(buffer.hasRemaining()) check(channel.read(buffer)>0)
            check(channel.size()==buffer.capacity().toLong())
            buffer.array()
        }
        val text=bytes.toString(Charsets.UTF_8)
        check(text.toByteArray(Charsets.UTF_8).contentEquals(bytes))
        val value=json.parseToJsonElement(text).jsonObject
        // Compact canonical input equality rejects duplicate/escaped-duplicate keys and whitespace.
        check(text==value.toString())
        check(value.keys==shape.strings+shape.integers+shape.flags+shape.stringArrays)
        shape.strings.forEach {key -> check(value[key] is JsonPrimitive && value.getValue(key).jsonPrimitive.isString)}
        shape.integers.forEach {key ->
            val p=value.getValue(key).jsonPrimitive
            check(!p.isString && Regex("0|[1-9][0-9]*").matches(p.content) && p.longOrNull!=null)
        }
        shape.flags.forEach {key -> val p=value.getValue(key).jsonPrimitive; check(!p.isString && p.content=="true")}
        shape.stringArrays.forEach {key ->
            val a=value.getValue(key).jsonArray
            check(a.size in 1..10 && a.all {it is JsonPrimitive && it.isString})
        }
        return json.decodeFromJsonElement(value)
    }
    private inline fun <reified T> write(name:String,value:T) {
        val target=checkedPath(name)
        val text=json.encodeToString(value)
        check(text.toByteArray(Charsets.UTF_8).size in 1..16384)
        val marker=File(root,"$name.ready").toPath()
        check(!Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS) && !Files.exists(marker,LinkOption.NOFOLLOW_LINKS))
        val attributes=PosixFilePermissions.asFileAttribute(privatePermissions)
        FileChannel.open(target.toPath(),setOf(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),attributes).use {
            val data=ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
            while(data.hasRemaining()) it.write(data)
            it.force(true)
        }
        check(Files.getPosixFilePermissions(target.toPath(),LinkOption.NOFOLLOW_LINKS)==privatePermissions)
        // Publish completion only after closing the create-once payload; no hard links or rename.
        FileChannel.open(marker,setOf(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),attributes).use { }
        check(ready(name))
    }
    private fun hash(value:String)=sha256(value.toByteArray(Charsets.UTF_8))
    private fun instant(value:String):Instant {check(utcMillis.matches(value)); return Instant.parse(value)}
    private suspend fun until(timeoutMs:Long,predicate:suspend ()->Boolean) {
        val end=minOf(deadlineElapsed,SystemClock.elapsedRealtime()+timeoutMs)
        while(!predicate()) {check(SystemClock.elapsedRealtime()<end); delay(25)}
    }
    private fun tokenFiles()=File(app.applicationInfo.dataDir,"shared_prefs").listFiles().orEmpty().filter {it.name.startsWith("tokens_")}
    @Suppress("DEPRECATION") private fun service():ActivityManager.RunningServiceInfo? =
        app.getSystemService(ActivityManager::class.java).getRunningServices(100).singleOrNull {
            it.service==ComponentName(app,SyncService::class.java)
        }
    private fun notificationVisible():Boolean=app.getSystemService(NotificationManager::class.java).activeNotifications.any {
        it.id==41 && it.packageName==app.packageName && it.notification.channelId=="sync" &&
            it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE!=0
    }
    private fun requireForeground(initial:ActivityManager.RunningServiceInfo?=null):ActivityManager.RunningServiceInfo {
        val current=requireNotNull(service())
        check(current.foreground && current.started && current.pid>0 && current.activeSince>0 && notificationVisible())
        if(initial!=null) check(current.pid==initial.pid && current.activeSince==initial.activeSince)
        return current
    }
    private fun workName()="sync_${requireNotNull(fixture).employeeId}"
    private fun workInfos()=wm.getWorkInfosForUniqueWork(workName()).get(5,TimeUnit.SECONDS)
    private fun reservation(infos:List<WorkInfo> = workInfos()) {
        val active=infos.filterNot {it.state.isFinished}
        check(active.size==1 && active.single().id==parkedId && active.single().state==WorkInfo.State.ENQUEUED)
        check(active.single().runAttemptCount==0 && active.single().initialDelayMillis==TimeUnit.DAYS.toMillis(1))
        check(active.single().nextScheduleTimeMillis>deadlineWall && active.single().nextScheduleTimeMillis!=Long.MAX_VALUE)
    }
    private fun requireSameScreen() {
        scenario!!.onActivity {check(it===activity && ViewModelProvider(it)[FsmModel::class.java]===model)}
        check(scenario!!.state==Lifecycle.State.RESUMED && model.route=="detail" && model.selected==fixture!!.workOrderId)
    }
    private fun queuedScreen() {
        requireSameScreen()
        check(model.results.single {it.workOrderId==fixture!!.workOrderId}.lifecycle=="QUEUED")
        compose.onNodeWithText("Заявка закрыта, результат отправлен",substring=true).assertDoesNotExist()
    }

    @Test fun visibleSendRunsForegroundServiceToRealBackendAndUpdatesUi() {
        var result:Result?=null
        runBlocking {
            deadlineElapsed=SystemClock.elapsedRealtime()+120000
            deadlineWall=System.currentTimeMillis()+120000
            try {withTimeout(120000) {result=runPositive()}}
            finally {withContext(NonCancellable) {withTimeout(30000) {cleanup()}}}
        }
        write("result.json",requireNotNull(result)) // No auth/PID/paths/raw DTO ever exported.
    }
    private suspend fun runPositive():Result=coroutineScope {
        check(BuildConfig.DEBUG && BuildConfig.API_URL=="http://127.0.0.1:18080")
        val nonce=requireNotNull(InstrumentationRegistry.getArguments().getString("runNonce"))
        check(uuidPattern.matches(nonce))
        val f=read<Fixture>("fixture.json",fixtureShape)
        check(f.schemaVersion==1 && f.fixtureExpectationVersion==1 && f.runNonce==nonce)
        check(listOf(f.employeeId,f.workOrderId).all {uuidPattern.matches(it)} && f.employeeId!=f.workOrderId)
        check(f.checklistItemIds.size==1 && f.checklistItemIds.all {uuidPattern.matches(it)})
        check(f.expectedJpegByteSize==631L && f.expectedJpegSha256=="4a0043beb47e76e4bf6a12e9d93832dc69d596c8d1b114870e17f9d1420d61bf")
        check(Regex("[a-z0-9_.-]{1,64}").matches(f.login) && f.password.length in 12..128 && f.password.all {it.code in 33..126})
        check(app.repository==null && app.activeId()==null && service()==null)
        check(app.getSharedPreferences("account",Context.MODE_PRIVATE).all.isEmpty())
        check(app.databaseList().none {it.startsWith("fsm_")})
        check(File(app.filesDir,"accounts").listFiles().isNullOrEmpty() && tokenFiles().isEmpty())
        check(root.listFiles().orEmpty().map {it.name}.toSet()==setOf("fixture.json","fixture.json.ready"))
        check(app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
        check(wm.getWorkInfosForUniqueWork("sync_${f.employeeId}").get(5,TimeUnit.SECONDS).isEmpty())
        check(!KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.containsAlias("fsm_refresh_${f.employeeId}"))
        fixture=f; emptyStorageProven=true
        withTimeout(10000) {SyncGate.mutex.lock(gateOwner)}; ownsGate=true
        scenario=ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity {activity=it; model=ViewModelProvider(it)[FsmModel::class.java]}
        compose.onNodeWithText("Логин").performTextInput(f.login)
        compose.onNodeWithText("Пароль").performTextInput(f.password)
        loginAttempted=true
        compose.onNodeWithText("Войти").performScrollTo().performClick()
        compose.waitUntil(60000) {!model.busy && model.route=="list" && model.repo?.employeeId==f.employeeId && model.orders.any {it.id==f.workOrderId}}
        repo=requireNotNull(model.repo); check(app.repository===repo && app.activeId()==f.employeeId && model.error==null)
        val r=requireNotNull(repo)
        val order=wire.decodeFromString<WorkOrderDto>(requireNotNull(r.store.order(f.workOrderId)).json)
        check(order.status=="ASSIGNED" && order.revision==1L && order.assigneeId==f.employeeId && order.checklistItems.map {it.id}==f.checklistItemIds)
        check(order.measurementSpecs.single().let {it.key=="pressure" && it.min=="0" && it.max=="10"})
        wm.cancelUniqueWork(workName()).result.get(10,TimeUnit.SECONDS)
        until(10000) {workInfos().all {it.state.isFinished}}
        val parked=OneTimeWorkRequestBuilder<SyncWorker>().setInputData(workDataOf("account" to f.employeeId))
            .setInitialDelay(1,TimeUnit.DAYS).build()
        parkedId=parked.id
        wm.enqueueUniqueWork(workName(),ExistingWorkPolicy.KEEP,parked).result.get(10,TimeUnit.SECONDS)
        reservation()
        workWatcher=launch {wm.getWorkInfosForUniqueWorkFlow(workName()).collect {reservation(it)}}
        r.create(order.id)
        val draft=wire.decodeFromString<ResultWriteDto>(requireNotNull(r.store.result(order.id)).json)
        r.save(order.id,draft.copy(answers=f.checklistItemIds.map {AnswerDto(it,true)},
            readings=listOf(ReadingDto("pressure","2.40")),comment="Демонстрационный результат"))
        val source=wire.parseToJsonElement(instrumentation.context.assets.open("contract-fixtures.json").bufferedReader().use {it.readText()}).jsonObject
        val bytes=android.util.Base64.decode(source.getValue("photoUpload").jsonObject.getValue("jpegBase64").jsonPrimitive.content,android.util.Base64.DEFAULT)
        check(bytes.size.toLong()==f.expectedJpegByteSize && sha256(bytes)==f.expectedJpegSha256)
        val photoId=uuid(); ownedPhotos.add(photoId)
        val photoFile=File(r.files,"$photoId.jpg")
        Media.filesMutex.withLock {
            FileChannel.open(photoFile.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS).use {
                val buffer=ByteBuffer.wrap(bytes); while(buffer.hasRemaining()) it.write(buffer); it.force(true)
            }
            r.addPhoto(order.id,photoFile,photoId,f.expectedJpegSha256)
        }
        // Production local finalization, not a GPS permission scenario and NEVER a seeded ACK.
        r.enqueue(order.id,LocationDto.missing("DENIED"))
        val queued=wire.decodeFromString<ResultWriteDto>(requireNotNull(r.store.result(order.id)).json)
        instant(queued.capturedAt)
        val before=r.store.operations()
        check(before.size==2 && before.all {it.state=="PENDING" && it.attempts==0})
        val upload=before.single {it.kind=="UPLOAD_PHOTO"}; val close=before.single {it.kind=="CLOSE_WORK_ORDER"}
        check(uuidPattern.matches(upload.id) && uuidPattern.matches(close.id) && close.wireBody==null)
        check(upload.photoId==photoId && wire.decodeFromString<List<String>>(close.dependencies)==listOf(upload.id))
        until(5000) {model.results.any {it.id==queued.id && it.lifecycle=="QUEUED"} && model.operations.size==2}
        serviceStartAttempted=true
        check(scenario!!.state==Lifecycle.State.RESUMED)
        compose.onNodeWithText("Отправить сохранённые работы").performScrollTo().performClick()
        until(5000) {service()?.foreground==true && notificationVisible()}
        val initial=requireForeground(); reservation()
        // The real seed has two own orders with the same title. Match UI's list order,
        // not an arbitrary first text node, and assert the selector cardinality first.
        val sameTitle=model.orders.filter {wire.decodeFromString<WorkOrderDto>(it.json).title==order.title}
        val rowIndex=sameTitle.indexOfFirst {it.id==order.id}; check(rowIndex>=0)
        val rows=compose.onAllNodes(hasText(order.title,substring=true) and hasClickAction())
        rows.assertCountEquals(sameTitle.size)
        rows[rowIndex].performScrollTo().performClick()
        queuedScreen()
        write("service-ready.json",ServiceReady(1,nonce,initial.pid,initial.activeSince,true,true,true,true))
        until(30000) {reservation(); requireForeground(initial); ready("release-service.json")}
        val released=read<ServiceRelease>("release-service.json",releaseShape)
        check(released.schemaVersion==1 && released.runNonce==nonce && released.servicePid==initial.pid && released.serviceActiveSince==initial.activeSince)
        check(released.dataSyncObserved && released.workerQuiescent && released.workerReservationVerified)
        queuedScreen(); reservation(); requireForeground(initial)
        SyncGate.mutex.unlock(gateOwner); ownsGate=false
        until(60000) {reservation(); requireForeground(initial); ready("close-committed.json")}
        val committed=read<CloseCommitted>("close-committed.json",committedShape)
        check(committed.schemaVersion==1 && committed.runNonce==nonce && uuidPattern.matches(committed.gateNonce) && committed.closeOperationId==close.id)
        val commitAt=instant(committed.hostCommittedAt).toEpochMilli(); val releaseAt=instant(committed.releaseDeadlineAt).toEpochMilli()
        check(releaseAt>commitAt && releaseAt-commitAt<=15000 && commitAt<=System.currentTimeMillis()+5000 && System.currentTimeMillis()<=releaseAt+5000)
        requireForeground(initial); reservation(); queuedScreen()
        val probe=Any()
        if(SyncGate.mutex.tryLock(probe)) {SyncGate.mutex.unlock(probe); error("SERVICE_E2E_FAILED")}
        val checkpoint=r.store.operations()
        check(checkpoint.single {it.id==upload.id}.state=="ACKED" && checkpoint.single {it.id==close.id}.state=="RUNNING")
        val sentClose=checkpoint.single {it.id==close.id}
        check(sentClose.frozenPayload==close.frozenPayload)
        val sentBody=requireNotNull(sentClose.wireBody)
        val wireClose=wire.decodeFromString<CloseRequest>(sentBody)
        val serverPhoto=requireNotNull(r.store.photo(photoId)?.serverPhotoId)
        check(uuidPattern.matches(serverPhoto) && wireClose.result.photoIds==listOf(serverPhoto) && wireClose.result.id==queued.id && wireClose.result.capturedAt==queued.capturedAt)
        val wireHash=hash(sentBody)
        write("close-observed.json",CloseObserved(1,nonce,committed.gateNonce,close.id,initial.pid,initial.activeSince,true,true,true,true,true,true,true))
        // Host opens real response only after independently rechecking dataSync and same Service.
        until(20000) {
            reservation()
            r.store.operations().let {ops -> ops.size==2 && ops.all {it.state=="ACKED"}}
        }
        until(5000) {model.results.any {it.id==queued.id && it.lifecycle=="CLOSED"} && model.orders.any {wire.decodeFromString<WorkOrderDto>(it.json).let {dto -> dto.id==order.id && dto.status=="CLOSED"}}}
        requireSameScreen()
        compose.onNodeWithText("Заявка закрыта, результат отправлен",substring=true).performScrollTo().assertIsDisplayed()
        reservation()
        val after=r.store.operations()
        check(after.map {it.id}.toSet()==before.map {it.id}.toSet() && after.all {it.state=="ACKED"})
        check(after.single {it.id==close.id}.wireBody==sentBody && after.single {it.id==close.id}.frozenPayload==close.frozenPayload)
        val finalResult=requireNotNull(r.store.result(order.id))
        val finalContent=wire.decodeFromString<ResultWriteDto>(finalResult.json)
        val finalOrder=wire.decodeFromString<WorkOrderDto>(requireNotNull(r.store.order(order.id)).json)
        check(finalResult.id==queued.id && finalResult.lifecycle=="CLOSED" && finalContent.capturedAt==queued.capturedAt && finalOrder.revision==2L)
        val finalPhoto=requireNotNull(r.store.photo(photoId))
        check(finalPhoto.localPath==photoFile.absolutePath && finalPhoto.serverPhotoId==serverPhoto && finalPhoto.contentPath=="/api/v1/photos/$serverPhoto/content")
        check(photoFile.isFile && photoFile.length()==f.expectedJpegByteSize && sha256(photoFile.readBytes())==f.expectedJpegSha256)
        workWatcher?.cancelAndJoin(); workWatcher=null
        Result(1,1,nonce,upload.id,close.id,queued.id,photoId,serverPhoto,f.expectedJpegSha256,f.expectedJpegByteSize,
            queued.capturedAt,hash(close.frozenPayload),wireHash,true,true,true,true,true,true,true,true,true,true,"CLOSED")
    }

    private fun requireOwned(r:Repository,f:Fixture) {
        check(emptyStorageProven && loginAttempted && app.repository===r && app.activeId()==f.employeeId && r.employeeId==f.employeeId)
        check(app.getSharedPreferences("account",Context.MODE_PRIVATE).all==mapOf("active" to f.employeeId))
        val allowedDb=setOf("fsm_${f.employeeId}.db","fsm_${f.employeeId}.db-wal","fsm_${f.employeeId}.db-shm","fsm_${f.employeeId}.db-journal")
        check(app.databaseList().filter {it.startsWith("fsm_")}.all {it in allowedDb})
        val accounts=File(app.filesDir,"accounts")
        val account=File(accounts,f.employeeId)
        check(!Files.isSymbolicLink(accounts.toPath()) && !Files.isSymbolicLink(account.toPath()) && !Files.isSymbolicLink(r.files.toPath()))
        check(accounts.listFiles().orEmpty().map {it.name}==listOf(f.employeeId))
        check(app.databaseList().filter {it.startsWith("fsm_")}.none {Files.isSymbolicLink(app.getDatabasePath(it).toPath())})
        check(File(app.filesDir,"accounts/${f.employeeId}").listFiles().orEmpty().all {it.name=="photos" && !Files.isSymbolicLink(it.toPath())})
        check(r.files.listFiles().orEmpty().all {it.name in ownedPhotos.map {id -> "$id.jpg"} && !Files.isSymbolicLink(it.toPath())})
        check(tokenFiles().all {it.name in setOf("tokens_${f.employeeId}.xml","tokens_${f.employeeId}.xml.bak") && !Files.isSymbolicLink(it.toPath())})
    }
    private suspend fun cleanup() {
        try {
            workWatcher?.cancelAndJoin(); workWatcher=null
            // Cancel any in-flight UI login before deciding whether it created an owned account.
            // Closing the Activity does not cancel the independently scoped Service.
            scenario?.close(); scenario=null
            if(::model.isInitialized) withTimeout(10000) {model.viewModelScope.coroutineContext[Job]?.join()}
            val f=fixture
            val candidate=if(emptyStorageProven && loginAttempted) app.repository else null
            check(candidate!=null || repo==null)
            if(candidate!=null) {
                check(f!=null); requireOwned(candidate,f)
                // If released, Service may own gate: stop/cancel FIRST, never reacquire then wait.
                if(serviceStartAttempted) app.stopService(Intent(app,SyncService::class.java))
                wm.cancelUniqueWork(workName()).result.get(10,TimeUnit.SECONDS)
                untilCleanup {service()==null && workInfos().all {it.state.isFinished}}
                if(!ownsGate) {withTimeout(10000) {SyncGate.mutex.lock(gateOwner)}; ownsGate=true}
                requireOwned(candidate,f)
                check(candidate.store.allOrders().all {it.assigneeId==f.employeeId})
                check(candidate.store.allPhotos().all {it.id in ownedPhotos})
                candidate.api.clearAccess(); app.clearActive()
                if(!serviceStartAttempted) {
                    candidate.db.close()
                    check(app.deleteDatabase("fsm_${f.employeeId}.db"))
                    check(File(app.filesDir,"accounts/${f.employeeId}").deleteRecursively())
                }
                // Service disappearance + engine mutex release do NOT join its coroutine:
                // a NonCancellable SYNC_CANCELLED logger may still use this DB. Preserve own
                // DB/photos whenever a Service start was attempted, even on the success path.
                // Destructive cleanup is deferred to teardown of this disposable emulator.
                // Clearing own auth above prevents any late Worker from dispatching HTTP.
                app.deleteSharedPreferences("tokens_${f.employeeId}")
                val alias="fsm_refresh_${f.employeeId}"
                KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.let {if(it.containsAlias(alias)) it.deleteEntry(alias)}
            }
            if(emptyStorageProven) {
                check(ready("fixture.json"))
                check(File(root,"fixture.json.ready").delete())
                check(checkedPath("fixture.json").delete())
            }
        } finally {
            // Also release on foreign-ownership / malformed-file cleanup failure, without deletion.
            if(ownsGate) {SyncGate.mutex.unlock(gateOwner); ownsGate=false}
        }
    }
    private suspend fun untilCleanup(predicate:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+10000
        while(!predicate()) {check(SystemClock.elapsedRealtime()<end); delay(25)}
    }
}
