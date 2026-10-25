package fsm

import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.ApplicationSendPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.io.OutputStream
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/** Explicit test-source executable, deliberately NOT a JUnit test or production entry point.
 * The caller owns the emulator, native PG16 fixture and strict orchestration/FGS observations.
 * No upload or close is performed here: only the genuine Android Service sends mutations.
 */
object AndroidServiceE2EHost {
    @JvmStatic fun main(args: Array<String>) {
        val console = System.out
        // Server/library exceptions can contain configuration. Never publish arbitrary JVM output.
        System.setOut(PrintStream(OutputStream.nullOutputStream()))
        System.setErr(PrintStream(OutputStream.nullOutputStream()))
        var host: ServiceE2eFixture? = null
        var success = false
        try {
            require(args.isEmpty())
            host = ServiceE2eFixture()
            val owned = host
            Runtime.getRuntime().addShutdownHook(Thread { owned.close() })
            host.execute()
            success = true
        } catch (_: Throwable) {
            // No causes, DTOs, credentials, SQL or filesystem paths in the failure surface.
            host?.recordFailure()
        } finally {
            try { host?.close() } catch (_: Throwable) { host?.recordFailure(); success = false }
        }
        if (success) {
            try { host!!.publishSuccess() } catch (_: Throwable) { host?.recordFailure(); success = false }
        }
        console.println(if (success) "E2E_HOST_PASS" else
            "E2E_HOST_FAILED:" + (host?.failureCategory() ?: E2eHostPhase.STARTUP).name)
        exitProcess(if (success) 0 else 1)
    }
}

// Failure-only diagnostic vocabulary. No runtime values or Throwable text can enter it.
private enum class E2eHostPhase {
    STARTUP, CLOSE_SQL, CLOSE_RELEASE, RESULT_CONTROL, RESULT_SQL, PHOTO_SQL,
    RECEIPTS_SQL, RECEIPT_HASH, BLOB, OWN_AUTH, OWN_JPEG, OWN_HEADERS,
    FOREIGN_AUTH, FOREIGN_404, CLEANUP_NETTY, CLEANUP_DATABASE, CLEANUP_SCHEMA,
    CLEANUP_STORAGE, CLEANUP_CONTROL, PUBLISH_RESULT, PUBLISH_PROOF
}

private val readyKeys = setOf("schemaVersion", "fixtureExpectationVersion", "runNonce", "hostPort", "hostReadyAt", "hostDeadlineAt")
private val releaseKeys = setOf("schemaVersion", "runNonce", "gateNonce", "closeOperationId", "servicePid", "serviceActiveSince",
    "serviceForeground", "dataSyncObserved", "notificationObserved", "workerQuiescent", "workerReservationVerified")
private val resultKeys = setOf("schemaVersion", "fixtureExpectationVersion", "runNonce", "uploadOperationId", "closeOperationId",
    "resultId", "localPhotoId", "serverPhotoId", "jpegSha256", "jpegByteSize", "resultCapturedAt",
    "closeFrozenPayloadSha256", "closeWireBodySha256", "workerQuiescent", "workerReservationVerified",
    "serviceObservedForeground", "serviceNotificationObserved", "uploadACK", "closeACK", "liveUiClosed", "finalLifecycle",
    "serviceSameInstanceAtClose", "workerQuiescentAtClose", "closeBarrierObserved")
private val resultFlags = setOf("workerQuiescent", "workerReservationVerified", "serviceObservedForeground",
    "serviceNotificationObserved", "uploadACK", "closeACK", "liveUiClosed", "serviceSameInstanceAtClose",
    "workerQuiescentAtClose", "closeBarrierObserved")
private val privateFiles = setOf("fixture.json", "close-committed.json", "close-release.json", "android-result.json", "outcome.json", "abort.json")

private fun JsonObject.text(key: String): String {
    val value = getValue(key) as JsonPrimitive
    check(value.isString)
    return value.content
}
private fun JsonObject.number(key: String): Long {
    val value = getValue(key) as JsonPrimitive
    check(!value.isString && Regex("0|[1-9][0-9]*").matches(value.content))
    return value.content.toLong()
}
private fun JsonObject.yes(key: String) {
    val value = getValue(key) as JsonPrimitive
    check(!value.isString && value.booleanOrNull == true)
}
private fun JsonObject.identifier(key: String) = uuid(text(key))
private fun JsonObject.digest(key: String) = text(key).also { check(Regex("[0-9a-f]{64}").matches(it)) }
private fun JsonObject.instant(key: String) = text(key).also { check(validInstant(it)) }.let(Instant::parse)

private class ServiceE2eFixture : AutoCloseable {
    private val phase = AtomicReference(E2eHostPhase.STARTUP)
    private val firstFailure = AtomicReference<E2eHostPhase?>(null)
    fun recordFailure() { firstFailure.compareAndSet(null, phase.get()) }
    fun failureCategory(): E2eHostPhase = firstFailure.get() ?: phase.get()
    private fun mark(value: E2eHostPhase) { phase.set(value) }
    private val closed = AtomicBoolean(false)
    private val failure = AtomicBoolean(false)
    private val barrierEntered = AtomicBoolean(false)
    private val barrierReleased = AtomicBoolean(false)
    private val operationAtGate = AtomicReference<String>()
    private var root: Path? = null
    private var storage: PhotoStorage? = null
    private var db: Database? = null
    private var stopServer: (() -> Unit)? = null
    private var schemaCreated = false
    private lateinit var jdbc: String
    private lateinit var dbUser: String
    private lateinit var dbPassword: String
    private val runNonce = newId()
    private val schema = "service_e2e_" + runNonce.replace("-", "")
    private val employee = demoId(1)
    private val order = demoId(4)
    private val closePath = "/api/v1/work-orders/$order/close"
    private val uploadPath = "/api/v1/work-orders/$order/photos"
    private lateinit var readyAt: Instant
    private lateinit var expectedJpeg: ByteArray
    private lateinit var jpegSha: String
    private lateinit var auth: Auth
    private lateinit var firstLogin: String
    private lateinit var secondLogin: String
    private lateinit var syntheticPassword: String
    private var port = 0
    private var verifiedResult: JsonObject? = null

    private fun control(name: String) = root!!.resolve(name)
    private fun exists(name: String) = Files.exists(control(name), LinkOption.NOFOLLOW_LINKS)
    private fun publish(name: String, value: JsonObject) {
        val target = control(name)
        check(!Files.exists(target, LinkOption.NOFOLLOW_LINKS))
        val temporary = Files.createTempFile(root, ".publish-", ".tmp", PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rw-------")))
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val bytes = ByteBuffer.wrap(value.toString().toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            // Atomic no-clobber publication, unlike ATOMIC_MOVE which may replace a target on Unix.
            Files.createLink(target, temporary)
        } finally { Files.deleteIfExists(temporary) }
    }
    private fun read(name: String, keys: Set<String>): JsonObject {
        val path = control(name)
        check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) in 2..16384)
        val bytes = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(16385) }
        check(bytes.size <= 16384)
        strictJson(bytes) // Independent duplicate-key rejection, including escaped duplicates.
        val raw = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val obj = wire.parseToJsonElement(raw).jsonObject
        check(raw == obj.toString() && obj.keys == keys)
        check(obj.number("schemaVersion") == 1L && obj.identifier("runNonce") == runNonce)
        return obj
    }
    private fun abortCheck() {
        check(!closed.get() && !failure.get())
        if (exists("abort.json")) {
            val aborted = read("abort.json", setOf("schemaVersion", "runNonce", "code"))
            check(aborted.text("code") == "ORCHESTRATION_FAILED")
            error("E2E aborted")
        }
    }
    private fun <T> transaction(block: (Connection) -> T): T = db!!.tx(true) { c ->
        c.exec("SET LOCAL statement_timeout='5s'")
        block(c)
    }
    private fun Connection.count(table: String) = query("SELECT count(*) FROM $table") { it.getInt(1) }.single()

    fun execute() = runBlocking {
        check(System.getenv("FSM_SERVICE_E2E_DISPOSABLE") == "1")
        val inputUrl = System.getenv("TEST_DATABASE_URL") ?: error("Missing test configuration")
        val target = URI(inputUrl.removePrefix("jdbc:"))
        check(inputUrl.startsWith("jdbc:postgresql://") && target.scheme == "postgresql")
        check(target.host in setOf("127.0.0.1", "localhost", "[::1]", "::1") && target.userInfo == null)
        check(target.path in setOf("/fsm_service_e2e", "/fsm_test", "/fsm_ci") && target.rawQuery == null && target.fragment == null)
        jdbc = inputUrl + "?connectTimeout=5&socketTimeout=15&loginTimeout=5&ApplicationName=$schema" +
            "&options=-c%20statement_timeout%3D10000%20-c%20lock_timeout%3D3000"
        dbUser = System.getenv("TEST_DATABASE_USER") ?: error("Missing test configuration")
        dbPassword = System.getenv("TEST_DATABASE_PASSWORD") ?: error("Missing test configuration")
        val requestedRoot = Path.of(System.getProperty("fsm.serviceE2e.controlRoot") ?: error("Missing control directory")).normalize()
        check(requestedRoot.isAbsolute && requestedRoot.parent.toRealPath() == requestedRoot.parent)
        check(!Files.exists(requestedRoot, LinkOption.NOFOLLOW_LINKS))
        root = Files.createDirectory(requestedRoot, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        DriverManager.getConnection(jdbc, dbUser, dbPassword).use { c ->
            check(c.query("SELECT current_setting('server_version_num')::integer") { it.getInt(1) }.single() in 160000..169999)
            c.exec("CREATE SCHEMA $schema")
            schemaCreated = true
        }
        db = Database(jdbc, dbUser, dbPassword, schema)
        storage = PhotoStorage(Files.createTempDirectory("fsm-service-e2e-", PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rwx------"))))
        firstLogin = "service.one." + runNonce.replace("-", "")
        secondLogin = "service.two." + runNonce.replace("-", "")
        syntheticPassword = newId() + "Aa!"
        seed(db!!, listOf(SeedAccount(firstLogin, syntheticPassword), SeedAccount(secondLogin, syntheticPassword)))
        val secret = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        try { auth = Auth(db!!, secret) } finally { secret.fill(0) }
        val fixture = fixtureUpload()
        expectedJpeg = fixture.bytes
        jpegSha = sha(expectedJpeg)
        check(expectedJpeg.size == 631 && jpegSha == "4a0043beb47e76e4bf6a12e9d93832dc69d596c8d1b114870e17f9d1420d61bf")
        val operations = Operations(db!!, storage!!)
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            fsm(db!!, auth, operations)
            // respondText already creates TextContent(status=200). This phase is BEFORE Engine:
            // no successful close response headers/body are sent while this interceptor is waiting.
            sendPipeline.intercept(ApplicationSendPipeline.Before) {
                if (call.request.httpMethod == HttpMethod.Post && call.request.path() == closePath &&
                    (subject as? OutgoingContent)?.status == HttpStatusCode.OK) {
                    try { closeResponseBarrier() } catch (_: Throwable) {
                        recordFailure()
                        failure.set(true)
                        error("E2E close barrier failed")
                    }
                }
            }
        }
        stopServer = { server.stop(100, 2000) }
        server.start(wait = false)
        port = server.engine.resolvedConnectors().single().port
        readyAt = Instant.now()
        publish("fixture.json", buildJsonObject {
            put("schemaVersion", 1); put("fixtureExpectationVersion", 1); put("runNonce", runNonce)
            put("employeeId", employee); put("workOrderId", order)
            put("checklistItemIds", buildJsonArray { add(demoId(5)) })
            put("expectedJpegSha256", jpegSha); put("expectedJpegByteSize", expectedJpeg.size)
            put("login", firstLogin); put("password", syntheticPassword)
        })
        val ready = buildJsonObject {
            put("schemaVersion", 1); put("fixtureExpectationVersion", 1); put("runNonce", runNonce); put("hostPort", port)
            put("hostReadyAt", timestamp(readyAt)); put("hostDeadlineAt", timestamp(readyAt.plusSeconds(480)))
        }
        check(ready.keys == readyKeys)
        publish("ready.json", ready)
        val deadline = System.nanoTime() + Duration.ofMinutes(8).toNanos()
        while (!exists("outcome.json")) {
            abortCheck()
            check(System.nanoTime() < deadline)
            delay(50)
        }
        abortCheck()
        check(System.nanoTime() < deadline)
        mark(E2eHostPhase.RESULT_CONTROL)
        val outcome = read("outcome.json", setOf("schemaVersion", "runNonce", "androidExitCode"))
        check(outcome.number("androidExitCode") == 0L && barrierEntered.get() && barrierReleased.get())
        val result = read("android-result.json", resultKeys)
        withTimeout(30_000) { runInterruptible(Dispatchers.IO) { verify(result) } }
        verifiedResult = result
    }

    // No PASS artifact is published until owned resource cleanup has succeeded.
    fun publishSuccess() {
        mark(E2eHostPhase.PUBLISH_RESULT)
        check(closed.get() && !failure.get())
        publish("result.json", verifiedResult ?: error("Missing verified result"))
        mark(E2eHostPhase.PUBLISH_PROOF)
        publish("hostproof.json", buildJsonObject {
            put("schemaVersion", 1); put("runNonce", runNonce); put("status", "PASS")
            put("sqlVerified", true); put("receiptsVerified", true); put("privateJpegVerified", true); put("foreignPhoto404", true)
        })
    }

    private suspend fun closeResponseBarrier() {
        mark(E2eHostPhase.CLOSE_SQL)
        check(barrierEntered.compareAndSet(false, true)) // A second successful close cannot bypass attribution.
        val closeOperation = transaction { c ->
            check(c.count("inspection_results") == 1 && c.count("photos") == 1 && c.count("idempotency_receipts") == 2)
            val canonical = c.order(employee, order)
            check(canonical.status == OrderStatus.CLOSED && canonical.revision == 2L && canonical.result!!.photos.size == 1)
            check(c.query("SELECT state FROM photos") { it.getString(1) }.single() == "ATTACHED")
            c.query("SELECT operation_id FROM idempotency_receipts WHERE employee_id=? AND method='POST' AND path=? AND status=200",
                id(employee), closePath) { it.getString(1) }.single().also(::uuid)
        }
        mark(E2eHostPhase.CLOSE_RELEASE)
        operationAtGate.set(closeOperation)
        val gateNonce = newId()
        val committedAt = Instant.now()
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        publish("close-committed.json", buildJsonObject {
            put("schemaVersion", 1); put("runNonce", runNonce); put("gateNonce", gateNonce); put("closeOperationId", closeOperation)
            put("hostCommittedAt", timestamp(committedAt)); put("releaseDeadlineAt", timestamp(committedAt.plusSeconds(15)))
        })
        while (!exists("close-release.json")) {
            abortCheck()
            check(System.nanoTime() < deadline)
            delay(20)
        }
        abortCheck()
        check(System.nanoTime() < deadline)
        val release = read("close-release.json", releaseKeys)
        check(release.identifier("gateNonce") == gateNonce && release.identifier("closeOperationId") == closeOperation)
        check(release.number("servicePid") in 1..Int.MAX_VALUE.toLong() && release.number("serviceActiveSince") > 0)
        listOf("serviceForeground", "dataSyncObserved", "notificationObserved", "workerQuiescent", "workerReservationVerified").forEach(release::yes)
        // The CI controller independently validates the private same-instance/FGS checkpoints.
        // This host does not claim that untrusted boolean fields alone prove Android ownership.
        barrierReleased.set(true)
    }

    private fun verify(result: JsonObject) {
        mark(E2eHostPhase.RESULT_CONTROL)
        check(result.number("fixtureExpectationVersion") == 1L)
        resultFlags.forEach(result::yes)
        check(result.text("finalLifecycle") == "CLOSED" && result.text("jpegSha256") == jpegSha)
        check(result.number("jpegByteSize") == expectedJpeg.size.toLong())
        val captured = result.instant("resultCapturedAt")
        // The controller first requires bracketed adb clock skew <=5s and roundtrip <=2s.
        check(!captured.isBefore(readyAt.minusSeconds(5)) && !captured.isAfter(Instant.now().plusSeconds(5)))
        val uploadOperation = result.identifier("uploadOperationId")
        val closeOperation = result.identifier("closeOperationId")
        val resultId = result.identifier("resultId")
        val localPhoto = result.identifier("localPhotoId")
        val serverPhoto = result.identifier("serverPhotoId")
        check(uploadOperation != closeOperation && closeOperation == operationAtGate.get())
        result.digest("closeFrozenPayloadSha256") // Persisted frozen bytes: distinct from transformed wire bytes.
        val wireHash = result.digest("closeWireBodySha256")
        var photo: Photo? = null
        mark(E2eHostPhase.RESULT_SQL)
        transaction { c ->
            check(c.count("inspection_results") == 1 && c.count("photos") == 1 && c.count("idempotency_receipts") == 2)
            val canonical = c.order(employee, order)
            check(canonical.status == OrderStatus.CLOSED && canonical.revision == 2L)
            val final = canonical.result!!
            check(final.id == resultId && final.answers == listOf(Answer(demoId(5), true)))
            check(final.readings == listOf(Reading("pressure", "2.40")) && final.comment == "Демонстрационный результат")
            check(final.location == Location(LocationStatus.MISSING, LocationReason.DENIED, null, null, null, null))
            check(final.capturedAt == result.text("resultCapturedAt") && final.photos.size == 1)
            photo = final.photos.single()
            check(photo!!.id == localPhoto && photo!!.serverPhotoId == serverPhoto && photo!!.sha256 == jpegSha && photo!!.byteSize == expectedJpeg.size.toLong())
            mark(E2eHostPhase.PHOTO_SQL)
            c.query("SELECT * FROM photos") { row ->
                check(row.getString("owner_id") == employee && row.getString("work_order_id") == order && row.getString("result_id") == resultId)
                check(row.getString("state") == "ATTACHED" && row.getInt("position") == 0)
                check(row.getString("storage_key") == storage!!.key(PhotoMetadata(localPhoto, "image/jpeg", expectedJpeg.size.toLong(), jpegSha)))
            }.single()
            val metadata = PhotoMetadata(localPhoto, "image/jpeg", expectedJpeg.size.toLong(), jpegSha)
            mark(E2eHostPhase.RECEIPTS_SQL)
            c.query("SELECT * FROM idempotency_receipts WHERE employee_id=?", id(employee)) { row ->
                mark(E2eHostPhase.RECEIPTS_SQL)
                val op = row.getString("operation_id")
                check(row.getString("method") == "POST")
                when (op) {
                    uploadOperation -> {
                        check(row.getString("path") == uploadPath && row.getInt("status") == 201 && row.getString("semantic_hash") == uploadHash(uploadPath, metadata))
                        check(decode<UploadReceipt>(row.getString("body").toByteArray()) == UploadReceipt(op, photo!!))
                    }
                    closeOperation -> {
                        check(row.getString("path") == closePath && row.getInt("status") == 200)
                        mark(E2eHostPhase.RECEIPT_HASH)
                        check(row.getString("semantic_hash") == sha("POST\n$closePath\n$wireHash".toByteArray(Charsets.UTF_8)))
                        check(decode<CloseReceipt>(row.getString("body").toByteArray()) == CloseReceipt(op, canonical))
                    }
                    else -> error("Unexpected operation")
                }
                op
            }.also { check(it.toSet() == setOf(uploadOperation, closeOperation) && it.size == 2) }
        }
        mark(E2eHostPhase.BLOB)
        val metadata = PhotoMetadata(localPhoto, "image/jpeg", expectedJpeg.size.toLong(), jpegSha)
        check(Files.list(storage!!.root).use { files -> files.map { it.fileName.toString() }.toList() } == listOf(storage!!.key(metadata)))
        check(Files.readAllBytes(storage!!.path(storage!!.key(metadata))).contentEquals(expectedJpeg))
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        fun getPhoto(token: String): HttpResponse<ByteArray> = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port${photo!!.contentPath}"))
            .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer $token").GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        mark(E2eHostPhase.OWN_AUTH)
        val ownToken = auth.login(LoginRequest(firstLogin, syntheticPassword), "host-own").accessToken
        mark(E2eHostPhase.OWN_JPEG)
        val own = getPhoto(ownToken)
        check(own.statusCode() == 200 && own.body().contentEquals(expectedJpeg))
        mark(E2eHostPhase.OWN_HEADERS)
        check(own.headers().firstValue("Content-Length").orElseThrow() == expectedJpeg.size.toString())
        check(own.headers().firstValue("Content-Type").orElseThrow() == "image/jpeg")
        check(own.headers().firstValue("ETag").orElseThrow() == "\"$jpegSha\"")
        mark(E2eHostPhase.FOREIGN_AUTH)
        val otherToken = auth.login(LoginRequest(secondLogin, syntheticPassword), "host-other").accessToken
        mark(E2eHostPhase.FOREIGN_404)
        val foreign = getPhoto(otherToken)
        check(foreign.statusCode() == 404 && decodeErrorCode(foreign.body()) == "NOT_FOUND")
    }

    private fun decodeErrorCode(bytes: ByteArray): String {
        strictJson(bytes)
        return wire.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject.getValue("error").jsonObject.text("code")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var cleanupFailed = false
        fun clean(category: E2eHostPhase, block: () -> Unit) {
            mark(category)
            try { block() } catch (_: Throwable) { recordFailure(); cleanupFailed = true }
        }
        clean(E2eHostPhase.CLEANUP_NETTY) { stopServer?.invoke() }
        clean(E2eHostPhase.CLEANUP_DATABASE) { db?.close() }
        if (schemaCreated) clean(E2eHostPhase.CLEANUP_SCHEMA) {
            check(Regex("service_e2e_[0-9a-f]{32}").matches(schema))
            DriverManager.getConnection(jdbc, dbUser, dbPassword).use { it.exec("DROP SCHEMA $schema CASCADE") }
        }
        storage?.root?.let { directory -> clean(E2eHostPhase.CLEANUP_STORAGE) { Files.walk(directory).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) } } }
        root?.let { directory ->
            privateFiles.forEach { name -> clean(E2eHostPhase.CLEANUP_CONTROL) { Files.deleteIfExists(directory.resolve(name)) } }
        }
        check(!cleanupFailed)
    }
}
