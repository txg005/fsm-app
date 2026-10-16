package fsm

import kotlinx.serialization.json.*
import java.sql.Connection

data class StoredResponse(val status: Int, val body: String)
// Fault hooks are in-process test seams only, never request parameters or environment switches.
class Operations(private val db: Database, private val storage: PhotoStorage, val afterRename: () -> Unit = {}, val beforeReceipt: () -> Unit = {}) {
    fun accessible(employee: String, order: String) { db.tx { it.order(employee,order) } }
    private fun receipt(c: Connection, employee: String, operation: String, path: String, hash: String): StoredResponse? {
        c.lock("operation:$employee:$operation")
        return c.query("SELECT * FROM idempotency_receipts WHERE employee_id=? AND operation_id=?",id(employee),id(operation)) { r ->
            if(r.getString("method")!="POST" || r.getString("path")!=path || r.getString("semantic_hash")!=hash) conflict("IDEMPOTENCY_KEY_REUSED")
            StoredResponse(r.getInt("status"),r.getString("body"))
        }.singleOrNull()
    }
    private fun store(c: Connection, employee: String, operation: String, path: String, hash: String, response: StoredResponse): StoredResponse {
        beforeReceipt()
        c.exec("INSERT INTO idempotency_receipts VALUES (?,?,'POST',?,?,?,?)",id(employee),id(operation),path,hash,response.status,response.body)
        return response
    }
    private fun closed(order: WorkOrder) {
        if(order.status==OrderStatus.CLOSED) throw ApiError(409,"ORDER_CLOSED",buildJsonObject { put("currentRevision",order.revision) })
    }
    fun upload(employee: String, orderId: String, operation: String, upload: Upload): StoredResponse {
        uuid(operation); validatePhoto(upload.metadata,upload.bytes)
        val path="/api/v1/work-orders/$orderId/photos"; val hash=uploadHash(path,upload.metadata)
        return db.tx { c ->
            // Recheck current authorization before returning a historical receipt.
            c.order(employee,orderId)
            val existingReceipt=receipt(c,employee,operation,path,hash)
            val order=c.order(employee,orderId,true)
            if(existingReceipt!=null) return@tx existingReceipt
            closed(order)
            val m=upload.metadata
            c.lock("photo:${m.photoId}")
            val existing = c.query("SELECT * FROM photos WHERE id=?",id(m.photoId)) { r ->
                if(r.getString("owner_id")!=employee) notFound()
                if(r.getString("work_order_id")!=orderId || r.getString("sha256")!=m.sha256 || r.getLong("byte_size")!=m.byteSize) conflict("PHOTO_ID_REUSED")
                r.photo()
            }.singleOrNull()
            val key=storage.durable(m,upload.bytes)
            afterRename()
            val photo=existing ?: Photo(m.photoId,newId(),"image/jpeg",m.byteSize,m.sha256,now(),"").let { it.copy(contentPath="/api/v1/photos/${it.serverPhotoId}/content") }.also { p ->
                c.exec("INSERT INTO photos(id,server_photo_id,work_order_id,owner_id,mime_type,byte_size,sha256,created_at,storage_key,state) VALUES (?,?,?,?,'image/jpeg',?,?,?::timestamptz,?,'STAGED')",id(p.id),id(p.serverPhotoId),id(orderId),id(employee),p.byteSize,p.sha256,p.createdAt,key)
            }
            store(c,employee,operation,path,hash,StoredResponse(201,encode(UploadReceipt(operation,photo))))
        }
    }
    fun close(employee: String, orderId: String, operation: String, bytes: ByteArray): StoredResponse {
        uuid(operation)
        val request=decode<CloseRequest>(bytes)
        // Structural UUID/range checks are independent of current business state; semantic checks follow replay.
        uuid(request.result.id); request.result.answers.forEach { uuid(it.itemId) }; request.result.photoIds.forEach(::uuid)
        if(request.baseRevision !in 1..9007199254740991L || !validInstant(request.result.capturedAt)) malformed()
        val path="/api/v1/work-orders/$orderId/close"; val hash=closeHash(path,bytes)
        return db.tx { c ->
            c.order(employee,orderId)
            val existing=receipt(c,employee,operation,path,hash)
            val order=c.order(employee,orderId,true)
            if(existing!=null) return@tx existing
            closed(order)
            if(order.revision!=request.baseRevision) throw ApiError(409,"REVISION_CONFLICT",buildJsonObject { put("currentRevision",order.revision) })
            validateClose(request,order)
            val v=request.result
            c.lock("result:${v.id}")
            if(c.query("SELECT id FROM inspection_results WHERE id=?",id(v.id)) { true }.isNotEmpty()) conflict("RESULT_ID_REUSED")
            val missing=v.photoIds.filter { photo ->
                c.query("SELECT id FROM photos WHERE server_photo_id=? AND owner_id=? AND work_order_id=? AND result_id IS NULL AND state='STAGED' FOR UPDATE",id(photo),id(employee),id(orderId)) { true }.isEmpty()
            }
            if(missing.isNotEmpty()) throw ApiError(422,"PHOTO_MISSING",buildJsonObject { put("photoIds",buildJsonArray { missing.forEach { add(it) } }) })
            c.exec("INSERT INTO inspection_results VALUES (?,?,?::jsonb,?::jsonb,?,?::jsonb,?::timestamptz)",id(v.id),id(orderId),encode(v.answers),encode(v.readings),v.comment,encode(v.location),v.capturedAt)
            v.photoIds.forEachIndexed { index, photo -> c.exec("UPDATE photos SET result_id=?,position=?,state='ATTACHED' WHERE server_photo_id=?",id(v.id),index,id(photo)) }
            c.exec("UPDATE work_orders SET status='CLOSED',revision=revision+1 WHERE id=?",id(orderId))
            store(c,employee,operation,path,hash,StoredResponse(200,encode(CloseReceipt(operation,c.order(employee,orderId)))))
        }
    }
    fun content(employee: String, serverPhotoId: String): Pair<Photo,java.nio.file.Path> = db.tx { c ->
        c.query("SELECT p.* FROM photos p JOIN work_orders w ON w.id=p.work_order_id WHERE p.server_photo_id=? AND p.owner_id=? AND w.assignee_id=?",id(serverPhotoId),id(employee),id(employee)) { r -> r.photo() to storage.path(r.getString("storage_key")) }.singleOrNull() ?: notFound()
    }
}
