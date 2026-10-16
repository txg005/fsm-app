package fsm

import kotlinx.serialization.json.*
import kotlin.test.*
import java.util.Base64
import org.junit.jupiter.api.Test

val fixtures: JsonObject by lazy { wire.parseToJsonElement(object {}.javaClass.getResource("/contract-fixtures.json")!!.readText()).jsonObject }
fun fixture(name: String) = fixtures.getValue(name)
fun fixtureBytes(name: String) = fixture(name).toString().toByteArray()
fun fixtureUpload(): Upload {
    val u=fixture("photoUpload").jsonObject
    return parseMultipart(u["headers"]!!.jsonObject["Content-Type"]!!.jsonPrimitive.content,Base64.getDecoder().decode(u["multipartBodyBase64"]!!.jsonPrimitive.content))
}
fun fixtureClose() = fixture("close").jsonObject["closeExactBodyUtf8"]!!.jsonPrimitive.content.toByteArray()
class ContractTest {
    @Test fun `frozen fixtures decode and semantic hashes match`() {
        decode<ReferenceData>(fixtureBytes("referenceDataResponse"))
        decode<WorkOrderList>(fixtureBytes("workOrderListResponse"))
        decode<Snapshot>(fixtureBytes("snapshotBeforeClose"))
        decode<Snapshot>(fixtureBytes("snapshotAfterClose"))
        val order=decode<WorkOrder>(fixtureBytes("workOrderResponse"))
        validateClose(decode(fixtureClose()),order)
        validateClose(decode(fixtureBytes("closeNoPhotoRequest")),order)
        decode<TokenResponse>(fixture("auth").jsonObject["tokenResponse"].toString().toByteArray())
        val upload=fixtureUpload(); val u=fixture("photoUpload").jsonObject
        assertEquals(u["semanticHash"]!!.jsonPrimitive.content,uploadHash(u["path"]!!.jsonPrimitive.content,upload.metadata))
        val close=fixture("close").jsonObject
        assertEquals(close["semanticHash"]!!.jsonPrimitive.content,closeHash(close["path"]!!.jsonPrimitive.content,fixtureClose()))
        decode<UploadReceipt>(u["response"].toString().toByteArray())
        decode<CloseReceipt>(close["response"].toString().toByteArray())
        assertEquals(631,upload.bytes.size)
    }
    @Test fun `strict JSON rejects duplicate unknown missing and wrong scalar types`() {
        listOf("""{"login":"x","login":"y","password":"z"}""","""{"login":"x","password":"z","extra":1}""","""{"login":1,"password":"z"}""","""{"login":"x"}""","""{"login":null,"password":"z"}""").forEach { text -> assertEquals("MALFORMED_REQUEST",assertFailsWith<ApiError> { decode<LoginRequest>(text.toByteArray()) }.code) }
        val bytes=fixtureBytes("closeNoPhotoRequest").toString(Charsets.UTF_8).replace("\"baseRevision\":1","\"baseRevision\":\"1\"")
        assertFailsWith<ApiError> { decode<CloseRequest>(bytes.toByteArray()) }
    }
    @Test fun `decimal UUID location and checklist validation`() {
        listOf("NaN","1e2","01","1.12345","1234567890123").forEach { assertFailsWith<ApiError> { decimal(it,"value") } }
        assertEquals("2.40",decimal("2.40","value").toPlainString())
        assertFailsWith<ApiError> { uuid("10000000-0000-1000-8000-000000000001") }
        val order=decode<WorkOrder>(fixtureBytes("workOrderResponse")); val request=decode<CloseRequest>(fixtureBytes("closeNoPhotoRequest"))
        assertFailsWith<ApiError> { validateClose(request.copy(result=request.result.copy(answers=emptyList())),order) }
        assertFailsWith<ApiError> { validateClose(request.copy(result=request.result.copy(readings=listOf(Reading("pressure","11")))),order) }
        val captured=decode<Location>(fixtureBytes("capturedLocationExample"))
        validateClose(request.copy(result=request.result.copy(location=captured)),order)
        assertFailsWith<ApiError> { validateClose(request.copy(result=request.result.copy(location=captured.copy(reason=LocationReason.DENIED))),order) }
    }
    @Test fun `multipart hash decode invalid bytes order and size`() {
        val upload=fixtureUpload()
        assertFailsWith<ApiError> { validatePhoto(upload.metadata,byteArrayOf(1,2)) }
        assertFailsWith<ApiError> { validatePhoto(upload.metadata.copy(byteSize=PHOTO_MAX+1L),upload.bytes) }
        val raw=Base64.getDecoder().decode(fixture("photoUpload").jsonObject["multipartBodyBase64"]!!.jsonPrimitive.content)
        val changed=raw.toString(Charsets.ISO_8859_1).replace("FSMFixtureBoundaryV1","another-boundary").replace("photo.jpg","../../etc/passwd").toByteArray(Charsets.ISO_8859_1)
        assertContentEquals(upload.bytes,parseMultipart("multipart/form-data; boundary=another-boundary",changed).bytes)
        assertFailsWith<ApiError> { parseMultipart("multipart/form-data; boundary=x",raw) }
    }
    @Test fun `login limiter has isolated window and reset`() {
        var at=0L; val limiter=LoginLimiter { at }
        repeat(10) { limiter.attempt("a") }
        assertEquals(429,assertFailsWith<ApiError> { limiter.attempt("a") }.status)
        limiter.attempt("b"); at=60000; limiter.attempt("a")
    }
    @Test fun `comment rejects null and unpaired surrogates without rejecting normal Unicode`() {
        val order=decode<WorkOrder>(fixtureBytes("workOrderResponse"))
        val request=decode<CloseRequest>(fixtureBytes("closeNoPhotoRequest"))
        listOf("before\u0000after", "\uD800", "\uDC00").forEach { bad ->
            val error=assertFailsWith<ApiError> { validateClose(request.copy(result=request.result.copy(comment=bad)),order) }
            assertEquals(422,error.status)
            assertEquals("VALIDATION_ERROR",error.code)
            assertEquals(buildJsonObject { put("fields",buildJsonArray { add(buildJsonObject { put("path","result.comment"); put("code","INVALID_FORMAT") }) }) },error.details)
        }
        validateClose(request.copy(result=request.result.copy(comment="Обычный Unicode: е́ 日本語 \uD83D\uDD27\n")),order)
    }

}
