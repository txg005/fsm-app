package fsm

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

@Tag("postgres")
class KtorMultipartPostgresTest {
    @Test fun `Android real Ktor multipart HTTP upload close and receipt replay`() = testApplication {
        val url=System.getenv("TEST_DATABASE_URL") ?: error("TEST_DATABASE_URL required")
        val user=System.getenv("TEST_DATABASE_USER") ?: error("TEST_DATABASE_USER required")
        val dbPassword=System.getenv("TEST_DATABASE_PASSWORD") ?: ""
        val schema="k_"+newId().replace("-","")
        DriverManager.getConnection(url,user,dbPassword).use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        val db=Database(url,user,dbPassword,schema)
        val directory=Files.createTempDirectory("fsm-ktor-http-")
        try {
            val password=newId()+"Aa!"
            seed(db,listOf(SeedAccount("ktor.one",password),SeedAccount("ktor.two",password)))
            val auth=Auth(db,ByteArray(32).also(java.security.SecureRandom()::nextBytes))
            val ops=Operations(db,PhotoStorage(directory))
            application { fsm(db,auth,ops) }
            val access=auth.login(LoginRequest("ktor.one",password),"ktor-test").accessToken
            val actual=realKtorMultipart(); val key=newId(); val order=demoId(4)
            suspend fun upload(bytes: ByteArray=actual.bytes, type: String=actual.contentType, operation: String=key): HttpResponse = client.post("/api/v1/work-orders/$order/photos") {
                bearerAuth(access); header("Idempotency-Key",operation); contentType(ContentType.parse(type)); setBody(bytes)
            }
            val first=upload(); assertEquals(201,first.status.value)
            val firstBody=first.bodyAsText(); val receipt=decode<UploadReceipt>(firstBody.toByteArray())
            val replay=upload(); assertEquals(201,replay.status.value); assertEquals(firstBody,replay.bodyAsText())
            val newBoundary=realKtorMultipart()
            val semanticReplay=upload(newBoundary.bytes,newBoundary.contentType)
            assertEquals(201,semanticReplay.status.value); assertEquals(firstBody,semanticReplay.bodyAsText())
            val newKey=upload(operation=newId())
            assertEquals(receipt.photo,decode<UploadReceipt>(newKey.bodyAsText().toByteArray()).photo)
            val close=decode<CloseRequest>(fixtureBytes("closeNoPhotoRequest")).let { it.copy(result=it.result.copy(photoIds=listOf(receipt.photo.serverPhotoId))) }
            val closeKey=newId()
            suspend fun closeRequest() = client.post("/api/v1/work-orders/$order/close") {
                bearerAuth(access); header("Idempotency-Key",closeKey); contentType(ContentType.Application.Json); setBody(encode(close))
            }
            val accepted=closeRequest(); assertEquals(200,accepted.status.value)
            val closedBody=accepted.bodyAsText()
            assertEquals(listOf(receipt.photo),decode<CloseReceipt>(closedBody.toByteArray()).workOrder.result!!.photos)
            assertEquals(closedBody,closeRequest().bodyAsText())
            val afterClosed=upload(); assertEquals(201,afterClosed.status.value); assertEquals(firstBody,afterClosed.bodyAsText())
            val binary=client.get(receipt.photo.contentPath) { bearerAuth(access) }
            assertEquals(200,binary.status.value); assertContentEquals(fixtureUpload().bytes,binary.bodyAsBytes())
            db.tx { c ->
                assertEquals(1,c.query("SELECT count(*) FROM photos") { it.getInt(1) }.single())
                assertEquals(1,c.query("SELECT count(*) FROM inspection_results") { it.getInt(1) }.single())
                assertEquals(3,c.query("SELECT count(*) FROM idempotency_receipts") { it.getInt(1) }.single())
            }
        } finally {
            db.close(); directory.toFile().deleteRecursively()
            DriverManager.getConnection(url,user,dbPassword).use { c -> c.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }
}
