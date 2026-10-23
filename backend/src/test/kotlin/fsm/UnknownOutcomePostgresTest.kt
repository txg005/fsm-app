package fsm

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLClassLoader
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*

private data class ProofReply(val status: Int, val body: ByteArray)
private data class FrozenProofRequest(val path: String, val type: String, val key: String, val body: ByteArray, val access: String) {
    fun wire(): ByteArray = ("POST $path HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
        "Authorization: Bearer $access\r\nIdempotency-Key: $key\r\nContent-Type: $type\r\n" +
        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII) + body
}

private fun sendProofRequest(client: HttpClient, port: Int, request: FrozenProofRequest): ProofReply {
    val response = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port${request.path}"))
        .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer ${request.access}")
        .header("Idempotency-Key", request.key).header("Content-Type", request.type)
        .POST(HttpRequest.BodyPublishers.ofByteArray(request.body)).build(), HttpResponse.BodyHandlers.ofByteArray())
    uuid(response.headers().firstValue("X-Request-Id").orElseThrow())
    return ProofReply(response.statusCode(), response.body())
}

private fun assertProofReply(expected: ProofReply, actual: ProofReply) {
    assertEquals(expected.status, actual.status)
    assertTrue(expected.body.contentEquals(actual.body), "Exact receipt/replay bytes differ; body omitted")
}

/** One request only. Consumes the committed upstream reply, then closes downstream without writing. */
private class DropCommittedResponse(
    client: HttpClient,
    upstreamPort: Int,
    frozen: FrozenProofRequest,
    verifyCommitted: (ProofReply) -> Unit
) : AutoCloseable {
    private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 25_000 }
    val port: Int get() = listener.localPort
    val observed = CompletableFuture<ProofReply>()
    private val worker = thread(name = "proof-drop-response", isDaemon = true) {
        try {
            listener.accept().use { downstream ->
                downstream.soTimeout = 25_000
                val expected = frozen.wire()
                val received = downstream.getInputStream().readNBytes(expected.size)
                check(expected.contentEquals(received)) { "Proxy did not receive the exact frozen request; values omitted" }
                val reply = sendProofRequest(client, upstreamPort, frozen)
                // A separate DB transaction must see the receipt and business effect BEFORE EOF delivery.
                verifyCommitted(reply)
                observed.complete(reply)
                // Intentionally no downstream.getOutputStream(): zero status/header/body bytes delivered.
            }
        } catch (t: Throwable) { observed.completeExceptionally(t) }
    }
    override fun close() {
        listener.close()
        worker.join(30_000)
        check(!worker.isAlive) { "Proof proxy worker did not stop" }
    }
}

private class ProofNettyChild(environment: Map<String, String>) : AutoCloseable {
    private val ready = CompletableFuture<Int>()
    private val process: Process
    private val reader: Thread
    val port: Int
    val pid: Long get() = process.pid()
    init {
        // Gradle's test runtime loader carries the full test classpath, not just gradle-worker.jar.
        val entries = linkedSetOf<String>()
        var loader: ClassLoader? = UnknownOutcomePostgresTest::class.java.classLoader
        while (loader != null) {
            if (loader is URLClassLoader) loader.getURLs().filter { it.protocol == "file" }.forEach {
                entries.add(Path.of(it.toURI()).toString())
            }
            loader = loader.parent
        }
        entries.addAll(System.getProperty("java.class.path").split(File.pathSeparator).filter { it.isNotBlank() })
        process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx256m", "-XX:MaxMetaspaceSize=192m", "-XX:ActiveProcessorCount=2",
            "-cp", entries.joinToString(File.pathSeparator), UnknownOutcomeNettyProcess::class.java.name)
            .redirectErrorStream(true).apply {
                environment().clear()
                environment().putAll(environment)
            }.start()
        reader = thread(name = "proof-netty-output", isDaemon = true) {
            try {
                process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                    if (line.startsWith("PROOF_READY ")) ready.complete(line.removePrefix("PROOF_READY ").toInt())
                    // Consume, but do not log arbitrary server output or configuration values.
                } }
            } finally {
                if (!ready.isDone) ready.completeExceptionally(IllegalStateException("Proof Netty child exited before readiness; details omitted"))
            }
        }
        try { port = ready.get(30, TimeUnit.SECONDS) } catch (t: Throwable) { close(); throw t }
        check(port in 1..65535)
    }
    override fun close() {
        process.destroy()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            check(process.waitFor(5, TimeUnit.SECONDS)) { "Proof child did not stop" }
        }
        reader.join(5000)
        check(!reader.isAlive) { "Proof child output reader did not stop" }
    }
}

@Tag("postgres")
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class UnknownOutcomePostgresTest {
    private lateinit var db: Database
    private lateinit var storage: PhotoStorage
    private lateinit var schema: String
    private lateinit var url: String
    private lateinit var user: String
    private lateinit var dbPassword: String
    private lateinit var access: String
    private lateinit var childEnvironment: Map<String, String>
    private var child: ProofNettyChild? = null
    private val employee = demoId(1)
    private val order = demoId(4)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1).build()

    @BeforeEach fun setup() {
        url = System.getenv("TEST_DATABASE_URL") ?: error("TEST_DATABASE_URL required; no silent skip")
        user = System.getenv("TEST_DATABASE_USER") ?: error("TEST_DATABASE_USER required")
        dbPassword = System.getenv("TEST_DATABASE_PASSWORD") ?: ""
        schema = "outcome_" + newId().replace("-", "")
        DriverManager.getConnection(url, user, dbPassword).use { c ->
            c.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
        db = Database(url, user, dbPassword, schema)
        storage = PhotoStorage(Files.createTempDirectory("fsm-unknown-outcome-"))
        val password = newId() + "Aa!"
        seed(db, listOf(SeedAccount("outcome.one", password), SeedAccount("outcome.two", password)))
        val secret = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        access = Auth(db, secret).login(LoginRequest("outcome.one", password), "proof-login").accessToken
        childEnvironment = mapOf("PROOF_DATABASE_URL" to url, "PROOF_DATABASE_USER" to user,
            "PROOF_DATABASE_PASSWORD" to dbPassword, "PROOF_SCHEMA" to schema,
            "PROOF_STORAGE" to storage.root.toString(), "PROOF_JWT_SECRET" to Base64.getEncoder().encodeToString(secret))
        secret.fill(0)
        child = ProofNettyChild(childEnvironment)
    }

    @AfterEach fun cleanup() {
        // Stop only our child before dropping only our disposable schema/files.
        child?.close()
        child = null
        if (::db.isInitialized) db.close()
        if (::schema.isInitialized) DriverManager.getConnection(url, user, dbPassword).use { c ->
            c.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
        }
        if (::storage.isInitialized) storage.root.toFile().deleteRecursively()
    }

    private fun restartChild() {
        val previous = child ?: error("Missing proof child")
        val previousPid = previous.pid
        previous.close()
        child = null
        child = ProofNettyChild(childEnvironment)
        assertNotEquals(previousPid, child!!.pid, "Expected a newly launched Netty JVM")
    }

    private fun assertReceipt(request: FrozenProofRequest, expectedHash: String, reply: ProofReply) {
        val stored = db.tx { c ->
            c.query("SELECT * FROM idempotency_receipts WHERE employee_id=? AND operation_id=?", id(employee), id(request.key)) { r ->
                assertEquals("POST", r.getString("method"))
                assertEquals(request.path, r.getString("path"))
                assertEquals(expectedHash, r.getString("semantic_hash"))
                ProofReply(r.getInt("status"), r.getString("body").toByteArray(Charsets.UTF_8))
            }.single()
        }
        assertProofReply(stored, reply)
    }

    private fun assertState(upload: Upload, photo: Photo, closed: Boolean) {
        db.tx(true) { c ->
            val counts = listOf("inspection_results", "photos", "idempotency_receipts").map { table ->
                c.query("SELECT count(*) FROM $table") { it.getInt(1) }.single()
            }
            assertEquals(if (closed) listOf(1, 1, 2) else listOf(0, 1, 1), counts)
            val canonical = c.order(employee, order)
            assertEquals(if (closed) OrderStatus.CLOSED else OrderStatus.ASSIGNED, canonical.status)
            assertEquals(if (closed) 2L else 1L, canonical.revision)
            if (closed) assertEquals(listOf(photo), canonical.result!!.photos) else assertNull(canonical.result)
            c.query("SELECT * FROM photos WHERE id=?", id(upload.metadata.photoId)) { r ->
                assertEquals(photo.serverPhotoId, r.getString("server_photo_id"))
                assertEquals(employee, r.getString("owner_id"))
                assertEquals(order, r.getString("work_order_id"))
                assertEquals(if (closed) "ATTACHED" else "STAGED", r.getString("state"))
                assertEquals(canonical.result?.id, r.getString("result_id"))
                assertEquals(if (closed) 0 else null, r.getObject("position"))
                assertEquals(storage.key(upload.metadata), r.getString("storage_key"))
            }.single()
        }
        assertEquals(listOf(storage.key(upload.metadata)), Files.list(storage.root).use { paths ->
            paths.map { it.fileName.toString() }.toList()
        })
        assertTrue(upload.bytes.contentEquals(Files.readAllBytes(storage.path(storage.key(upload.metadata)))), "Stored JPEG differs; bytes omitted")
    }

    private fun loseCommittedResponse(request: FrozenProofRequest, verify: (ProofReply) -> Unit): ProofReply {
        DropCommittedResponse(http, child!!.port, request, verify).use { proxy ->
            val delivered = Socket("127.0.0.1", proxy.port).use { socket ->
                socket.soTimeout = 30_000
                socket.getOutputStream().write(request.wire())
                socket.getOutputStream().flush()
                socket.getInputStream().readBytes()
            }
            assertEquals(0, delivered.size, "Client received usable response bytes instead of EOF")
            return proxy.observed.get(30, TimeUnit.SECONDS)
        }
    }

    private fun assertPrivateBinary(photo: Photo, expected: ByteArray) {
        val response = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${child!!.port}${photo.contentPath}"))
            .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer $access").GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        assertEquals(200, response.statusCode())
        assertEquals("image/jpeg", response.headers().firstValue("Content-Type").orElseThrow())
        assertEquals(expected.size.toString(), response.headers().firstValue("Content-Length").orElseThrow())
        assertEquals("\"${sha(expected)}\"", response.headers().firstValue("ETag").orElseThrow())
        assertTrue(expected.contentEquals(response.body()), "Private JPEG differs; bytes omitted")
    }

    @Test fun `lost committed upload and close replies replay unchanged after real Netty JVM restarts`(): Unit = runBlocking {
        val upload = fixtureUpload()
        val multipart = realKtorMultipart(upload)
        val frozenUpload = FrozenProofRequest("/api/v1/work-orders/$order/photos", multipart.contentType, newId(), multipart.bytes, access)
        val acceptedUpload = loseCommittedResponse(frozenUpload) { reply ->
            assertEquals(201, reply.status)
            assertReceipt(frozenUpload, uploadHash(frozenUpload.path, upload.metadata), reply)
            val photo = decode<UploadReceipt>(reply.body).photo
            assertEquals(upload.metadata.photoId, photo.id)
            assertState(upload, photo, closed = false)
        }
        // The client got EOF, while only the proxy/test observer saw the original successful reply.
        restartChild()
        val replayedUpload = sendProofRequest(http, child!!.port, frozenUpload)
        assertProofReply(acceptedUpload, replayedUpload)
        val photo = decode<UploadReceipt>(replayedUpload.body).photo
        assertState(upload, photo, closed = false)
        assertPrivateBinary(photo, upload.bytes)

        val request = decode<CloseRequest>(fixtureBytes("closeNoPhotoRequest"))
        val frozenClose = FrozenProofRequest("/api/v1/work-orders/$order/close", "application/json", newId(),
            encode(request.copy(result = request.result.copy(photoIds = listOf(photo.serverPhotoId)))).toByteArray(Charsets.UTF_8), access)
        val acceptedClose = loseCommittedResponse(frozenClose) { reply ->
            assertEquals(200, reply.status)
            assertReceipt(frozenClose, closeHash(frozenClose.path, frozenClose.body), reply)
            assertState(upload, photo, closed = true)
        }
        restartChild()
        val replayedClose = sendProofRequest(http, child!!.port, frozenClose)
        assertProofReply(acceptedClose, replayedClose)
        val canonical = decode<CloseReceipt>(replayedClose.body).workOrder
        assertEquals(request.result.id, canonical.result!!.id)
        assertEquals(2L, canonical.revision)
        assertEquals(listOf(photo), canonical.result.photos)
        assertProofReply(acceptedUpload, sendProofRequest(http, child!!.port, frozenUpload))
        assertReceipt(frozenClose, closeHash(frozenClose.path, frozenClose.body), replayedClose)
        assertState(upload, photo, closed = true)
        assertPrivateBinary(photo, upload.bytes)
        Unit
    }
}
