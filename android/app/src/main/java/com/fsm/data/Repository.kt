package com.fsm.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File

class Repository(val db:FsmDatabase,val api:Api,val employeeId:String,val files:File) {
    val store=db.store()
    val logger=SafeLogger(db)
    suspend fun create(orderId:String) = db.withTransaction {
        val row=requireNotNull(store.order(orderId)); val order=wire.decodeFromString<WorkOrderDto>(row.json)
        check(row.available && order.status=="ASSIGNED")
        if(store.result(orderId)==null) {
            val draft=ResultWriteDto(uuid(),emptyList(),emptyList(),"",LocationDto.missing("UNAVAILABLE"),now(),emptyList())
            store.result(InspectionResult(draft.id,orderId,"DRAFT",order.revision,wire.encodeToString(draft)))
        }
    }
    suspend fun save(orderId:String, content:ResultWriteDto) = db.withTransaction {
        val current=requireNotNull(store.result(orderId)); check(current.lifecycle=="DRAFT" && current.id==content.id)
        store.result(current.copy(json=wire.encodeToString(content.copy(photoIds=emptyList()))))
    }
    suspend fun deleteDraft(orderId:String) {
        val paths=db.withTransaction {
            val current=requireNotNull(store.result(orderId)); check(current.lifecycle=="DRAFT")
            val ops=store.operations().filter { it.workOrderId==orderId }
            check(ops.none { it.state in listOf("RUNNING","PENDING","RETRY_WAIT","AUTH_REQUIRED") })
            val paths=store.photos(orderId).mapNotNull { it.localPath }
            store.deleteOperations(orderId); store.deleteResult(orderId); paths
        }
        withContext(Dispatchers.IO) { paths.forEach { ownedFile(it)?.delete() } }
    }
    fun ownedFile(path:String):File? { val file=File(path); return file.takeIf { it.canonicalPath.startsWith(files.canonicalPath+File.separator) } }
    suspend fun addPhoto(orderId:String,file:File,id:String,hash:String) {
        try {
            db.withTransaction {
                val result=requireNotNull(store.result(orderId)); check(result.lifecycle=="DRAFT")
                val photos=store.photos(orderId); check(photos.size<10)
                check(file.length() in 1..10485760)
                store.photo(Photo(id,orderId,result.id,employeeId,file.absolutePath,file.length(),hash,now(),position=(photos.maxOfOrNull { it.position } ?: -1)+1))
            }
        } catch(e:Exception) { file.delete(); throw e }
    }
    suspend fun cleanupOrphans() = Media.filesMutex.withLock {
        val referenced=store.allPhotos().mapNotNull { it.localPath?.let(::ownedFile)?.canonicalPath }.toSet()
        withContext(Dispatchers.IO) {
            // Only this account's app-generated UUID files; no sweeping registered queued/ACKed photos.
            val generated=Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.(jpg|tmp|download)")
            files.listFiles()?.filter { it.isFile && generated.matches(it.name) && it.canonicalPath !in referenced }?.forEach { it.delete() }
        }
    }
    suspend fun cachePhoto(id:String) = Media.filesMutex.withLock {
        val photo=requireNotNull(store.photo(id))
        if(photo.localPath?.let(::ownedFile)?.exists()==true) return@withLock
        val remote=PhotoDto(photo.id,requireNotNull(photo.serverPhotoId),"image/jpeg",photo.byteSize,photo.sha256,photo.createdAt,requireNotNull(photo.contentPath))
        val bytes=api.photo(remote)
        check(bytes.size.toLong()==photo.byteSize && bytes.size<=10485760 && com.fsm.sync.sha256(bytes)==photo.sha256)
        val target=File(files,"${photo.id}.jpg"); val temp=File(files,"${photo.id}.download")
        withContext(Dispatchers.IO) {
            try { java.io.FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }; check(temp.renameTo(target)) } finally {temp.delete()}
        }
        db.withTransaction {
            val current=store.photo(id)
            if(current!=null && current.serverPhotoId==photo.serverPhotoId) store.photo(current.copy(localPath=target.absolutePath)) else target.delete()
        }
    }
    suspend fun removePhoto(orderId:String,id:String) {
        val path=db.withTransaction {
            check(store.result(orderId)?.lifecycle=="DRAFT")
            val photo=requireNotNull(store.photo(id)); check(photo.workOrderId==orderId)
            store.deletePhoto(id); photo.localPath
        }
        if(path!=null) withContext(Dispatchers.IO) { ownedFile(path)?.delete() }
    }
    suspend fun enqueue(orderId:String,location:LocationDto) = db.withTransaction {
        val result=requireNotNull(store.result(orderId)); check(result.lifecycle=="DRAFT")
        val row=requireNotNull(store.order(orderId)); check(row.available)
        val order=wire.decodeFromString<WorkOrderDto>(row.json); check(order.status=="ASSIGNED")
        val photos=store.photos(orderId)
        val final=wire.decodeFromString<ResultWriteDto>(result.json).copy(location=location,capturedAt=now(),photoIds=photos.map { it.id })
        val errors=Validation.errors(order,final); if(errors.isNotEmpty()) throw InvalidInspection(errors)
        check(store.operations().none { it.workOrderId==orderId && it.kind=="CLOSE_WORK_ORDER" && it.state !in listOf("CANCELLED","ACKED") })
        var seq=(store.operations().maxOfOrNull { it.sequence } ?: 0)
        val deps=photos.filter { it.serverPhotoId==null }.map { photo ->
            val file=photo.localPath?.let(::ownedFile); check(file?.exists()==true)
            val op=Operation(uuid(),"UPLOAD_PHOTO",orderId,photo.id,++seq,frozenPayload=wire.encodeToString(PhotoUploadMetadata(photo.id,"image/jpeg",photo.byteSize,photo.sha256)))
            store.operation(op); op.id
        }
        store.result(result.copy(lifecycle="QUEUED",json=wire.encodeToString(final)))
        store.operation(Operation(uuid(),"CLOSE_WORK_ORDER",orderId,sequence=++seq,frozenPayload=wire.encodeToString(CloseRequest(result.baseRevision,final)),dependencies=wire.encodeToString(deps)))
    }
    suspend fun snapshot() {
        val snapshot=api.snapshot(); check(snapshot.employee.id==employeeId)
        db.withTransaction {
            store.employee(Employee(snapshot.employee.id,wire.encodeToString(snapshot.employee)))
            snapshot.sites.forEach { store.site(Site(it.id,wire.encodeToString(it))) }
            snapshot.equipment.forEach { store.equipment(Equipment(it.id,it.siteId,wire.encodeToString(it))) }
            val ids=snapshot.workOrders.map { it.id }.toSet()
            store.allOrders().filter { it.id !in ids }.forEach { old ->
                // Retain local work and hide pure cache. No cached draft is uploaded after access revocation.
                if(store.result(old.id)==null) store.deleteOrder(old.id)
                else store.order(old.copy(available=false))
            }
            snapshot.workOrders.forEach { canonical ->
                store.shadow(Shadow(canonical.id,wire.encodeToString(canonical)))
                val local=store.result(canonical.id)
                if(local==null || local.lifecycle=="CLOSED") applyCanonical(canonical)
                else {
                    val old=store.order(canonical.id)
                    if(old!=null) store.order(old.copy(available=true))
                    if(local.lifecycle=="DRAFT" && (canonical.status=="CLOSED" || canonical.revision!=local.baseRevision)) store.result(local.copy(lifecycle="CONFLICT"))
                }
            }
            store.syncState(SyncState(employeeId,employeeId,snapshot.generatedAt))
        }
        logger.record(Event.SNAPSHOT_OK)
    }
    // Called only in the encompassing transaction. Upsert preserves uploaded local photo paths.
    suspend fun applyCanonical(order:WorkOrderDto) {
        store.order(WorkOrder(order.id,order.equipmentId,order.assigneeId,wire.encodeToString(order)))
        store.shadow(Shadow(order.id,wire.encodeToString(order)))
        order.checklistItems.forEach { store.item(ChecklistItem(it.id,order.id,it.position,wire.encodeToString(it))) }
        order.result?.let { r ->
            val content=ResultWriteDto(r.id,r.answers,r.readings,r.comment,r.location,r.capturedAt,r.photos.map { it.id })
            store.result(InspectionResult(r.id,order.id,"CLOSED",order.revision,wire.encodeToString(content)))
            r.photos.forEachIndexed { position,p ->
                val old=store.photo(p.id)
                store.photo(Photo(p.id,order.id,r.id,employeeId,old?.localPath,p.byteSize,p.sha256,p.createdAt,p.serverPhotoId,p.contentPath,position))
            }
        }
    }
    // Durable state change precedes any network call. Clearing shadow is the freshness marker;
    // no schema change and no old cached canonical can authorize destructive resolution.
    suspend fun markConflict(orderId:String, failed:Operation?=null, code:String="REVISION_CONFLICT") = db.withTransaction {
        store.operations().filter { it.workOrderId==orderId && (it.id==failed?.id || it.state in listOf("RUNNING","PENDING","RETRY_WAIT","AUTH_REQUIRED")) }.forEach {
            store.operation(it.copy(state="CONFLICT",error=code,attempts=if(it.id==failed?.id) it.attempts+1 else it.attempts))
        }
        store.result(orderId)?.let { store.result(it.copy(lifecycle="CONFLICT")) }
        store.deleteShadow(orderId)
    }
    suspend fun recover() = db.withTransaction {
        store.recover()
        // Repair interrupted transitions from the earlier WIP as well as normal restart.
        store.operations().filter { it.state=="CONFLICT" }.map { it.workOrderId }.distinct().forEach { id ->
            if(store.result(id)?.lifecycle=="QUEUED") markConflict(id)
        }
    }
    suspend fun freshCanonical(orderId:String):String {
        store.deleteShadow(orderId)
        val canonical=api.order(orderId) // Authorized GET every time; failures preserve local result/outbox.
        val row=requireNotNull(store.order(orderId))
        ConflictPolicy.validate(canonical,orderId,employeeId,row.available)
        val json=wire.encodeToString(canonical)
        store.shadow(Shadow(orderId,json))
        return json
    }
    private suspend fun confirmedCanonical(orderId:String,shown:String?):WorkOrderDto {
        val fresh=freshCanonical(orderId)
        if(!ConflictPolicy.sameShown(shown,fresh)) throw CanonicalChanged(fresh)
        return wire.decodeFromString(fresh)
    }
    suspend fun returnToDraft(orderId:String,shown:String?=null) {
        val canonical=confirmedCanonical(orderId,shown)
        db.withTransaction {
            val local=requireNotNull(store.result(orderId))
            val operations=store.operations().filter { it.workOrderId==orderId }
            val correctable=operations.any { it.state=="FAILED" && it.error in listOf("MALFORMED_REQUEST","VALIDATION_ERROR","PHOTO_TOO_LARGE","REQUEST_TOO_LARGE","PHOTO_CONTENT_INVALID") }
            val conflict=local.lifecycle=="CONFLICT" && operations.none { it.error=="IDEMPOTENCY_KEY_REUSED" }
            check((correctable || conflict) && canonical.status=="ASSIGNED")
            ConflictPolicy.validate(canonical,orderId,employeeId,store.order(orderId)?.available==true)
            check(operations.none { it.state in listOf("RUNNING","PENDING","RETRY_WAIT","AUTH_REQUIRED") })
            store.deleteOperations(orderId)
            store.order(WorkOrder(canonical.id,canonical.equipmentId,canonical.assigneeId,wire.encodeToString(canonical)))
            store.result(local.copy(lifecycle="DRAFT",baseRevision=canonical.revision))
        }
    }
    suspend fun acceptServer(orderId:String,shown:String?=null) {
        val canonical=confirmedCanonical(orderId,shown)
        db.withTransaction {
            val local=requireNotNull(store.result(orderId)); check(local.lifecycle=="CONFLICT")
            val ops=store.operations().filter { it.workOrderId==orderId }
            check(ops.none { it.state in listOf("RUNNING","PENDING","RETRY_WAIT","AUTH_REQUIRED") })
            ConflictPolicy.validate(canonical,orderId,employeeId,store.order(orderId)?.available==true)
            store.deleteOperations(orderId); store.deleteResult(orderId); applyCanonical(canonical)
            // Orphan files are swept on explicit cache cleanup, never on an unconfirmed response.
        }
    }
}
class CanonicalChanged(val canonical:String):Exception()
object ConflictPolicy {
    fun validate(order:WorkOrderDto,id:String,employeeId:String,available:Boolean) {
        check(available && order.id==id && order.assigneeId==employeeId)
        check(order.revision>0 && ((order.status=="ASSIGNED" && order.result==null) || (order.status=="CLOSED" && order.result!=null)))
    }
    fun sameShown(shown:String?,fresh:String):Boolean = shown!=null &&
        wire.decodeFromString<WorkOrderDto>(shown)==wire.decodeFromString<WorkOrderDto>(fresh)
}
