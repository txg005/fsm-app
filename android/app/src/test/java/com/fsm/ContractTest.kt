package com.fsm

import com.fsm.data.*
import com.fsm.sync.*
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpResponseData
import io.ktor.client.engine.mock.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ContractTest {
    private val fixture=wire.parseToJsonElement(javaClass.getResource("/contract-fixtures.json")!!.readText()).jsonObject
    private inline fun <reified T> read(name:String):T=wire.decodeFromJsonElement(fixture.getValue(name))
    private class Tokens(var token:String?):RefreshStore { override fun read()=token; override fun write(token:String) {this.token=token} }
    private fun engine(handler:suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData)->HttpResponseData)=MockEngine(handler)
    @Test fun fixtureDtosAndUnknownFields() {
        val before=read<SnapshotDto>("snapshotBeforeClose"); val after=read<SnapshotDto>("snapshotAfterClose")
        assertNull(before.workOrders.single().result); assertEquals("CLOSED",after.workOrders.single().status)
        assertNotNull(after.workOrders.single().result)
        assertEquals("CAPTURED",read<LocationDto>("capturedLocationExample").status)
        fixture["missingLocationExamples"]!!.jsonArray.forEach { assertEquals("MISSING",wire.decodeFromJsonElement<LocationDto>(it).status) }
        val extra=fixture["snapshotBeforeClose"]!!.jsonObject.toMutableMap().apply { put("future",JsonPrimitive(1)) }
        assertEquals(before,wire.decodeFromJsonElement<SnapshotDto>(JsonObject(extra)))
        fixture["errorExamples"]!!.jsonObject.values.forEach { wire.decodeFromJsonElement<ErrorBody>(it.jsonObject.getValue("body")) }
        val token=wire.decodeFromJsonElement<TokenResponse>(fixture["auth"]!!.jsonObject.getValue("tokenResponse"))
        assertEquals(before.employee.id,token.employee.id)
    }
    @Test fun exactCloseBytesThroughMockEngine()=runTest {
        val close=fixture["close"]!!.jsonObject; val body=close["closeExactBodyUtf8"]!!.jsonPrimitive.content
        val key=close["headers"]!!.jsonObject["Idempotency-Key"]!!.jsonPrimitive.content
        val id=fixture["ids"]!!.jsonObject["workOrder"]!!.jsonPrimitive.content
        val token=wire.decodeFromJsonElement<TokenResponse>(fixture["auth"]!!.jsonObject.getValue("tokenResponse"))
        var calls=0
        val client=HttpClient(engine { request ->
            calls++
            assertEquals("/api/v1/work-orders/$id/close",request.url.encodedPath)
            assertEquals(key,request.headers["Idempotency-Key"])
            assertEquals(body,(request.body as TextContent).text)
            assertEquals("Bearer ${token.accessToken}",request.headers[HttpHeaders.Authorization])
            respond(close["response"].toString(),HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        })
        val api=Api(client,"https://example.test",Tokens(null),token.employee.id).apply {accept(token)}
        repeat(2) { assertEquals(key,api.close(id,key,body).operationId) }
        assertEquals(2,calls)
        val input="POST\n/api/v1/work-orders/$id/close\n"+sha256(body.toByteArray(Charsets.UTF_8))
        assertEquals(close["semanticHash"]!!.jsonPrimitive.content,sha256(input.toByteArray()))
        client.close()
    }
    @Test fun multipartFixtureAndSameKeyOnReplay()=runTest {
        val upload=fixture["photoUpload"]!!.jsonObject
        val meta=wire.decodeFromJsonElement<PhotoUploadMetadata>(upload.getValue("metadata"))
        val bytes=Base64.getDecoder().decode(upload["jpegBase64"]!!.jsonPrimitive.content)
        assertEquals(meta.byteSize,bytes.size.toLong()); assertEquals(meta.sha256,sha256(bytes))
        val id=fixture["ids"]!!.jsonObject["workOrder"]!!.jsonPrimitive.content
        val key=upload["headers"]!!.jsonObject["Idempotency-Key"]!!.jsonPrimitive.content
        val token=wire.decodeFromJsonElement<TokenResponse>(fixture["auth"]!!.jsonObject.getValue("tokenResponse"))
        val boundaries=mutableSetOf<String>()
        val client=HttpClient(engine { request ->
            assertEquals(key,request.headers["Idempotency-Key"])
            val multipart=request.body as MultiPartFormDataContent
            boundaries.add(multipart.boundary)
            respond(upload["response"].toString(),HttpStatusCode.Created,headersOf(HttpHeaders.ContentType,"application/json"))
        })
        val api=Api(client,"https://example.test",Tokens(null),token.employee.id).apply {accept(token)}
        repeat(2) { assertEquals(meta.photoId,api.upload(id,key,meta,bytes).photo.id) }
        assertEquals(2,boundaries.size)
        val input="POST\n/api/v1/work-orders/$id/photos\n${meta.photoId}\nimage/jpeg\n${meta.byteSize}\n${meta.sha256}"
        assertEquals(upload["semanticHash"]!!.jsonPrimitive.content,sha256(input.toByteArray()))
        client.close()
    }
    @Test fun refreshOnceOn401AndNoRedirect()=runTest {
        val token=wire.decodeFromJsonElement<TokenResponse>(fixture["auth"]!!.jsonObject.getValue("tokenResponse"))
        var refreshes=0; var snapshots=0
        val client=HttpClient(engine { request ->
            if(request.url.encodedPath.endsWith("refresh")) {refreshes++; respond(wire.encodeToString(TokenResponse.serializer(),token),HttpStatusCode.OK)}
            else { snapshots++; if(snapshots==1) respond(fixture["errorExamples"]!!.jsonObject["auth"]!!.jsonObject["body"].toString(),HttpStatusCode.Unauthorized) else respond(fixture["snapshotBeforeClose"].toString(),HttpStatusCode.OK) }
        })
        val api=Api(client,"https://example.test",Tokens(token.refreshToken),token.employee.id).apply {accept(token)}
        api.snapshot(); assertEquals(1,refreshes); assertEquals(2,snapshots); client.close()
    }
    @Test fun mediaRefRejectsExternalPaths() {
        val receipt=wire.decodeFromJsonElement<PhotoUploadReceipt>(fixture["photoUpload"]!!.jsonObject.getValue("response"))
        assertTrue(Api.validContentPath(receipt.photo))
        listOf("https://evil.test/a","//evil.test/a",receipt.photo.contentPath+"?token=x","/api/v1/photos/../content").forEach { assertFalse(Api.validContentPath(receipt.photo.copy(contentPath=it))) }
    }
    @Test fun validationAndRetryStates() {
        val order=read<SnapshotDto>("snapshotBeforeClose").workOrders.single()
        val close=read<CloseRequest>("closeNoPhotoRequest")
        assertTrue(Validation.errors(order,close.result).isEmpty())
        assertTrue(Validation.errors(order,close.result.copy(comment="bad\u0000comment")).isNotEmpty())
        assertTrue(Validation.errors(order,close.result.copy(answers=emptyList())).isNotEmpty())
        assertTrue(Validation.errors(order,close.result.copy(readings=listOf(ReadingDto("pressure","10.0001")))).isNotEmpty())
        listOf("NaN","1e2","01","1.00000","1234567890123", " 2").forEach {assertFalse(it,Validation.validDecimal(it))}
        assertEquals("AUTH_REQUIRED",RetryPolicy.state(401)); assertEquals("CONFLICT",RetryPolicy.state(409)); assertEquals("FAILED",RetryPolicy.state(422))
        listOf(408,429,500,503).forEach {assertEquals("RETRY_WAIT",RetryPolicy.state(it))}
        assertEquals(2000L,RetryPolicy.delayMillis(1,0)); assertEquals(300000L,RetryPolicy.delayMillis(12,0))
    }
    @Test fun outboxDependenciesAndUnrelatedWork() {
        val upload=Operation(uuid(),"UPLOAD_PHOTO",uuid(),uuid(),1,frozenPayload="immutable")
        val close=Operation(uuid(),"CLOSE_WORK_ORDER",upload.workOrderId,sequence=2,frozenPayload="snapshot",dependencies=wire.encodeToString(listOf(upload.id)))
        val unrelated=Operation(uuid(),"CLOSE_WORK_ORDER",uuid(),sequence=3,frozenPayload="independent")
        assertEquals(upload,readyOperation(listOf(upload,close,unrelated),0,emptyMap()))
        assertEquals(unrelated,readyOperation(listOf(upload.copy(state="FAILED"),close,unrelated),0,emptyMap()))
        assertEquals(close,readyOperation(listOf(upload.copy(state="ACKED"),close),0,emptyMap()))
        assertNull(readyOperation(listOf(close),0,emptyMap()))
        assertNull(readyOperation(listOf(upload.copy(state="RETRY_WAIT",nextAttemptAt=100)),99,emptyMap()))
        assertNull(readyOperation(listOf(upload),100,mapOf(upload.id to 5)))
        val recovered=upload.copy(state="PENDING",attempts=2)
        assertEquals(upload.id,recovered.id); assertEquals(upload.frozenPayload,recovered.frozenPayload)
    }

    @Test fun failedRefreshDoesNotLoopOrEraseRefreshStore()=runTest {
        val token=wire.decodeFromJsonElement<TokenResponse>(fixture["auth"]!!.jsonObject.getValue("tokenResponse"))
        val tokens=Tokens(token.refreshToken); var calls=0
        val client=HttpClient(engine { _ -> calls++; respond(fixture["errorExamples"]!!.jsonObject["auth"]!!.jsonObject["body"].toString(),HttpStatusCode.Unauthorized) })
        val api=Api(client,"https://example.test",tokens,token.employee.id).apply {accept(token)}
        try {api.snapshot(); fail("Expected auth pause")} catch(e:ApiFailure) {assertEquals(401,e.status)}
        assertEquals(2,calls); assertEquals(token.refreshToken,tokens.token); client.close()
    }

}
