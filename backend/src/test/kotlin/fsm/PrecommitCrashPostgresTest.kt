package fsm

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
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
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import kotlin.test.*

private data class CrashCheckpoint(val sqlPid: Int, val jvmPid: Long, val applicationName: String)

/** Own lifecycle only; reuses PR14's public test launcher without opening its private wrapper. */
private class PrecommitNettyChild(baseEnvironment: Map<String, String>) : AutoCloseable {
    val applicationName = "precommit_" + newId().replace("-", "")
    private val ready = CompletableFuture<Int>()
    private val process: Process
    private val reader: Thread
    val port: Int
    val pid: Long get() = process.pid()
    init {
        val entries = linkedSetOf<String>()
        var loader: ClassLoader? = PrecommitCrashPostgresTest::class.java.classLoader
        while (loader != null) {
            if (loader is URLClassLoader) loader.getURLs().filter { it.protocol == "file" }.forEach {
                entries.add(Path.of(it.toURI()).toString())
            }
            loader = loader.parent
        }
        entries.addAll(System.getProperty("java.class.path").split(File.pathSeparator).filter { it.isNotBlank() })
        val childEnvironment = baseEnvironment.toMutableMap()
        val url = childEnvironment.getValue("PROOF_DATABASE_URL")
        childEnvironment["PROOF_DATABASE_URL"] = url + (if ('?' in url) "&" else "?") + "ApplicationName=$applicationName"
        process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx256m", "-XX:MaxMetaspaceSize=192m", "-XX:ActiveProcessorCount=2",
            "-cp", entries.joinToString(File.pathSeparator), UnknownOutcomeNettyProcess::class.java.name)
            .redirectErrorStream(true).apply { environment().clear(); environment().putAll(childEnvironment) }.start()
        reader = thread(name = "precommit-netty-output", isDaemon = true) {
            try {
                process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                    if (line.startsWith("PROOF_READY ")) ready.complete(line.removePrefix("PROOF_READY ").toInt())
                    // Drain without logging arbitrary server output, tokens or database configuration.
                } }
            } finally {
                if (!ready.isDone) ready.completeExceptionally(IllegalStateException("Precommit child exited before readiness; details omitted"))
            }
        }
        try { port = ready.get(30, TimeUnit.SECONDS) } catch (t: Throwable) { close(); throw t }
        check(port in 1..65535)
    }
    fun killAt(checkpoint: CrashCheckpoint) {
        check(checkpoint.jvmPid == process.pid() && checkpoint.applicationName == applicationName)
        check(process.isAlive) { "Child exited before the proven crash checkpoint" }
        process.destroyForcibly()
        check(process.waitFor(6, TimeUnit.SECONDS)) { "Owned child did not terminate after forced kill" }
        reader.join(5000)
        check(!reader.isAlive) { "Owned child output reader did not stop" }
    }
    override fun close() {
        // Cleanup fallback is NOT counted as reaching the crash checkpoint.
        process.destroy()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            check(process.waitFor(5, TimeUnit.SECONDS)) { "Owned child cleanup failed" }
        }
        reader.join(5000)
        check(!reader.isAlive) { "Owned child output reader cleanup failed" }
    }
}

@Tag("postgres")
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class PrecommitCrashPostgresTest {
    private lateinit var db: Database
    private lateinit var storage: PhotoStorage
    private lateinit var schema: String
    private lateinit var url: String
    private lateinit var user: String
    private lateinit var password: String
    private lateinit var access: String
    private lateinit var childEnvironment: Map<String, String>
    private var child: PrecommitNettyChild? = null
    private var pending: CompletableFuture<HttpResponse<ByteArray>>? = null
    private val employee = demoId(1)
    private val order = demoId(4)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1).build()

    @BeforeEach fun setup() {
        url = System.getenv("TEST_DATABASE_URL") ?: error("TEST_DATABASE_URL required; no silent skip")
        user = System.getenv("TEST_DATABASE_USER") ?: error("TEST_DATABASE_USER required")
        password = System.getenv("TEST_DATABASE_PASSWORD") ?: ""
        // PR14 launcher restricts its schema to this generated test-only format.
        schema = "outcome_" + newId().replace("-", "")
        DriverManager.getConnection(url, user, password).use { c ->
            c.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
        db = Database(url, user, password, schema)
        storage = PhotoStorage(Files.createTempDirectory("fsm-precommit-crash-"))
        val demoPassword = newId() + "Aa!"
        seed(db, listOf(SeedAccount("precommit.one", demoPassword), SeedAccount("precommit.two", demoPassword)))
        val secret = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        access = Auth(db, secret).login(LoginRequest("precommit.one", demoPassword), "precommit-login").accessToken
        childEnvironment = mapOf("PROOF_DATABASE_URL" to url, "PROOF_DATABASE_USER" to user,
            "PROOF_DATABASE_PASSWORD" to password, "PROOF_SCHEMA" to schema,
            "PROOF_STORAGE" to storage.root.toString(), "PROOF_JWT_SECRET" to Base64.getEncoder().encodeToString(secret))
        secret.fill(0)
        child = PrecommitNettyChild(childEnvironment)
    }

    @AfterEach fun cleanup() {
        // Gate connection is scoped/finally-rolled-back in the test before child/schema cleanup.
        try { child?.close() } finally { pending?.cancel(true) }
        child = null
        if (::db.isInitialized) db.close()
        if (::schema.isInitialized) DriverManager.getConnection(url, user, password).use { c ->
            c.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
        }
        if (::storage.isInitialized) storage.root.toFile().deleteRecursively()
    }

    private fun counts(): List<Int> = db.tx { c ->
        listOf("inspection_results", "photos", "idempotency_receipts").map { table ->
            c.query("SELECT count(*) FROM $table") { it.getInt(1) }.single()
        }
    }

    private fun assertOneBlob(upload: Upload) {
        assertEquals(listOf(storage.key(upload.metadata)), Files.list(storage.root).use { paths ->
            paths.map { it.fileName.toString() }.toList()
        })
        val bytes = Files.readAllBytes(storage.path(storage.key(upload.metadata)))
        assertEquals(upload.metadata.byteSize, bytes.size.toLong())
        assertEquals(upload.metadata.sha256, sha(bytes))
        assertTrue(upload.bytes.contentEquals(bytes), "Orphan/adopted bytes differ; content omitted")
    }

    private fun checkpoint(upload: Upload, relationOid: Long, gatePid: Int): CrashCheckpoint {
        val owned = child ?: error("Missing owned child")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            val waiters = db.tx { c -> c.query("""
                SELECT DISTINCT a.pid FROM pg_stat_activity a JOIN pg_locks l ON l.pid=a.pid
                WHERE a.application_name=? AND a.datname=current_database() AND a.state='active'
                  AND a.wait_event_type='Lock' AND a.query LIKE 'INSERT INTO photos%'
                  AND l.locktype='relation' AND l.relation=?::oid AND l.mode='RowExclusiveLock'
                  AND NOT l.granted AND ?=ANY(pg_blocking_pids(a.pid))
            """.trimIndent(), owned.applicationName, relationOid, gatePid) { it.getInt("pid") } }
            val target = storage.path(storage.key(upload.metadata))
            if (waiters.size == 1 && Files.isRegularFile(target)) {
                assertOneBlob(upload)
                assertEquals(listOf(0, 0, 0), counts())
                val canonical = db.tx(true) { it.order(employee, order) }
                assertEquals(OrderStatus.ASSIGNED, canonical.status)
                assertEquals(1L, canonical.revision)
                assertNull(canonical.result)
                return CrashCheckpoint(waiters.single(), owned.pid, owned.applicationName)
            }
            // Bounded observation polling, not a sleep chosen to guess when to kill.
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20))
            check(!Thread.currentThread().isInterrupted) { "Checkpoint observation interrupted" }
        }
        error("No proven own INSERT lock wait plus durable-blob checkpoint; no crash proof claimed")
    }

    private fun awaitRollback(checkpoint: CrashCheckpoint) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            val remains = db.tx { c -> c.query(
                "SELECT count(*) FROM pg_stat_activity WHERE pid=? AND application_name=?",
                checkpoint.sqlPid, checkpoint.applicationName) { it.getInt(1) }.single() }
            if (remains == 0) {
                assertEquals(listOf(0, 0, 0), counts())
                return
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20))
            check(!Thread.currentThread().isInterrupted) { "Rollback observation interrupted" }
        }
        error("Killed child SQL backend did not leave within rollback deadline")
    }

    private fun request(port: Int, path: String, operation: String, multipart: SerializedMultipart): HttpRequest =
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(40))
            .header("Authorization", "Bearer $access").header("Idempotency-Key", operation)
            .header("Content-Type", multipart.contentType).POST(HttpRequest.BodyPublishers.ofByteArray(multipart.bytes)).build()

    @Test fun `forced own Netty JVM death after rename before SQL commit adopts one orphan on exact retry`(): Unit = runBlocking {
        val upload = fixtureUpload()
        val frozen = realKtorMultipart(upload)
        val operation = newId()
        val path = "/api/v1/work-orders/$order/photos"
        lateinit var proven: CrashCheckpoint
        DriverManager.getConnection(url, user, password).use { gate ->
            gate.autoCommit = false
            try {
                gate.createStatement().use { s ->
                    s.queryTimeout = 5
                    s.execute("SET LOCAL lock_timeout='3s'")
                    s.execute("SET LOCAL statement_timeout='5s'")
                    s.execute("SET LOCAL idle_in_transaction_session_timeout='45s'")
                    // Fully qualified generated OWN schema; no public/user table can be locked.
                    s.execute("LOCK TABLE \"$schema\".\"photos\" IN SHARE MODE")
                }
                val gatePid = gate.query("SELECT pg_backend_pid()") { it.getInt(1) }.single()
                val relationOid = gate.query("SELECT ?::regclass::oid::bigint", "$schema.photos") { it.getLong(1) }.single()
                assertEquals(1, gate.query("SELECT count(*) FROM pg_locks WHERE pid=pg_backend_pid() AND relation=?::oid AND mode='ShareLock' AND granted",
                    relationOid) { it.getInt(1) }.single())
                pending = http.sendAsync(request(child!!.port, path, operation, frozen), HttpResponse.BodyHandlers.ofByteArray())
                proven = checkpoint(upload, relationOid, gatePid)
                child!!.killAt(proven)
            } finally {
                // Release even on checkpoint/kill/assert failure. A disconnected PG backend may
                // need the gate released before it finishes its blocked INSERT and aborts its tx.
                gate.rollback()
            }
        }
        awaitRollback(proven)
        assertTrue(pending!!.handle { response, failure -> response == null && failure != null }.get(10, TimeUnit.SECONDS),
            "Killed request unexpectedly delivered a response; details omitted")
        assertOneBlob(upload)
        assertEquals(listOf(0, 0, 0), counts())
        val previousPid = proven.jvmPid
        child!!.close()
        child = null
        child = PrecommitNettyChild(childEnvironment)
        assertNotEquals(previousPid, child!!.pid)
        val accepted = http.send(request(child!!.port, path, operation, frozen), HttpResponse.BodyHandlers.ofByteArray())
        assertEquals(201, accepted.statusCode())
        uuid(accepted.headers().firstValue("X-Request-Id").orElseThrow())
        val receipt = decode<UploadReceipt>(accepted.body())
        assertEquals(operation, receipt.operationId)
        assertEquals(upload.metadata.photoId, receipt.photo.id)
        assertEquals(upload.metadata.sha256, receipt.photo.sha256)
        assertEquals(upload.metadata.byteSize, receipt.photo.byteSize)
        val replay = http.send(request(child!!.port, path, operation, frozen), HttpResponse.BodyHandlers.ofByteArray())
        assertEquals(201, replay.statusCode())
        assertTrue(accepted.body().contentEquals(replay.body()), "Exact successful replay differs; body omitted")
        assertEquals(listOf(0, 1, 1), counts())
        assertOneBlob(upload)
        db.tx(true) { c ->
            val canonical = c.order(employee, order)
            assertEquals(OrderStatus.ASSIGNED, canonical.status)
            assertEquals(1L, canonical.revision)
            assertNull(canonical.result)
            c.query("SELECT * FROM idempotency_receipts WHERE employee_id=? AND operation_id=?", id(employee), id(operation)) { r ->
                assertEquals("POST", r.getString("method"))
                assertEquals(path, r.getString("path"))
                assertEquals(uploadHash(path, upload.metadata), r.getString("semantic_hash"))
                assertEquals(201, r.getInt("status"))
                assertTrue(accepted.body().contentEquals(r.getString("body").toByteArray(Charsets.UTF_8)), "Stored receipt differs; body omitted")
            }.single()
            c.query("SELECT * FROM photos WHERE id=?", id(upload.metadata.photoId)) { r ->
                assertEquals(receipt.photo.serverPhotoId, r.getString("server_photo_id"))
                assertEquals(employee, r.getString("owner_id"))
                assertEquals(order, r.getString("work_order_id"))
                assertEquals("STAGED", r.getString("state"))
                assertNull(r.getObject("result_id"))
                assertNull(r.getObject("position"))
                assertEquals(storage.key(upload.metadata), r.getString("storage_key"))
            }.single()
        }
        val binary = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${child!!.port}${receipt.photo.contentPath}"))
            .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer $access").GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        assertEquals(200, binary.statusCode())
        assertEquals("image/jpeg", binary.headers().firstValue("Content-Type").orElseThrow())
        assertEquals(upload.bytes.size.toString(), binary.headers().firstValue("Content-Length").orElseThrow())
        assertEquals("\"${upload.metadata.sha256}\"", binary.headers().firstValue("ETag").orElseThrow())
        assertTrue(upload.bytes.contentEquals(binary.body()), "Adopted private JPEG differs; bytes omitted")
        Unit
    }
}
