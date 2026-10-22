package fsm

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.test.*

@Tag("postgres")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class UploadReliabilityPostgresTest {
    private lateinit var db: Database
    private lateinit var auth: Auth
    private lateinit var storage: PhotoStorage
    private lateinit var ops: Operations
    private lateinit var schema: String
    private lateinit var databaseUrl: String
    private lateinit var databaseUser: String
    private lateinit var databasePassword: String
    private lateinit var accessA: String
    private lateinit var accessB: String
    private val employeeA = demoId(1)
    private val employeeB = demoId(101)
    private val orderA = demoId(4)
    private val orderB = demoId(104)

    @BeforeEach fun setup() {
        databaseUrl = System.getenv("TEST_DATABASE_URL") ?: error("TEST_DATABASE_URL required; no silent skip")
        databaseUser = System.getenv("TEST_DATABASE_USER") ?: error("TEST_DATABASE_USER required")
        databasePassword = System.getenv("TEST_DATABASE_PASSWORD") ?: ""
        schema = "upload_reliability_" + newId().replace("-", "")
        DriverManager.getConnection(databaseUrl, databaseUser, databasePassword).use { c ->
            c.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
        db = Database(databaseUrl, databaseUser, databasePassword, schema)
        storage = PhotoStorage(Files.createTempDirectory("fsm-upload-reliability-"))
        val password = newId() + "Aa!"
        seed(db, listOf(SeedAccount("reliability.a", password), SeedAccount("reliability.b", password)))
        auth = Auth(db, ByteArray(32).also(java.security.SecureRandom()::nextBytes))
        ops = Operations(db, storage)
        accessA = auth.login(LoginRequest("reliability.a", password), "test-a").accessToken
        accessB = auth.login(LoginRequest("reliability.b", password), "test-b").accessToken
    }

    @AfterEach fun cleanup() {
        if (::db.isInitialized) db.close()
        // Only the unique schema created by this test; never the database/cluster.
        if (::schema.isInitialized) {
            DriverManager.getConnection(databaseUrl, databaseUser, databasePassword).use { c ->
                c.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
        if (::storage.isInitialized) storage.root.toFile().deleteRecursively()
    }

    private data class Reply(val status: Int, val body: String)

    private suspend fun HttpClient.upload(
        token: String,
        order: String,
        operation: String,
        multipart: SerializedMultipart
    ): Reply {
        val response = post("/api/v1/work-orders/$order/photos") {
            bearerAuth(token)
            header("Idempotency-Key", operation)
            contentType(ContentType.parse(multipart.contentType))
            setBody(multipart.bytes)
        }
        uuid(response.headers["X-Request-Id"] ?: error("Missing request ID"))
        return Reply(response.status.value, response.bodyAsText())
    }

    // Both requests are ready before either enters the HTTP call. No sleep-based ordering.
    private suspend fun concurrentUploads(
        client: HttpClient,
        operations: List<String>,
        multipart: SerializedMultipart
    ): List<Reply> = withTimeout(30_000) {
        val barrier = CyclicBarrier(operations.size)
        coroutineScope {
            operations.map { operation ->
                async(Dispatchers.IO) {
                    barrier.await(10, TimeUnit.SECONDS)
                    client.upload(accessA, orderA, operation, multipart)
                }
            }.awaitAll()
        }
    }

    private fun counts(): List<Int> = db.tx { c ->
        listOf("inspection_results", "photos", "idempotency_receipts").map { table ->
            c.query("SELECT count(*) FROM $table") { it.getInt(1) }.single()
        }
    }

    private fun storedReceipt(employee: String, operation: String): Reply = db.tx { c ->
        c.query("SELECT status,body FROM idempotency_receipts WHERE employee_id=? AND operation_id=?",
            id(employee), id(operation)) { Reply(it.getInt("status"), it.getString("body")) }.single()
    }

    private fun assertOnlyBlob(upload: Upload) {
        assertEquals(listOf(storage.key(upload.metadata)),
            Files.list(storage.root).use { paths -> paths.map { it.fileName.toString() }.sorted().toList() })
        assertContentEquals(upload.bytes, Files.readAllBytes(storage.path(storage.key(upload.metadata))))
    }

    private suspend fun HttpClient.assertPrivatePhoto(token: String, photo: Photo, expected: ByteArray) {
        val response = get(photo.contentPath) { bearerAuth(token) }
        assertEquals(200, response.status.value)
        assertEquals("image/jpeg", response.headers[HttpHeaders.ContentType])
        assertEquals(expected.size.toString(), response.headers[HttpHeaders.ContentLength])
        assertEquals("\"${sha(expected)}\"", response.headers[HttpHeaders.ETag])
        assertContentEquals(expected, response.bodyAsBytes())
    }

    @Test fun `concurrent same upload key and body returns one photo and exact receipt`(): Unit = testApplication {
        application { fsm(db, auth, ops) }
        startApplication()
        val upload = fixtureUpload()
        val multipart = realKtorMultipart(upload)
        val key = newId()
        val replies = concurrentUploads(client, listOf(key, key), multipart)
        assertEquals(listOf(201, 201), replies.map { it.status })
        assertEquals(replies[0], replies[1])
        val receipt = decode<UploadReceipt>(replies[0].body.toByteArray())
        assertEquals(key, receipt.operationId)
        assertEquals(upload.metadata.photoId, receipt.photo.id)
        assertEquals(listOf(0, 1, 1), counts())
        assertEquals(replies[0], storedReceipt(employeeA, key))
        assertEquals(replies[0], client.upload(accessA, orderA, key, multipart))
        db.tx { c ->
            val order = c.order(employeeA, orderA)
            assertEquals(OrderStatus.ASSIGNED, order.status)
            assertEquals(1L, order.revision)
            assertNull(order.result)
        }
        assertOnlyBlob(upload)
        client.assertPrivatePhoto(accessA, receipt.photo, upload.bytes)
    }

    @Test fun `concurrent different upload keys for same photo share server ref and retain both receipts`(): Unit = testApplication {
        application { fsm(db, auth, ops) }
        startApplication()
        val upload = fixtureUpload()
        val multipart = realKtorMultipart(upload)
        val keys = listOf(newId(), newId())
        val replies = concurrentUploads(client, keys, multipart)
        assertEquals(listOf(201, 201), replies.map { it.status })
        val receipts = replies.map { decode<UploadReceipt>(it.body.toByteArray()) }
        assertEquals(keys, receipts.map { it.operationId })
        assertEquals(receipts[0].photo, receipts[1].photo)
        assertEquals(upload.metadata.photoId, receipts[0].photo.id)
        assertEquals(listOf(0, 1, 2), counts())
        keys.forEachIndexed { index, key ->
            assertEquals(replies[index], storedReceipt(employeeA, key))
            assertEquals(replies[index], client.upload(accessA, orderA, key, multipart))
        }
        assertOnlyBlob(upload)
        client.assertPrivatePhoto(accessA, receipts[0].photo, upload.bytes)
    }

    private suspend fun assertNotFound(response: HttpResponse, forbiddenIds: List<String>) {
        assertEquals(404, response.status.value)
        val text = response.bodyAsText()
        val root = wire.parseToJsonElement(text).jsonObject
        assertEquals(setOf("error", "requestId"), root.keys)
        uuid(root.getValue("requestId").jsonPrimitive.content)
        val error = root.getValue("error").jsonObject
        assertEquals("NOT_FOUND", error.getValue("code").jsonPrimitive.content)
        assertEquals(false, error.getValue("retryable").jsonPrimitive.boolean)
        assertEquals(buildJsonObject {}, error.getValue("details"))
        assertEquals(setOf("code", "message", "retryable", "details"), error.keys)
        forbiddenIds.forEach { assertFalse(text.contains(it), "404 must not expose resource IDs") }
        assertFalse(text.contains("serverPhotoId"))
        assertFalse(text.contains("contentPath"))
    }

    @Test fun `own historical upload and close receipts and photos are inaccessible after reassignment`(): Unit = testApplication {
        application { fsm(db, auth, ops) }
        startApplication()
        val closedUpload = fixtureUpload()
        val stagedUpload = closedUpload.copy(metadata = closedUpload.metadata.copy(photoId = newId()))
        val closedMultipart = realKtorMultipart(closedUpload)
        val stagedMultipart = realKtorMultipart(stagedUpload)
        val uploadKey = newId()
        val stagedKey = newId()
        val closeKey = newId()
        val stagedOrder = demoId(14)
        val acceptedUpload = client.upload(accessA, orderA, uploadKey, closedMultipart)
        val acceptedStaged = client.upload(accessA, stagedOrder, stagedKey, stagedMultipart)
        assertEquals(201, acceptedUpload.status)
        assertEquals(201, acceptedStaged.status)
        val attached = decode<UploadReceipt>(acceptedUpload.body.toByteArray()).photo
        val staged = decode<UploadReceipt>(acceptedStaged.body.toByteArray()).photo
        val request = decode<CloseRequest>(fixtureBytes("closeNoPhotoRequest"))
        val closeBytes = encode(request.copy(result = request.result.copy(photoIds = listOf(attached.serverPhotoId))))
        val closePath = "/api/v1/work-orders/$orderA/close"
        val acceptedClose = client.post(closePath) {
            bearerAuth(accessA); header("Idempotency-Key", closeKey)
            contentType(ContentType.Application.Json); setBody(closeBytes)
        }
        assertEquals(200, acceptedClose.status.value)
        val closeReply = Reply(200, acceptedClose.bodyAsText())
        client.assertPrivatePhoto(accessA, attached, closedUpload.bytes)
        client.assertPrivatePhoto(accessA, staged, stagedUpload.bytes)
        assertEquals(listOf(1, 2, 3), counts())
        // Fixture-only reassignment, not a new application endpoint or production feature.
        db.tx { c ->
            assertEquals(2, c.exec("UPDATE work_orders SET assignee_id=?,revision=revision+1 WHERE id IN (?,?) AND assignee_id=?",
                id(employeeB), id(orderA), id(stagedOrder), id(employeeA)))
        }
        val canonicalAfterFixture = db.tx(true) { c ->
            listOf(c.order(employeeB, orderA), c.order(employeeB, stagedOrder))
        }
        // A's session is still valid; these failures must be scope 404, not auth 401.
        assertEquals(200, client.get("/api/v1/reference-data") { bearerAuth(accessA) }.status.value)
        val forbidden = listOf(attached.id, attached.serverPhotoId, staged.id, staged.serverPhotoId, request.result.id)
        for ((order, key, multipart) in listOf(
            Triple(orderA, uploadKey, closedMultipart), Triple(stagedOrder, stagedKey, stagedMultipart))) {
            val response = client.post("/api/v1/work-orders/$order/photos") {
                bearerAuth(accessA); header("Idempotency-Key", key)
                contentType(ContentType.parse(multipart.contentType)); setBody(multipart.bytes)
            }
            assertNotFound(response, forbidden)
            assertNotFound(client.get("/api/v1/work-orders/$order") { bearerAuth(accessA) }, forbidden)
        }
        assertNotFound(client.post(closePath) {
            bearerAuth(accessA); header("Idempotency-Key", closeKey)
            contentType(ContentType.Application.Json); setBody(closeBytes)
        }, forbidden)
        for (photo in listOf(attached, staged)) {
            assertNotFound(client.get(photo.contentPath) { bearerAuth(accessA) }, forbidden)
        }
        assertEquals(listOf(1, 2, 3), counts())
        assertEquals(acceptedUpload, storedReceipt(employeeA, uploadKey))
        assertEquals(acceptedStaged, storedReceipt(employeeA, stagedKey))
        assertEquals(closeReply, storedReceipt(employeeA, closeKey))
        assertEquals(canonicalAfterFixture, db.tx(true) { c ->
            listOf(c.order(employeeB, orderA), c.order(employeeB, stagedOrder))
        })
        assertEquals(2L, Files.list(storage.root).use { it.count() })
        for (upload in listOf(closedUpload, stagedUpload)) {
            assertContentEquals(upload.bytes, Files.readAllBytes(storage.path(storage.key(upload.metadata))))
        }
    }

    @Test fun `same operation UUID is independently scoped to each employee`(): Unit = testApplication {
        application { fsm(db, auth, ops) }
        startApplication()
        val uploadA = fixtureUpload()
        val uploadB = uploadA.copy(metadata = uploadA.metadata.copy(photoId = newId()))
        val multipartA = realKtorMultipart(uploadA)
        val multipartB = realKtorMultipart(uploadB)
        val key = newId()
        val replyA = client.upload(accessA, orderA, key, multipartA)
        val replyB = client.upload(accessB, orderB, key, multipartB)
        assertEquals(201, replyA.status)
        assertEquals(201, replyB.status)
        val receiptA = decode<UploadReceipt>(replyA.body.toByteArray())
        val receiptB = decode<UploadReceipt>(replyB.body.toByteArray())
        assertEquals(key, receiptA.operationId)
        assertEquals(key, receiptB.operationId)
        assertEquals(uploadA.metadata.photoId, receiptA.photo.id)
        assertEquals(uploadB.metadata.photoId, receiptB.photo.id)
        assertNotEquals(receiptA.photo.serverPhotoId, receiptB.photo.serverPhotoId)
        assertEquals(replyA, storedReceipt(employeeA, key))
        assertEquals(replyB, storedReceipt(employeeB, key))
        assertEquals(replyA, client.upload(accessA, orderA, key, multipartA))
        assertEquals(replyB, client.upload(accessB, orderB, key, multipartB))
        assertEquals(listOf(0, 2, 2), counts())
        client.assertPrivatePhoto(accessA, receiptA.photo, uploadA.bytes)
        client.assertPrivatePhoto(accessB, receiptB.photo, uploadB.bytes)
        assertNotFound(client.get(receiptB.photo.contentPath) { bearerAuth(accessA) }, listOf(receiptB.photo.serverPhotoId))
        assertNotFound(client.get(receiptA.photo.contentPath) { bearerAuth(accessB) }, listOf(receiptA.photo.serverPhotoId))
        assertEquals(2L, Files.list(storage.root).use { it.count() })
        for (upload in listOf(uploadA, uploadB)) {
            assertContentEquals(upload.bytes, Files.readAllBytes(storage.path(storage.key(upload.metadata))))
        }
    }
}
