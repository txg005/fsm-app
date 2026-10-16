package com.fsm.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.Locale

class ApiFailure(val status: Int, val code: String, val retryAfter: Long=0): Exception(code)
interface RefreshStore { fun read(): String?; fun write(token:String) }
class Api(private val client: HttpClient, private val origin: String, private val tokens: RefreshStore?, private val accountId:String?) {
    @Volatile private var access: String? = null
    private val refreshLock = Mutex()
    init { val u=Url(origin); require(u.encodedPath in listOf("", "/") && u.user.isNullOrEmpty() && u.password.isNullOrEmpty() && u.parameters.isEmpty()); require(u.protocol==URLProtocol.HTTPS || com.fsm.BuildConfig.DEBUG) }
    fun accept(token:TokenResponse) { require(accountId==null || accountId==token.employee.id); access=token.accessToken; tokens?.write(token.refreshToken) }
    suspend fun login(login:String,password:String): TokenResponse {
        val response = raw("/api/v1/auth/login", HttpMethod.Post, wire.encodeToString(LoginRequest(login.lowercase(Locale.ROOT),password)), null)
        return wire.decodeFromString(checked(response))
    }
    private suspend fun refresh(stale:String?) = refreshLock.withLock {
        if (access != stale && access != null) return@withLock
        val refresh=tokens?.read() ?: throw ApiFailure(401,"AUTH_REQUIRED")
        val response=raw("/api/v1/auth/refresh",HttpMethod.Post,wire.encodeToString(RefreshRequest(refresh)),null)
        val token=wire.decodeFromString<TokenResponse>(checked(response))
        require(token.employee.id==accountId)
        accept(token)
    }
    private suspend fun raw(path:String, method:HttpMethod, body:String?, key:String?, multipart:MultiPartFormDataContent?=null, bearer:String?=null):HttpResponse = client.request(origin.trimEnd('/')+path) {
        this.method=method
        if (bearer!=null) bearerAuth(bearer)
        if(key!=null) header("Idempotency-Key",key)
        if(multipart!=null) setBody(multipart)
        else if(body!=null) { contentType(ContentType.Application.Json); setBody(body) }
    }
    private suspend fun checked(response:HttpResponse):String {
        val body=response.bodyAsText()
        if(response.status.value !in 200..299) {
            val parsed=runCatching { wire.decodeFromString<ErrorBody>(body).error.code }.getOrDefault("PROTOCOL_ERROR")
            val error=parsed.takeIf { it in safeCodes } ?: "PROTOCOL_ERROR"
            throw ApiFailure(response.status.value,error,(response.headers["Retry-After"]?.toLongOrNull() ?: 0).coerceIn(0,86400)*1000)
        }
        return body
    }
    private suspend fun authorized(path:String,method:HttpMethod=HttpMethod.Get,body:String?=null,key:String?=null,multipart:MultiPartFormDataContent?=null):HttpResponse {
        if(access==null) refresh(null)
        val old=access
        var response=raw(path,method,body,key,multipart,old)
        if(response.status.value==401) { response.bodyAsText(); refresh(old); response=raw(path,method,body,key,multipart,access) }
        return response
    }
    suspend fun snapshot():SnapshotDto = wire.decodeFromString(checked(authorized("/api/v1/sync/snapshot")))
    suspend fun order(id:String):WorkOrderDto = wire.decodeFromString(checked(authorized("/api/v1/work-orders/$id")))
    suspend fun close(id:String,key:String,bytes:String):CloseReceipt = wire.decodeFromString(checked(authorized("/api/v1/work-orders/$id/close",HttpMethod.Post,bytes,key)))
    suspend fun upload(id:String,key:String,meta:PhotoUploadMetadata,bytes:ByteArray):PhotoUploadReceipt {
        val parts=MultiPartFormDataContent(formData {
            append("metadata",wire.encodeToString(meta),Headers.build { append(HttpHeaders.ContentType,"application/json") })
            append("file",bytes,Headers.build { append(HttpHeaders.ContentType,"image/jpeg"); append(HttpHeaders.ContentDisposition,"filename=\"photo.jpg\"") })
        })
        return wire.decodeFromString(checked(authorized("/api/v1/work-orders/$id/photos",HttpMethod.Post,key=key,multipart=parts)))
    }
    suspend fun photo(photo:PhotoDto):ByteArray {
        require(validContentPath(photo))
        val response=authorized(photo.contentPath)
        if(response.status.value!=200) checked(response)
        return response.readRawBytes()
    }
    fun clearAccess() { access=null }
    suspend fun logout() { val token=tokens?.read(); if(token!=null) checked(raw("/api/v1/auth/logout",HttpMethod.Post,wire.encodeToString(RefreshRequest(token)),null)); access=null }
    companion object {
        val safeCodes=setOf("MALFORMED_REQUEST","INVALID_CREDENTIALS","AUTH_REQUIRED","SESSION_EXPIRED","NOT_FOUND","REQUEST_TIMEOUT","REVISION_CONFLICT","ORDER_CLOSED","PHOTO_ID_REUSED","RESULT_ID_REUSED","IDEMPOTENCY_KEY_REUSED","PHOTO_TOO_LARGE","REQUEST_TOO_LARGE","VALIDATION_ERROR","PHOTO_CONTENT_INVALID","PHOTO_MISSING","RATE_LIMITED","INTERNAL_ERROR","SERVICE_UNAVAILABLE")
        fun validContentPath(photo:PhotoDto)= Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(photo.serverPhotoId) && photo.contentPath=="/api/v1/photos/${photo.serverPhotoId}/content"
        fun client()=HttpClient(io.ktor.client.engine.okhttp.OkHttp) { followRedirects=false; install(HttpTimeout) { requestTimeoutMillis=60000; connectTimeoutMillis=15000; socketTimeoutMillis=30000 } }
    }
}
