package com.fsm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
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
        // Expected output corners: top-left, top-right, bottom-left, bottom-right.
        // Independent landmarks catch skipped/double rotations and wrong mirror transforms.
        val r=Color.RED; val g=Color.GREEN; val b=Color.BLUE; val y=Color.YELLOW
        val corners=listOf(
            listOf(r,g,b,y), listOf(g,r,y,b), listOf(y,b,g,r), listOf(b,y,r,g),
            listOf(r,b,g,y), listOf(b,r,y,g), listOf(y,g,b,r), listOf(g,y,r,b)
        )
        val importedPhotoIds=mutableSetOf<String>()
        for(orientation in 1..8) {
            val source=File(context.cacheDir,"exif-test-$orientation.jpg")
            try {
                writePhotoFixture(source)
                ExifInterface(source.path).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION,orientation.toString())
                    photoMetadata.forEach { (tag,value) -> setAttribute(tag,value) }
                    setLatLong(12.25,34.5) // Synthetic coordinates, never a device location.
                    saveAttributes()
                }
                val input=ExifInterface(source.path)
                assertEquals(orientation,input.getAttributeInt(ExifInterface.TAG_ORIENTATION,-1))
                photoMetadata.forEach { (tag,value) -> assertEquals("Fixture $tag",value,input.getAttribute(tag)) }
                val gps=requireNotNull(input.latLong)
                assertEquals(12.25,gps[0],0.000001); assertEquals(34.5,gps[1],0.000001)
                assertTrue("Fixture must really contain EXIF APP1",jpegHeaderMarkers(source.readBytes()).contains(0xe1))

                Media.importPhoto(context,repo,order.id,android.net.Uri.fromFile(source))
                val stored=db.store().photos(order.id).single { it.id !in importedPhotoIds }
                importedPhotoIds.add(stored.id)
                val swapped=orientation>=5
                assertSanitizedPhoto(stored,if(swapped) 48 else 80,if(swapped) 80 else 48,corners[orientation-1])
            } finally {source.delete()}
        }
    }
    @Test fun importWithoutExifPreservesUprightPixelsAndFinalHash()=runBlocking {
        validDraft()
        val source=File(context.cacheDir,"no-exif-test.jpg")
        try {
            writePhotoFixture(source)
            assertFalse("Fixture must have no EXIF APP1",jpegHeaderMarkers(source.readBytes()).contains(0xe1))
            Media.importPhoto(context,repo,order.id,android.net.Uri.fromFile(source))
            assertSanitizedPhoto(db.store().photos(order.id).single(),80,48,listOf(Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW))
        } finally {source.delete()}
    }
    private val photoMetadata=mapOf(
        ExifInterface.TAG_MAKE to "FSM_TEST_CAMERA",
        ExifInterface.TAG_MODEL to "FSM_TEST_MODEL",
        ExifInterface.TAG_USER_COMMENT to "FSM_TEST_METADATA",
        ExifInterface.TAG_DATETIME_ORIGINAL to "2026:01:02 03:04:05"
    )
    private fun writePhotoFixture(source:File) {
        val bitmap=Bitmap.createBitmap(80,48,Bitmap.Config.ARGB_8888)
        val colors=listOf(Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW)
        try {
            for(y in 0 until bitmap.height) for(x in 0 until bitmap.width) {
                bitmap.setPixel(x,y,colors[(if(y<24) 0 else 2)+(if(x<40) 0 else 1)])
            }
            source.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it))}
        } finally {bitmap.recycle()}
    }
    private fun assertSanitizedPhoto(photo:Photo,width:Int,height:Int,corners:List<Int>) {
        val file=File(requireNotNull(photo.localPath)); val bytes=file.readBytes()
        assertEquals("Room byteSize must describe final JPEG",bytes.size.toLong(),photo.byteSize)
        assertEquals("Room hash must describe final JPEG",sha256(bytes),photo.sha256)
        assertFalse("Imported JPEG must not retain APP1 metadata",jpegHeaderMarkers(bytes).contains(0xe1))
        val exif=ExifInterface(file.path)
        // AndroidX 1.4.1 synthesizes UNDEFINED (0) when the orientation tag is absent.
        // Pixels, not a residual metadata transform, must already be upright.
        val orientation=exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_UNDEFINED)
        assertTrue("No remaining orientation transform: $orientation",orientation in listOf(ExifInterface.ORIENTATION_UNDEFINED,ExifInterface.ORIENTATION_NORMAL))
        photoMetadata.keys.forEach {tag -> assertNull("Must strip $tag",exif.getAttribute(tag))}
        assertNull("Must strip GPS latitude",exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull("Must strip GPS longitude",exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
        assertNull("No GPS fix may survive",exif.latLong)
        val bitmap=requireNotNull(BitmapFactory.decodeFile(file.path))
        try {
            assertEquals(width,bitmap.width); assertEquals(height,bitmap.height)
            val points=listOf(width/4 to height/4,3*width/4 to height/4,width/4 to 3*height/4,3*width/4 to 3*height/4)
            points.forEachIndexed { index,(x,y) ->
                val actual=bitmap.getPixel(x,y); val expected=corners[index]
                // Sample away from boundaries; allow bounded loss from two JPEG encodes.
                assertEquals("Corner $index red",Color.red(expected).toDouble(),Color.red(actual).toDouble(),50.0)
                assertEquals("Corner $index green",Color.green(expected).toDouble(),Color.green(actual).toDouble(),50.0)
                assertEquals("Corner $index blue",Color.blue(expected).toDouble(),Color.blue(actual).toDouble(),50.0)
            }
        } finally {bitmap.recycle()}
    }
    /** Walk length-delimited JPEG headers only; entropy-coded scan bytes are not markers. */
    private fun jpegHeaderMarkers(bytes:ByteArray):List<Int> {
        fun unsigned(index:Int)=bytes[index].toInt() and 0xff
        assertTrue("JPEG SOI",bytes.size>=2 && unsigned(0)==0xff && unsigned(1)==0xd8)
        val markers=mutableListOf<Int>(); var offset=2
        while(offset<bytes.size) {
            assertEquals("JPEG marker prefix",0xff,unsigned(offset++))
            while(offset<bytes.size && unsigned(offset)==0xff) offset++
            assertTrue("JPEG marker present",offset<bytes.size)
            val marker=unsigned(offset++)
            if(marker==0xda || marker==0xd9) return markers
            assertTrue("JPEG segment length present",offset+2<=bytes.size)
            val length=(unsigned(offset) shl 8) or unsigned(offset+1)
            assertTrue("JPEG segment bounds",length>=2 && length<=bytes.size-offset)
            markers.add(marker); offset+=length
        }
        fail("JPEG missing scan/end marker")
        return markers
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
