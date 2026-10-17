package com.fsm

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fsm.data.*
import com.fsm.sync.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RoomTest {
    private val context:Context=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db:FsmDatabase
    private lateinit var repo:Repository
    private lateinit var order:WorkOrderDto
    private lateinit var client:HttpClient
    private val name="room-test.db"
    @Before fun prepare()=runBlocking {
        context.deleteDatabase(name)
        db=Room.databaseBuilder(context,FsmDatabase::class.java,name).build()
        client=HttpClient(OkHttp)
        val fixture=wire.parseToJsonElement(InstrumentationRegistry.getInstrumentation().context.assets.open("contract-fixtures.json").bufferedReader().readText()).jsonObject
        val snapshot=wire.decodeFromJsonElement<SnapshotDto>(fixture.getValue("snapshotBeforeClose"))
        order=snapshot.workOrders.single()
        repo=Repository(db,Api(client,"https://example.test",null,snapshot.employee.id),snapshot.employee.id,File(context.filesDir,"room-test-photos").apply {mkdirs()})
        db.store().employee(Employee(snapshot.employee.id,wire.encodeToString(snapshot.employee)))
        snapshot.sites.forEach {db.store().site(Site(it.id,wire.encodeToString(it)))}
        snapshot.equipment.forEach {db.store().equipment(Equipment(it.id,it.siteId,wire.encodeToString(it)))}
        repo.applyCanonical(order)
    }
    @After fun cleanup() { db.close(); client.close(); context.deleteDatabase(name); File(context.filesDir,"room-test-photos").deleteRecursively() }
    private suspend fun validDraft() {
        repo.create(order.id)
        val row=db.store().result(order.id)!!
        val value=wire.decodeFromString<ResultWriteDto>(row.json).copy(answers=order.checklistItems.map { AnswerDto(it.id,true) },readings=order.measurementSpecs.map {ReadingDto(it.key,"2.40")})
        repo.save(order.id,value)
    }
    @Test fun localCrudAndPendingLock()=runBlocking {
        validDraft(); val row=db.store().result(order.id)!!
        assertEquals("DRAFT",row.lifecycle); assertTrue(db.store().operations().isEmpty())
        repo.save(order.id,wire.decodeFromString<ResultWriteDto>(row.json).copy(comment="test"))
        repo.deleteDraft(order.id); assertNull(db.store().result(order.id))
        validDraft(); repo.enqueue(order.id,LocationDto.missing("DENIED"))
        assertEquals("QUEUED",db.store().result(order.id)!!.lifecycle)
        try { repo.deleteDraft(order.id); fail() } catch(_:IllegalStateException) {}
        assertEquals(1,db.store().operations().size)
    }
    @Test fun transactionRollsBackFinalAndOutbox()=runBlocking {
        validDraft()
        try { db.withTransaction { repo.enqueue(order.id,LocationDto.missing("TIMEOUT")); error("injected rollback") } } catch(_:IllegalStateException) {}
        assertEquals("DRAFT",db.store().result(order.id)!!.lifecycle)
        assertTrue(db.store().operations().isEmpty())
    }
    @Test fun restartKeepsUuidFrozenBytesAndRecoversRunning()=runBlocking {
        validDraft(); repo.enqueue(order.id,LocationDto.missing("DENIED"))
        val op=db.store().operations().single().copy(state="RUNNING",wireBody="frozen exact bytes",attempts=3,nextAttemptAt=1234)
        db.store().operation(op); db.close()
        db=Room.databaseBuilder(context,FsmDatabase::class.java,name).build(); db.store().recover()
        val recovered=db.store().operations().single()
        assertEquals(op.id,recovered.id); assertEquals(op.wireBody,recovered.wireBody); assertEquals(op.frozenPayload,recovered.frozenPayload)
        assertEquals("RETRY_WAIT",recovered.state); assertEquals("UNKNOWN_OUTCOME",recovered.error)
        assertTrue(recovered.nextAttemptAt>System.currentTimeMillis()-1000)
    }
    @Test fun uploadBeforeCloseDependenciesAndLocalPathRetention()=runBlocking {
        validDraft(); val id=uuid(); val file=File(repo.files,"$id.jpg").apply {writeBytes(byteArrayOf(1,2,3))}
        repo.addPhoto(order.id,file,id,sha256(file.readBytes()))
        repo.enqueue(order.id,LocationDto.missing("DENIED"))
        val ops=db.store().operations(); assertEquals(listOf("UPLOAD_PHOTO","CLOSE_WORK_ORDER"),ops.map {it.kind})
        assertEquals(listOf(ops[0].id),wire.decodeFromString<List<String>>(ops[1].dependencies))
        val original=db.store().photo(id)!!
        db.store().photo(original.copy(serverPhotoId=uuid(),contentPath="relative"))
        assertEquals(file.absolutePath,db.store().photo(id)!!.localPath); assertTrue(file.exists())
        try { repo.removePhoto(order.id,id); fail() } catch(_:IllegalStateException) {}
    }
    @Test fun loggerBoundedAndWhitelisted()=runBlocking {
        val logger=SafeLogger(db); val clock=System.currentTimeMillis()
        db.withTransaction {
            db.store().log(AppLog(uuid(),clock-8*86400000L,"INFO","OLD",null,null,"old"))
            repeat(1003) { db.store().log(AppLog(uuid(),clock,"INFO","SAFE",null,null,"safe")) }
        }
        logger.record(Event.SYNC_STARTED,"not-a-uuid",at=clock)
        val rows=db.store().logs().first()
        assertEquals(1000,rows.size); assertTrue(rows.all { it.at>=clock-7*86400000L }); assertTrue(rows.all {it.operationId==null})
    }
    @Test fun orphanCleanupNeverDeletesRegisteredQueuedPhoto()=runBlocking {
        validDraft(); val id=uuid(); val registered=File(repo.files,"$id.jpg").apply {writeBytes(byteArrayOf(1,2,3))}
        repo.addPhoto(order.id,registered,id,sha256(registered.readBytes()))
        repo.enqueue(order.id,LocationDto.missing("DENIED"))
        val orphan=File(repo.files,"${uuid()}.jpg").apply {writeText("orphan")}
        val temp=File(repo.files,"${uuid()}.tmp").apply {writeText("interrupted import")}
        repo.cleanupOrphans()
        assertTrue(registered.exists()); assertFalse(orphan.exists()); assertFalse(temp.exists())
        assertEquals(registered.absolutePath,db.store().photo(id)!!.localPath)
    }
    @Test fun importCorrectsExifBeforeStripping()=runBlocking {
        validDraft()
        val source=File(context.cacheDir,"exif-test.jpg")
        val bitmap=android.graphics.Bitmap.createBitmap(12,8,android.graphics.Bitmap.Config.ARGB_8888)
        try {
            source.outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,it)}
            androidx.exifinterface.media.ExifInterface(source.path).apply {setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,"6");saveAttributes()}
            Media.importPhoto(context,repo,order.id,android.net.Uri.fromFile(source))
            val stored=db.store().photos(order.id).single()
            val options=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
            android.graphics.BitmapFactory.decodeFile(stored.localPath,options)
            assertEquals(8,options.outWidth); assertEquals(12,options.outHeight)
            assertEquals(1,androidx.exifinterface.media.ExifInterface(stored.localPath!!).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,1))
        } finally {bitmap.recycle();source.delete()}
    }

    private fun fixture():JsonObject=wire.parseToJsonElement(InstrumentationRegistry.getInstrumentation().context.assets.open("contract-fixtures.json").bufferedReader().use {it.readText()}).jsonObject
    private fun stub(handler:suspend MockRequestHandleScope.(HttpRequestData)->HttpResponseData) {
        client.close(); client=HttpClient(MockEngine(handler))
        val token=wire.decodeFromJsonElement<TokenResponse>(fixture()["auth"]!!.jsonObject.getValue("tokenResponse"))
        repo=Repository(db,Api(client,"https://example.test",null,order.assigneeId).apply {accept(token)},order.assigneeId,repo.files)
    }
    @Test fun conflictTransitionBeforeCancelledFetchSurvivesRestart()=runBlocking {
        validDraft(); repo.enqueue(order.id,LocationDto.missing("DENIED"))
        val frozen=db.store().operations().single(); val fetching=CompletableDeferred<Unit>()
        stub { fetching.complete(Unit); awaitCancellation() }
        repo.markConflict(order.id,frozen)
        val job=launch {repo.freshCanonical(order.id)}; fetching.await(); job.cancelAndJoin()
        assertNull(db.store().shadow(order.id)); assertEquals("CONFLICT",db.store().result(order.id)!!.lifecycle)
        val conflict=db.store().operations().single()
        assertEquals("CONFLICT",conflict.state); assertEquals(frozen.id,conflict.id); assertEquals(frozen.frozenPayload,conflict.frozenPayload)
        db.close(); db=Room.databaseBuilder(context,FsmDatabase::class.java,name).build()
        assertEquals("CONFLICT",db.store().result(order.id)!!.lifecycle); assertEquals(conflict,db.store().operations().single())
    }
    @Test fun failedAuthorizedResolveNeverUsesStaleShadowOrDiscardsQueue()=runBlocking {
        validDraft(); repo.enqueue(order.id,LocationDto.missing("DENIED")); repo.markConflict(order.id,db.store().operations().single())
        val local=db.store().result(order.id); val ops=db.store().operations()
        var status=HttpStatusCode.NotFound
        stub { respond("{}",status) }
        for(code in listOf(HttpStatusCode.NotFound,HttpStatusCode.Unauthorized,HttpStatusCode.ServiceUnavailable)) {
            status=code
            for(server in listOf(true,false)) {
                val shown=wire.encodeToString(order); db.store().shadow(Shadow(order.id,shown))
                try {if(server) repo.acceptServer(order.id,shown) else repo.returnToDraft(order.id,shown);fail()} catch(_:ApiFailure) {}
                assertNull(db.store().shadow(order.id)); assertEquals(local,db.store().result(order.id)); assertEquals(ops,db.store().operations())
            }
        }
    }
    @Test fun changedClosedCanonicalRequiresNewChoiceAndCannotReopenDraft()=runBlocking {
        validDraft(); repo.enqueue(order.id,LocationDto.missing("DENIED")); repo.markConflict(order.id,db.store().operations().single())
        val closed=wire.decodeFromJsonElement<SnapshotDto>(fixture().getValue("snapshotAfterClose")).workOrders.single()
        val shown=wire.encodeToString(order); val fresh=wire.encodeToString(closed)
        var gets=0; stub { gets++; respond(fresh,HttpStatusCode.OK) }
        val frozen=db.store().operations()
        try {repo.returnToDraft(order.id,shown);fail()} catch(e:CanonicalChanged) {assertEquals(fresh,e.canonical)}
        assertEquals(frozen,db.store().operations()); assertEquals("CONFLICT",db.store().result(order.id)!!.lifecycle)
        try {repo.returnToDraft(order.id,fresh);fail()} catch(_:IllegalStateException) {}
        try {repo.acceptServer(order.id,shown);fail()} catch(_:CanonicalChanged) {}
        assertEquals(frozen,db.store().operations())
        repo.acceptServer(order.id,fresh)
        assertEquals(4,gets); assertTrue(db.store().operations().isEmpty()); assertEquals("CLOSED",db.store().result(order.id)!!.lifecycle)
    }
    @Test fun atomicConflictRollsBackAndLegacyInterruptedStateRepairs()=runBlocking {
        validDraft(); repo.enqueue(order.id,LocationDto.missing("DENIED")); val frozen=db.store().operations().single()
        try { db.withTransaction {repo.markConflict(order.id,frozen);error("injected rollback")} } catch(_:IllegalStateException) {}
        assertEquals(frozen,db.store().operations().single()); assertEquals("QUEUED",db.store().result(order.id)!!.lifecycle)
        db.store().operation(frozen.copy(state="CONFLICT")); repo.recover()
        assertEquals("CONFLICT",db.store().result(order.id)!!.lifecycle); assertNull(db.store().shadow(order.id))
    }
    private suspend fun closedReceipt(op:Operation):CloseReceipt {
        val r=wire.decodeFromString<ResultWriteDto>(db.store().result(order.id)!!.json)
        return CloseReceipt(op.id,order.copy(status="CLOSED",revision=order.revision+1,result=ResultDto(r.id,r.answers,r.readings,r.comment,r.location,r.capturedAt,emptyList())))
    }
    @Test fun unknownReceiptRetainsFrozenWireAndBackoffThenSameOperationAcks()=runBlocking {
        validDraft(); repo.enqueue(order.id,LocationDto.missing("DENIED")); val op=db.store().operations().single(); val good=closedReceipt(op)
        val sent=mutableListOf<Pair<String?,String>>(); var posts=0
        stub { request ->
            if(request.method==HttpMethod.Post) { posts++; sent.add(request.headers["Idempotency-Key"] to (request.body as TextContent).text); respond(if(posts==1) "{broken" else wire.encodeToString(good),HttpStatusCode.OK) }
            else respond("{}",HttpStatusCode.ServiceUnavailable)
        }
        SyncEngine(repo).run()
        val waiting=db.store().operations().single()
        assertEquals("RETRY_WAIT",waiting.state); assertEquals("UNKNOWN_OUTCOME",waiting.error); assertTrue(waiting.nextAttemptAt>System.currentTimeMillis())
        assertEquals("QUEUED",db.store().result(order.id)!!.lifecycle)
        SyncEngine(repo).run(); assertEquals(1,posts) // No hot-loop before durable deadline.
        db.store().operation(waiting.copy(nextAttemptAt=0)); SyncEngine(repo).run()
        assertEquals("ACKED",db.store().operations().single().state); assertEquals(2,posts); assertEquals(sent[0],sent[1]); assertEquals(op.id,sent[0].first)
        assertEquals(waiting.wireBody,sent[1].second); assertEquals(op.frozenPayload,db.store().operations().single().frozenPayload)
    }
    @Test fun cancelAfterDispatchKeepsRetryableSameFrozenOperation()=runBlocking {
        validDraft(); repo.enqueue(order.id,LocationDto.missing("DENIED")); val op=db.store().operations().single()
        val accepted=CompletableDeferred<Unit>()
        stub { accepted.complete(Unit); awaitCancellation() }
        val job=launch {SyncEngine(repo).run()}; accepted.await(); job.cancelAndJoin()
        val waiting=db.store().operations().single()
        assertEquals(op.id,waiting.id); assertEquals(op.frozenPayload,waiting.frozenPayload); assertNotNull(waiting.wireBody)
        assertEquals("RETRY_WAIT",waiting.state); assertEquals("UNKNOWN_OUTCOME",waiting.error); assertTrue(waiting.nextAttemptAt>System.currentTimeMillis())
        assertEquals("QUEUED",db.store().result(order.id)!!.lifecycle)
    }
    @Test fun missingPhotoIsDefinitiveBeforeDispatchAndMakesNoMutationRequest()=runBlocking {
        validDraft(); val id=uuid(); val file=File(repo.files,"$id.jpg").apply {writeBytes(byteArrayOf(1,2,3))}
        repo.addPhoto(order.id,file,id,sha256(file.readBytes())); repo.enqueue(order.id,LocationDto.missing("DENIED")); file.delete()
        var posts=0; stub { request -> if(request.method==HttpMethod.Post) posts++; respond("{}",HttpStatusCode.ServiceUnavailable) }
        SyncEngine(repo).run()
        assertEquals(0,posts); assertTrue(db.store().operations().all {it.state=="FAILED" && it.error=="LOCAL_INTEGRITY_ERROR"})
        assertEquals("QUEUED",db.store().result(order.id)!!.lifecycle)
    }

}
