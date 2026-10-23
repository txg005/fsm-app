package com.fsm.restart

import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import androidx.work.WorkManager
import com.fsm.FsmApp
import com.fsm.MainActivity
import com.fsm.ui.FsmModel
import com.fsm.data.*
import com.fsm.sync.SyncGate
import com.fsm.sync.sha256
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/** Host-selected phases ONLY; exclude this class from the ordinary 21-case invocation.
 * Seed uses am instrument --no-restart against an already running, empty app.
 * Host observes that PID alive, force-stops, observes no PID, warms a new app process,
 * then attaches verify with --no-restart. Host must ensure offline BEFORE both warm starts.
 * Neither phase kills its own runner. No Activity recreation / network / Service E2E claim.
 */
@RunWith(AndroidJUnit4::class)
class ProcessRestartTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as FsmApp
    private val json=Json {encodeDefaults=true; ignoreUnknownKeys=false}
    private val root get()=File(app.filesDir,"restart-proof-v1")
    private val uuidPattern=Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val hashPattern=Regex("[0-9a-f]{64}")

    @Serializable private data class ResultRow(val orderId:String,val resultId:String,val jsonSha256:String)
    @Serializable private data class PhotoRow(val id:String,val orderId:String,val byteSize:Long,val sha256:String,
        val serverPhotoId:String?,val contentPath:String?)
    @Serializable private data class OpRow(val id:String,val kind:String,val orderId:String,val photoId:String?,
        val sequence:Long,val state:String,val payloadSha256:String,val wireSha256:String?,
        val dependencies:String,val attempts:Int,val nextAttemptAt:Long,val error:String?)
    @Serializable private data class Manifest(val schemaVersion:Int,val runId:String,val accountId:String,
        val draft:ResultRow,val queued:ResultRow,val photos:List<PhotoRow>,val operations:List<OpRow>)
    @Serializable private data class SeedProof(val schemaVersion:Int=1,val phase:String="seed",
        val passed:Boolean=true,val sameProcess:Boolean=true)
    @Serializable private data class VerifyProof(val schemaVersion:Int=1,val phase:String="verify",
        val passed:Boolean=true,val sameProcess:Boolean=true,val newProcess:Boolean=true,val startupRestored:Boolean=true,
        val draftDurable:Boolean=true,val queuedLocked:Boolean=true,val photosIntact:Boolean=true,
        val recoveredRunning:Boolean=true,val cleanupOwned:Boolean=true)

    private fun arguments():Pair<String,Int> {
        val args=InstrumentationRegistry.getArguments()
        val run=args.getString("restartRunId")
        val pid=args.getString("restartSeedPid")?.toIntOrNull()
        check(run!=null && uuidPattern.matches(run) && pid!=null && pid>0) {"Explicit restart phase arguments required"}
        return run to pid
    }
    private fun hash(text:String)=sha256(text.toByteArray(Charsets.UTF_8))
    private fun write(name:String,text:String) {
        val target=File(root,name)
        check(!target.exists()) {"Refusing to overwrite restart evidence"}
        val temp=File(root,"$name.tmp")
        FileOutputStream(temp).use {it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync()}
        check(temp.renameTo(target)) {"Restart evidence commit failed"}
    }
    private suspend fun <T> withoutSync(block:suspend ()->T):T {
        val owner=Any()
        withTimeout(10000) {SyncGate.mutex.lock(owner)}
        try {return block()} finally {SyncGate.mutex.unlock(owner)}
    }
    private fun tokenFiles()=File(app.applicationInfo.dataDir,"shared_prefs").listFiles().orEmpty().filter {it.name.startsWith("tokens_")}
    private fun requireEmpty() {
        check(app.repository==null && app.activeId()==null) {"Restart seed requires an unused disposable emulator"}
        check(app.getSharedPreferences("account",Context.MODE_PRIVATE).all.isEmpty()) {"Existing account preferences; refusing seed"}
        check(app.databaseList().none {it.startsWith("fsm_")}) {"Existing account database; refusing seed"}
        check(File(app.filesDir,"accounts").listFiles().isNullOrEmpty()) {"Existing account files; refusing seed"}
        check(tokenFiles().isEmpty()) {"Existing token storage; refusing seed"}
        check(!root.exists()) {"Existing restart evidence; refusing seed"}
    }

    @Test fun seedDurableState()=runBlocking {
        val (run,seedPid)=arguments()
        check(Process.myPid()==seedPid) {"Seed must attach without restarting the host-observed process"}
        requireEmpty() // BEFORE mkdir/open/seed/cancel/delete, including any cleanup registration.
        withoutSync {
            check(root.mkdir()) {"Cannot create isolated restart evidence"}
            val repo=app.open(run)
            val site=SiteDto(uuid(),"Synthetic restart site","Synthetic address")
            val equipment=EquipmentDto(uuid(),site.id,"Synthetic equipment","RESTART-ONLY")
            fun order()=WorkOrderDto(uuid(),equipment.id,run,"Synthetic restart order","Synthetic description",null,
                "ASSIGNED",1,listOf(ChecklistItemDto(uuid(),0,"Synthetic check",true)),
                listOf(MeasurementSpecDto("pressure","Synthetic pressure","bar",true,"0","10")),null)
            val draftOrder=order(); val queuedOrder=order()
            repo.db.withTransaction {
                repo.store.employee(Employee(run,wire.encodeToString(EmployeeDto(run,"Synthetic restart engineer","synthetic.restart"))))
                repo.store.site(Site(site.id,wire.encodeToString(site)))
                repo.store.equipment(Equipment(equipment.id,site.id,wire.encodeToString(equipment)))
                listOf(draftOrder,queuedOrder).forEach {repo.applyCanonical(it)}
            }
            val fixture=wire.parseToJsonElement(instrumentation.context.assets.open("contract-fixtures.json").bufferedReader().use {it.readText()}).jsonObject
            // Read ONLY the public JPEG fixture, never auth examples.
            val bytes=android.util.Base64.decode(fixture.getValue("photoUpload").jsonObject.getValue("jpegBase64").jsonPrimitive.content,android.util.Base64.DEFAULT)
            for(order in listOf(draftOrder,queuedOrder)) {
                repo.create(order.id)
                val content=wire.decodeFromString<ResultWriteDto>(requireNotNull(repo.store.result(order.id)).json)
                repo.save(order.id,content.copy(answers=order.checklistItems.map {AnswerDto(it.id,true)},
                    readings=listOf(ReadingDto("pressure","2.40")),comment="Synthetic durable restart draft"))
                val photoId=uuid(); val file=File(repo.files,"$photoId.jpg")
                FileOutputStream(file).use {it.write(bytes); it.fd.sync()}
                repo.addPhoto(order.id,file,photoId,sha256(bytes))
            }
            repo.enqueue(queuedOrder.id,LocationDto.missing("DENIED"))
            val future=System.currentTimeMillis()+86400000L
            val ops=repo.store.operations()
            check(ops.size==2 && ops[0].kind=="UPLOAD_PHOTO" && ops[1].kind=="CLOSE_WORK_ORDER") {"Unexpected synthetic outbox"}
            // Seeded Room receipt + interrupted close, NOT a network end-to-end claim.
            val queuedPhoto=repo.store.photos(queuedOrder.id).single()
            val serverId=uuid()
            val final=wire.decodeFromString<CloseRequest>(ops[1].frozenPayload)
            repo.db.withTransaction {
                repo.store.photo(queuedPhoto.copy(serverPhotoId=serverId,contentPath="/api/v1/photos/$serverId/content"))
                repo.store.operation(ops[0].copy(state="ACKED",attempts=1))
                repo.store.operation(ops[1].copy(state="RUNNING",attempts=3,nextAttemptAt=future,
                    wireBody=wire.encodeToString(final.copy(result=final.result.copy(photoIds=listOf(serverId))))))
            }
            fun result(row:InspectionResult)=ResultRow(row.workOrderId,row.id,hash(row.json))
            val manifest=Manifest(schemaVersion=1,runId=run,accountId=run,
                draft=result(requireNotNull(repo.store.result(draftOrder.id))),queued=result(requireNotNull(repo.store.result(queuedOrder.id))),
                photos=repo.store.allPhotos().map {PhotoRow(it.id,it.workOrderId,it.byteSize,it.sha256,it.serverPhotoId,it.contentPath)},
                operations=repo.store.operations().map {opRow(it)})
            validate(manifest,run)
            write("manifest.json",json.encodeToString(manifest))
            // Keep DB open and process alive; host, NOT this phase, performs the real force-stop.
            write("seed-result.json",json.encodeToString(SeedProof()))
        }
    }

    private fun opRow(op:Operation)=OpRow(op.id,op.kind,op.workOrderId,op.photoId,op.sequence,op.state,
        hash(op.frozenPayload),op.wireBody?.let(::hash),op.dependencies,op.attempts,op.nextAttemptAt,op.error)
    private fun validate(m:Manifest,run:String) {
        check(m.schemaVersion==1 && m.runId==run && m.accountId==run) {"Invalid restart manifest identity"}
        val ids=listOf(m.draft.orderId,m.queued.orderId,m.draft.resultId,m.queued.resultId)+m.photos.map {it.id}+m.operations.map {it.id}+m.photos.mapNotNull {it.serverPhotoId}+run
        check(ids.all {uuidPattern.matches(it)} && ids.distinct().size==ids.size) {"Invalid synthetic ownership identifiers"}
        check(hashPattern.matches(m.draft.jsonSha256) && hashPattern.matches(m.queued.jsonSha256)) {"Invalid result digests"}
        check(m.photos.size==2 && m.photos.map {it.orderId}.toSet()==setOf(m.draft.orderId,m.queued.orderId)) {"Invalid photo ownership"}
        check(m.photos.all {it.byteSize in 1..10485760 && hashPattern.matches(it.sha256)}) {"Invalid photo digests"}
        check(m.operations.size==2 && m.operations.all {it.orderId==m.queued.orderId && hashPattern.matches(it.payloadSha256) && (it.wireSha256==null || hashPattern.matches(it.wireSha256))}) {"Invalid operation ownership"}
        val upload=m.operations[0]; val close=m.operations[1]
        val photo=m.photos.single {it.orderId==m.queued.orderId}
        check(photo.serverPhotoId!=null && uuidPattern.matches(photo.serverPhotoId) && photo.contentPath=="/api/v1/photos/${photo.serverPhotoId}/content") {"Invalid synthetic receipt"}
        check(m.photos.single {it.orderId==m.draft.orderId}.let {it.serverPhotoId==null && it.contentPath==null}) {"Invalid draft photo checkpoint"}
        check(upload.kind=="UPLOAD_PHOTO" && upload.photoId==photo.id && upload.state=="ACKED" && upload.attempts==1 && upload.error==null && upload.wireSha256==null) {"Invalid upload checkpoint"}
        check(close.kind=="CLOSE_WORK_ORDER" && close.photoId==null && close.state=="RUNNING" && close.attempts==3 && close.error==null && close.wireSha256!=null) {"Invalid close checkpoint"}
        check(upload.dependencies=="[]" && close.dependencies==wire.encodeToString(listOf(upload.id)) && upload.sequence==1L && close.sequence==2L && upload.nextAttemptAt==0L && close.nextAttemptAt>0) {"Invalid dependency/backoff checkpoint"}
    }
    private suspend fun requireOwned(m:Manifest):Repository {
        val repo=app.repository
        check(repo!=null && repo.employeeId==m.accountId && app.activeId()==m.accountId) {"Startup did not restore owned account"}
        check(app.getSharedPreferences("account",Context.MODE_PRIVATE).all==mapOf("active" to m.accountId)) {"Unexpected account preferences"}
        check(app.databaseList().filter {it.startsWith("fsm_")}.all {it in setOf("fsm_${m.accountId}.db","fsm_${m.accountId}.db-wal","fsm_${m.accountId}.db-shm","fsm_${m.accountId}.db-journal")}) {"Foreign account database; refusing cleanup"}
        check(File(app.filesDir,"accounts").listFiles().orEmpty().map {it.name}==listOf(m.accountId)) {"Foreign account files; refusing cleanup"}
        check(tokenFiles().isEmpty()) {"Unexpected token storage; refusing cleanup"}
        check(repo.store.allOrders().map {it.id}.toSet()==setOf(m.draft.orderId,m.queued.orderId)) {"Unexpected order ownership"}
        check(repo.store.allPhotos().map {it.id}.toSet()==m.photos.map {it.id}.toSet()) {"Unexpected photo ownership"}
        check(repo.store.operations().map {it.id}.toSet()==m.operations.map {it.id}.toSet()) {"Unexpected operation ownership"}
        check(File(app.filesDir,"accounts/${m.accountId}").listFiles().orEmpty().map {it.name}==listOf("photos")) {"Unexpected account files; refusing cleanup"}
        check(repo.files.listFiles().orEmpty().map {it.name}.toSet()==m.photos.map {"${it.id}.jpg"}.toSet()) {"Unexpected photo files; refusing cleanup"}
        return repo
    }

    @Test fun verifyDurableStateAfterForceStop()=runBlocking {
        val (run,seedPid)=arguments()
        val verifyPid=InstrumentationRegistry.getArguments().getString("restartVerifyPid")?.toIntOrNull()
        check(verifyPid!=null && verifyPid>0 && Process.myPid()==verifyPid && verifyPid!=seedPid) {"Verify must attach to the new host-observed process without restarting it"}
        // Malformed/missing marker never authorizes opening accounts or deleting any resource.
        val marker=File(root,"manifest.json")
        check(marker.isFile && marker.length() in 1..65536 && !File(root,"verify-result.json").exists()) {"Missing or stale restart manifest"}
        val m=try {json.decodeFromString<Manifest>(marker.readText())} catch(_:Exception) {error("Invalid restart manifest encoding")}
        validate(m,run)
        val seedFile=File(root,"seed-result.json")
        check(seedFile.isFile && seedFile.length() in 1..4096) {"Missing seed phase proof"}
        val seed=try {json.parseToJsonElement(seedFile.readText())} catch(_:Exception) {error("Invalid seed proof encoding")}
        check(seed==json.parseToJsonElement(json.encodeToString(SeedProof()))) {"Seed phase proof missing"}
        withoutSync {
            // FsmApp.onCreate must already have restored this repository; do NOT call app.open.
            val repo=requireOwned(m)
            for((expected,lifecycle) in listOf(m.draft to "DRAFT",m.queued to "QUEUED")) {
                val actual=repo.store.result(expected.orderId)
                assertTrue("Durable result identity, lifecycle and exact JSON bytes",actual!=null && actual.id==expected.resultId && actual.lifecycle==lifecycle && hash(actual.json)==expected.jsonSha256)
            }
            assertTrue("Outbox bytes, identity, attempts and backoff survive startup unchanged",repo.store.operations().map {opRow(it)}==m.operations)
            for(expected in m.photos) {
                val actual=repo.store.photo(expected.id)
                val path=File(repo.files,"${expected.id}.jpg")
                assertTrue("Durable private photo metadata",actual!=null && actual.workOrderId==expected.orderId && actual.ownerId==m.accountId && actual.localPath==path.absolutePath && actual.byteSize==expected.byteSize && actual.sha256==expected.sha256 && actual.serverPhotoId==expected.serverPhotoId && actual.contentPath==expected.contentPath)
                assertTrue("Durable JPEG bytes and hash",path.isFile && path.length()==expected.byteSize && sha256(path.readBytes())==expected.sha256)
            }
            val queued=wire.decodeFromString<ResultWriteDto>(requireNotNull(repo.store.result(m.queued.orderId)).json)
            suspend fun rejected(block:suspend ()->Unit) {
                var blocked=false
                try {block()} catch(_:IllegalStateException) {blocked=true}
                assertTrue("Queued mutation remains locked after process restart",blocked)
            }
            rejected {repo.save(m.queued.orderId,queued.copy(comment="Synthetic forbidden edit"))}
            rejected {repo.deleteDraft(m.queued.orderId)}
            rejected {repo.removePhoto(m.queued.orderId,m.photos.single {it.orderId==m.queued.orderId}.id)}
            assertTrue("Rejected mutations preserve queued bytes",hash(requireNotNull(repo.store.result(m.queued.orderId)).json)==m.queued.jsonSha256)
            // Explicit local recovery AFTER startup assertions; startup itself promises no auto-recover.
            val recoveryBefore=System.currentTimeMillis()
            repo.recover()
            val recovered=repo.store.operations()
            val close=m.operations[1]
            val actual=recovered.first {it.id==close.id}
            assertTrue("Interrupted close recovers without changing frozen identity",opRow(actual)==close.copy(state="RETRY_WAIT",attempts=close.attempts+1,error="UNKNOWN_OUTCOME",nextAttemptAt=actual.nextAttemptAt))
            assertTrue("Recovery preserves durable backoff floor",actual.nextAttemptAt>=maxOf(close.nextAttemptAt,recoveryBefore+2000))
            assertTrue("ACKed upload remains byte-identical",opRow(recovered.first {it.id==m.operations[0].id})==m.operations[0])
            repo.recover()
            assertTrue("Recovery remains idempotent",repo.store.operations()==recovered)
            // After all proof assertions, replace the synthetic warm task so its collectors
            // are destroyed, then close the known Activity before deleting the owned DB.
            var model:FsmModel?=null
            val cleanupActivity=ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            try {cleanupActivity.onActivity {model=ViewModelProvider(it)[FsmModel::class.java]}}
            finally {cleanupActivity.close()}
            withTimeout(10000) {model!!.viewModelScope.coroutineContext[Job]!!.join()}
            requireOwned(m) // Revalidate ownership before any cancellation / deletion.
            WorkManager.getInstance(app).cancelUniqueWork("sync_${m.accountId}").result.get(10,TimeUnit.SECONDS)
            app.clearActive(); repo.db.close()
            check(app.deleteDatabase("fsm_${m.accountId}.db")) {"Owned database cleanup failed"}
            check(File(app.filesDir,"accounts/${m.accountId}").deleteRecursively()) {"Owned photo cleanup failed"}
            app.deleteSharedPreferences("tokens_${m.accountId}")
            write("verify-result.json",json.encodeToString(VerifyProof()))
        }
    }
}
