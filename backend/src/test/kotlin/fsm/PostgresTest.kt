package fsm

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Test
import kotlin.test.*
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.CyclicBarrier

@Tag("postgres")
class PostgresTest {
    private lateinit var db: Database
    private lateinit var auth: Auth
    private lateinit var storage: PhotoStorage
    private lateinit var ops: Operations
    private lateinit var schema: String
    private val password=newId()+"Aa!"
    private val jwtSecret=ByteArray(32).also(java.security.SecureRandom()::nextBytes)
    private val a=demoId(1); private val order=demoId(4)
    private val url=System.getenv("TEST_DATABASE_URL") ?: error("TEST_DATABASE_URL required; tests never silently skip")
    private val user=System.getenv("TEST_DATABASE_USER") ?: error("TEST_DATABASE_USER required")
    private val dbPassword=System.getenv("TEST_DATABASE_PASSWORD") ?: ""
    @BeforeEach fun setup() {
        schema="t_"+newId().replace("-","")
        DriverManager.getConnection(url,user,dbPassword).use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        db=Database(url,user,dbPassword,schema)
        seed(db,listOf(SeedAccount("engineer.one",password),SeedAccount("engineer.two",password)))
        auth=Auth(db,jwtSecret)
        storage=PhotoStorage(Files.createTempDirectory("fsm-photo-test-")); ops=Operations(db,storage)
    }
    @AfterEach fun cleanup() {
        if(::db.isInitialized) db.close()
        if(::schema.isInitialized) DriverManager.getConnection(url,user,dbPassword).use { c -> c.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
        if(::storage.isInitialized) storage.root.toFile().deleteRecursively()
    }
    private fun closeBody(photos: List<String> = emptyList(), resultId: String=demoId(6)): ByteArray {
        val r=decode<CloseRequest>(fixtureBytes("closeNoPhotoRequest"))
        return encode(r.copy(result=r.result.copy(id=resultId,photoIds=photos))).toByteArray()
    }
    private fun counts(): List<Int> = db.tx { c -> listOf("inspection_results","photos","idempotency_receipts").map { t -> c.query("SELECT count(*) FROM $t") { it.getInt(1) }.single() } }
    private fun assertCode(code: String, block: () -> Unit) { assertEquals(code,assertFailsWith<ApiError>(block=block).code) }
    @Test fun `auth uniform failure refresh no rotation logout revokes sid and scope`() = testApplication {
        application { fsm(db,auth,ops) }
        assertCode("INVALID_CREDENTIALS") { auth.login(LoginRequest("engineer.one","wrong"),"a") }
        assertCode("INVALID_CREDENTIALS") { auth.login(LoginRequest("unknown","wrong"),"b") }
        val token=auth.login(LoginRequest("ENGINEER.ONE",password),"c")
        assertEquals(token.refreshToken,auth.refresh(RefreshRequest(token.refreshToken)).refreshToken)
        assertEquals(a,auth.authenticate("Bearer ${token.accessToken}"))
        val decoded=com.auth0.jwt.JWT.decode(token.accessToken)
        val expired=com.auth0.jwt.JWT.create().withIssuer("fsm-api").withAudience("fsm-android").withSubject(a)
            .withClaim("sid",decoded.getClaim("sid").asString())
            .withIssuedAt(java.util.Date.from(java.time.Instant.now().minusSeconds(1800)))
            .withExpiresAt(java.util.Date.from(java.time.Instant.now().minusSeconds(900)))
            .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256(jwtSecret))
        assertCode("AUTH_REQUIRED") { auth.authenticate("Bearer $expired") }
        assertEquals(404,client.get("/api/v1/work-orders/${demoId(104)}") { bearerAuth(token.accessToken) }.status.value)
        assertEquals(400,client.get("/api/v1/work-orders?limit=1") { bearerAuth(token.accessToken) }.status.value)
        val unauth=client.get("/api/v1/work-orders")
        assertEquals(401,unauth.status.value); uuid(unauth.headers["X-Request-Id"]!!)
        auth.logout(RefreshRequest(token.refreshToken)); auth.logout(RefreshRequest(token.refreshToken)); auth.logout(RefreshRequest(newId()))
        assertCode("AUTH_REQUIRED") { auth.authenticate("Bearer ${token.accessToken}") }
        assertCode("SESSION_EXPIRED") { auth.refresh(RefreshRequest(token.refreshToken)) }
    }
    @Test fun `close replay after closed changed bytes and cross route reuse rejected`() {
        val bytes=closeBody(); val key=newId()
        val first=ops.close(a,order,key,bytes); assertEquals(200,first.status)
        assertEquals(first,ops.close(a,order,key,bytes))
        assertCode("IDEMPOTENCY_KEY_REUSED") { ops.close(a,order,key,bytes+byteArrayOf(32)) }
        assertCode("IDEMPOTENCY_KEY_REUSED") { ops.upload(a,order,key,fixtureUpload()) }
        assertCode("ORDER_CLOSED") { ops.close(a,order,newId(),bytes) }
        assertEquals(listOf(1,0,1),counts())
        val canonical=db.tx { it.order(a,order) }; assertEquals(2L,canonical.revision); assertNotNull(canonical.result)
        assertCode("NOT_FOUND") { ops.close(demoId(101),order,key,bytes) }
    }
    @Test fun `concurrent same keys one effect and identical receipt`() {
        val key=newId(); val bytes=closeBody()
        val pool=Executors.newFixedThreadPool(4); val barrier=CyclicBarrier(4)
        try {
            val responses=pool.invokeAll((1..4).map { Callable { barrier.await(); ops.close(a,order,key,bytes) } }).map { it.get() }
            assertEquals(1,responses.distinct().size); assertEquals(listOf(1,0,1),counts())
        } finally { pool.shutdownNow() }
    }
    @Test fun `different concurrent keys one success one order closed`() {
        val pool=Executors.newFixedThreadPool(2); val barrier=CyclicBarrier(2)
        try {
            val responses=pool.invokeAll((1..2).map { Callable { barrier.await(); try { ops.close(a,order,newId(),closeBody()); "OK" } catch(e: ApiError) { e.code } } }).map { it.get() }
            assertEquals(setOf("OK","ORDER_CLOSED"),responses.toSet()); assertEquals(listOf(1,0,1),counts())
        } finally { pool.shutdownNow() }
    }
    @Test fun `validation revision result reuse and SQL rollback are atomic`() {
        val r=decode<CloseRequest>(closeBody())
        assertCode("REVISION_CONFLICT") { ops.close(a,order,newId(),encode(r.copy(baseRevision=2)).toByteArray()) }
        assertCode("VALIDATION_ERROR") { ops.close(a,order,newId(),encode(r.copy(result=r.result.copy(answers=emptyList()))).toByteArray()) }
        assertCode("PHOTO_MISSING") { ops.close(a,order,newId(),closeBody(listOf(newId()))) }
        val fail=Operations(db,storage,beforeReceipt={ throw IllegalStateException("injected") })
        assertFailsWith<IllegalStateException> { fail.close(a,order,newId(),closeBody()) }
        assertEquals(listOf(0,0,0),counts()); assertEquals(OrderStatus.ASSIGNED,db.tx { it.order(a,order) }.status)
        ops.close(a,order,newId(),closeBody())
        val body=r.copy(result=r.result.copy(answers=listOf(Answer(demoId(15),true))))
        assertCode("RESULT_ID_REUSED") { ops.close(a,demoId(14),newId(),encode(body).toByteArray()) }
    }
    @Test fun `upload dedup private binary staged hidden close attach and restart replay`() = testApplication {
        application { fsm(db,auth,ops) }
        val upload=fixtureUpload(); val key=newId(); val first=ops.upload(a,order,key,upload)
        val photo=decode<UploadReceipt>(first.body.toByteArray()).photo
        assertEquals(photo,decode<UploadReceipt>(ops.upload(a,order,newId(),upload).body.toByteArray()).photo)
        assertEquals(1,counts()[1]); assertNull(db.tx { it.order(a,order) }.result)
        val token=auth.login(LoginRequest("engineer.one",password),"test").accessToken
        val response=client.get(photo.contentPath) { bearerAuth(token); header("Range","bytes=0-1"); header("If-None-Match","\"${photo.sha256}\"") }
        assertEquals(200,response.status.value); assertEquals("image/jpeg",response.headers[HttpHeaders.ContentType]); assertEquals("631",response.headers[HttpHeaders.ContentLength]); assertEquals("\"${photo.sha256}\"",response.headers[HttpHeaders.ETag])
        assertContentEquals(upload.bytes,response.bodyAsBytes())
        val foreign=auth.login(LoginRequest("engineer.two",password),"test2").accessToken
        assertEquals(404,client.get(photo.contentPath) { bearerAuth(foreign) }.status.value)
        assertCode("PHOTO_ID_REUSED") { ops.upload(a,demoId(14),newId(),upload) }
        val fail=Operations(db,storage,beforeReceipt={ throw IllegalStateException("injected") })
        assertFailsWith<IllegalStateException> { fail.close(a,order,newId(),closeBody(listOf(photo.serverPhotoId))) }
        assertNull(db.tx { it.order(a,order) }.result)
        assertTrue(db.tx { it.query("SELECT result_id FROM photos WHERE id=?",id(photo.id)) { r -> r.getObject(1)==null }.single() })
        val closed=ops.close(a,order,newId(),closeBody(listOf(photo.serverPhotoId)))
        assertEquals(1,decode<CloseReceipt>(closed.body.toByteArray()).workOrder.result!!.photos.size)
        assertEquals(first,ops.upload(a,order,key,upload))
        assertCode("ORDER_CLOSED") { ops.upload(a,order,newId(),upload) }
        assertEquals(first,Operations(db,PhotoStorage(storage.root)).upload(a,order,key,upload))
    }
    @Test fun `rename before commit orphan reused without duplicate blob or row`() {
        val upload=fixtureUpload(); val key=newId()
        val fail=Operations(db,storage,afterRename={ throw IllegalStateException("injected") })
        assertFailsWith<IllegalStateException> { fail.upload(a,order,key,upload) }
        assertEquals(listOf(0,0,0),counts()); assertEquals(1L,Files.list(storage.root).use { it.count() })
        val accepted=ops.upload(a,order,key,upload)
        assertEquals(accepted,ops.upload(a,order,key,upload))
        assertEquals(1L,Files.list(storage.root).use { it.count() }); assertEquals(listOf(0,1,1),counts())
    }
    @Test fun `repeatable read coherent during concurrent commit and scopes references`() {
        val executor=Executors.newSingleThreadExecutor()
        try {
            db.tx(true) { c ->
                assertEquals(OrderStatus.ASSIGNED,c.order(a,order).status)
                executor.submit { ops.close(a,order,newId(),closeBody()) }.get()
                val snapshot=c.order(a,order)
                assertEquals(OrderStatus.ASSIGNED,snapshot.status); assertNull(snapshot.result)
                assertEquals(listOf(demoId(3)),c.reference(a).equipment.map { it.id })
            }
            val after=db.tx(true) { it.order(a,order) }; assertEquals(OrderStatus.CLOSED,after.status); assertNotNull(after.result)
        } finally { executor.shutdownNow() }
    }
    @Test fun `concurrent conflicting bodies same key never overwrite first receipt`() {
        val key=newId(); val pool=Executors.newFixedThreadPool(2); val barrier=CyclicBarrier(2)
        val bodies=listOf(closeBody(),closeBody()+byteArrayOf(32))
        try {
            val outcomes=pool.invokeAll(bodies.map { bytes -> Callable { barrier.await(); try { ops.close(a,order,key,bytes); "OK" } catch(e: ApiError) { e.code } } }).map { it.get() }
            assertEquals(setOf("OK","IDEMPOTENCY_KEY_REUSED"),outcomes.toSet())
            assertEquals(listOf(1,0,1),counts())
        } finally { pool.shutdownNow() }
    }

}
