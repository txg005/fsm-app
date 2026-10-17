package fsm

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.Base64

private val RequestId = AttributeKey<String>("requestId")
fun Application.fsm(db: Database, auth: Auth, operations: Operations) {
    intercept(ApplicationCallPipeline.Setup) {
        val requestId=newId(); call.attributes.put(RequestId,requestId)
        call.response.header("X-Request-Id",requestId)
    }
    install(StatusPages) {
        exception<ApiError> { call, e -> call.error(e) }
        exception<Throwable> { call, e ->
            // Raw exception messages/stacks can contain SQL values or credentials; log whitelist only.
            call.application.environment.log.error("event=REQUEST_FAILED requestId=${call.attributes.getOrNull(RequestId) ?: "unavailable"}")
            call.error(ApiError(if(e is java.sql.SQLException) 503 else 500,if(e is java.sql.SQLException) "SERVICE_UNAVAILABLE" else "INTERNAL_ERROR"))
        }
        status(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed, HttpStatusCode.UnsupportedMediaType, HttpStatusCode.BadRequest, HttpStatusCode.PayloadTooLarge) { call,status ->
            call.error(ApiError(if(status==HttpStatusCode.NotFound || status==HttpStatusCode.MethodNotAllowed) 404 else 400,if(status==HttpStatusCode.NotFound || status==HttpStatusCode.MethodNotAllowed) "NOT_FOUND" else "MALFORMED_REQUEST"))
        }
    }
    routing {
        get("/health") { withContext(Dispatchers.IO) { db.tx { it.query("SELECT 1") { true } }; call.respondText("ok") } }
        route("/api/v1") {
            post("/auth/login") { io { noQuery(); val r=body<LoginRequest>(); json(200,encode(auth.login(r,request.local.remoteHost))) } }
            post("/auth/refresh") { io { noQuery(); json(200,encode(auth.refresh(body()))) } }
            post("/auth/logout") { io { noQuery(); auth.logout(body()); respond(HttpStatusCode.NoContent) } }
            get("/reference-data") { io { val employee=protected(auth); json(200,encode(db.tx(true) { it.reference(employee) })) } }
            get("/work-orders") { io { val employee=protected(auth); json(200,encode(WorkOrderList(db.tx(true) { it.orders(employee) }))) } }
            get("/work-orders/{workOrderId}") { io { val employee=protected(auth); val order=uuid(parameters["workOrderId"] ?: malformed()); json(200,encode(db.tx(true) { it.order(employee,order) })) } }
            get("/sync/snapshot") { io {
                val employee=protected(auth)
                val snapshot=db.tx(true) { c -> val ref=c.reference(employee); Snapshot(now(),ref.employee,ref.sites,ref.equipment,c.orders(employee)) }
                json(200,encode(snapshot))
            } }
            post("/work-orders/{workOrderId}/photos") { io {
                val employee=protected(auth); val order=uuid(parameters["workOrderId"] ?: malformed())
                operations.accessible(employee,order)
                val operation=uuid(request.headers["Idempotency-Key"] ?: malformed())
                val bytes=boundedBody(MULTIPART_MAX,true)
                val upload=parseMultipart(request.headers[HttpHeaders.ContentType] ?: malformed(),bytes)
                val response=operations.upload(employee,order,operation,upload)
                json(response.status,response.body)
            } }
            post("/work-orders/{workOrderId}/close") { io {
                val employee=protected(auth); val order=uuid(parameters["workOrderId"] ?: malformed())
                operations.accessible(employee,order)
                val operation=uuid(request.headers["Idempotency-Key"] ?: malformed())
                requireJson()
                val response=operations.close(employee,order,operation,boundedBody(JSON_MAX,false))
                json(response.status,response.body)
            } }
            get("/photos/{serverPhotoId}/content") { io {
                val employee=protected(auth); val photoId=uuid(parameters["serverPhotoId"] ?: malformed())
                val (photo,path)=operations.content(employee,photoId)
                response.header(HttpHeaders.ETag,"\"${photo.sha256}\"")
                response.header(HttpHeaders.ContentLength,photo.byteSize.toString())
                // No conditional/range plugins. The full private file is served only after scope checks.
                respondFile(path.toFile())
            } }
        }
    }
}
private suspend fun RoutingContext.io(block: suspend ApplicationCall.() -> Unit) { withContext(Dispatchers.IO) { call.block() } }
private fun ApplicationCall.noQuery() { if(request.queryParameters.names().isNotEmpty()) malformed() }
private fun ApplicationCall.protected(auth: Auth): String { noQuery(); return auth.authenticate(request.headers[HttpHeaders.Authorization]) }
private fun ApplicationCall.requireJson() {
    val ct=try { request.contentType() } catch(_: Exception) { malformed() }
    if(!ct.match(ContentType.Application.Json) || ct.parameter("charset")?.lowercase()?.let { it!="utf-8" }==true) malformed()
    if(request.headers[HttpHeaders.ContentEncoding]?.let { it!="identity" }==true) malformed()
}
private suspend inline fun <reified T> ApplicationCall.body(): T { requireJson(); return decode(boundedBody(JSON_MAX,false)) }
private suspend fun ApplicationCall.boundedBody(max: Int, photo: Boolean): ByteArray {
    request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { if(it>max) tooLarge(photo) }
    val out=ByteArrayOutputStream(); val channel=receiveChannel(); val buffer=ByteArray(8192)
    while(true) {
        val n=channel.readAvailable(buffer,0,buffer.size)
        if(n<0) break
        if(n>0) { if(out.size()+n>max) tooLarge(photo); out.write(buffer,0,n) }
    }
    return out.toByteArray()
}
private suspend fun ApplicationCall.json(status: Int, body: String) = respondText(body,ContentType.Application.Json,HttpStatusCode.fromValue(status))
private suspend fun ApplicationCall.error(e: ApiError) {
    e.retryAfter?.let { response.header(HttpHeaders.RetryAfter,it.toString()) }
    val requestId=attributes.getOrNull(RequestId) ?: newId().also { response.header("X-Request-Id",it) }
    json(e.status,buildJsonObject {
        put("error",buildJsonObject { put("code",e.code); put("message",e.code); put("retryable",e.status in listOf(408,429,500,503)); put("details",e.details) })
        put("requestId",requestId)
    }.toString())
}
fun main() {
    fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("Missing configuration: $name")
    try {
        val secret=Base64.getDecoder().decode(env("JWT_SECRET_BASE64"))
        require(secret.size>=32)
        val db=Database(env("DATABASE_URL"),env("DATABASE_USER"),env("DATABASE_PASSWORD"))
        if(System.getenv("SEED_DEMO")=="true") seed(db,listOf(SeedAccount(env("DEMO_LOGIN_1"),env("DEMO_PASSWORD_1")),SeedAccount(env("DEMO_LOGIN_2"),env("DEMO_PASSWORD_2"))))
        val auth=Auth(db,secret); secret.fill(0)
        val operations=Operations(db,PhotoStorage(Path.of(env("PHOTO_STORAGE_DIR"))))
        val server=embeddedServer(Netty,port=System.getenv("PORT")?.toInt() ?: 8080,host="0.0.0.0") { fsm(db,auth,operations) }
        Runtime.getRuntime().addShutdownHook(Thread { server.stop(1000,5000); db.close() })
        server.start(wait=true)
    } catch(_: Exception) {
        System.err.println("event=STARTUP_FAILED check configuration/database; sensitive details omitted")
        kotlin.system.exitProcess(1)
    }
}
