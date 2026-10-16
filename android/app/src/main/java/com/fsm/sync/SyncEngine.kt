package com.fsm.sync

import androidx.room.withTransaction
import com.fsm.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.io.IOException
import java.security.MessageDigest
import kotlin.random.Random

object SyncGate { val mutex=Mutex() }
fun sha256(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
object RetryPolicy {
    fun transient(status:Int)=status==408 || status==429 || status>=500
    fun delayMillis(attempt:Int,jitter:Long=Random.nextLong(1000)):Long=(2000L shl (attempt-1).coerceIn(0,8)).coerceAtMost(300000)+jitter
    fun state(status:Int)=when { status==401->"AUTH_REQUIRED"; status==409->"CONFLICT"; transient(status)->"RETRY_WAIT"; else->"FAILED" }
}
fun readyOperation(all:List<Operation>, at:Long, perRun:Map<String,Int>):Operation? = all.firstOrNull {
    it.state in listOf("PENDING","RETRY_WAIT") && it.nextAttemptAt<=at && (perRun[it.id] ?: 0)<5 &&
        wire.decodeFromString<List<String>>(it.dependencies).all { dep -> all.any { op -> op.id==dep && op.state=="ACKED" } }
}
class SyncEngine(private val repo:Repository,private val isActiveAccount:()->Boolean={true}) {
    suspend fun run(progress:(Int)->Unit={}) = SyncGate.mutex.withLock {
        if(!isActiveAccount()) return@withLock
        val store=repo.store
        store.recover(); store.resumeAuth()
        repo.logger.record(Event.SYNC_STARTED)
        val started=System.currentTimeMillis(); var sent=0
        val perRun=mutableMapOf<String,Int>()
        try {
            while(System.currentTimeMillis()-started<600000) {
                currentCoroutineContext().ensureActive()
                if(!isActiveAccount()) break
                val all=store.operations()
                val op=readyOperation(all,System.currentTimeMillis(),perRun) ?: break
                val order=store.order(op.workOrderId)
                if(order?.available!=true) { store.operation(op.copy(state="FAILED",error="ACCESS_REVOKED")); continue }
                store.operation(op.copy(state="RUNNING"))
                perRun[op.id]=(perRun[op.id] ?: 0)+1
                try {
                    if(op.kind=="UPLOAD_PHOTO") upload(op) else close(op)
                    sent++; progress(sent); repo.logger.record(Event.OP_ACK,op.id)
                } catch(e:CancellationException) { throw e }
                catch(e:Exception) {
                    val api=e as? ApiFailure
                    val state= if(api!=null) RetryPolicy.state(api.status) else if(e is IOException || e is io.ktor.client.plugins.HttpRequestTimeoutException) "RETRY_WAIT" else "FAILED"
                    val code=api?.code ?: if(state=="RETRY_WAIT") "NETWORK_UNAVAILABLE" else "LOCAL_INTEGRITY_ERROR"
                    val latest=store.operations().first { it.id==op.id }
                    val attempts=op.attempts+1
                    store.operation(latest.copy(state=state,attempts=attempts,error=code,nextAttemptAt=System.currentTimeMillis()+maxOf(RetryPolicy.delayMillis(attempts),api?.retryAfter ?: 0)))
                    if(state=="CONFLICT") {
                        // The failed dependency must not leave its close indefinitely pending.
                        store.operations().filter { it.workOrderId==op.workOrderId && it.id!=op.id && it.state in listOf("PENDING","RETRY_WAIT") }.forEach { store.operation(it.copy(state="CONFLICT",error=code)) }
                        repo.markConflict(op.workOrderId)
                    } else if(state=="FAILED") {
                        store.operations().filter { it.workOrderId==op.workOrderId && it.id!=op.id && it.state in listOf("PENDING","RETRY_WAIT") }.forEach { store.operation(it.copy(state="FAILED",error=code)) }
                    }
                    repo.logger.record(if(state=="RETRY_WAIT") Event.OP_RETRY else Event.OP_BLOCKED,op.id)
                    if(state=="AUTH_REQUIRED") break
                }
            }
            // Replay happens first: a prior committed close must not become a false revision conflict.
            try { repo.snapshot() } catch(e:CancellationException) { throw e } catch(_:Exception) { repo.logger.record(Event.OP_BLOCKED) }
            repo.logger.record(Event.SYNC_FINISHED)
        } finally { withContext(NonCancellable) { store.recover() } }
    }
    private suspend fun upload(op:Operation) {
        val photo=requireNotNull(repo.store.photo(requireNotNull(op.photoId)))
        val file=requireNotNull(photo.localPath?.let(repo::ownedFile)); val bytes=withContext(Dispatchers.IO) { file.readBytes() }
        check(bytes.size.toLong()==photo.byteSize && sha256(bytes)==photo.sha256)
        val metadata=wire.decodeFromString<PhotoUploadMetadata>(op.frozenPayload)
        val receipt=repo.api.upload(op.workOrderId,op.id,metadata,bytes)
        check(receipt.operationId==op.id && receipt.photo.id==photo.id && receipt.photo.sha256==photo.sha256 && receipt.photo.byteSize==photo.byteSize && Api.validContentPath(receipt.photo))
        repo.db.withTransaction {
            repo.store.photo(photo.copy(serverPhotoId=receipt.photo.serverPhotoId,contentPath=receipt.photo.contentPath))
            repo.store.operation(op.copy(state="ACKED"))
        }
    }
    private suspend fun close(op:Operation) {
        val body=repo.db.withTransaction {
            val current=repo.store.operations().first { it.id==op.id }
            current.wireBody ?: kotlin.run {
                val frozen=wire.decodeFromString<CloseRequest>(op.frozenPayload)
                val remote=frozen.result.photoIds.map { id -> requireNotNull(repo.store.photo(id)?.serverPhotoId) }
                val final=wire.encodeToString(frozen.copy(result=frozen.result.copy(photoIds=remote)))
                check(final.toByteArray(Charsets.UTF_8).size<=262144)
                repo.store.operation(current.copy(wireBody=final)); final
            }
        }
        val receipt=repo.api.close(op.workOrderId,op.id,body)
        check(receipt.operationId==op.id && receipt.workOrder.id==op.workOrderId && receipt.workOrder.status=="CLOSED")
        repo.db.withTransaction {
            repo.applyCanonical(receipt.workOrder)
            repo.store.operation(repo.store.operations().first { it.id==op.id }.copy(state="ACKED"))
        }
    }
}
