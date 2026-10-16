package com.fsm.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="employees") data class Employee(@PrimaryKey val id: String, val json: String)
@Entity(tableName="sites") data class Site(@PrimaryKey val id: String, val json: String)
@Entity(tableName="equipment", foreignKeys=[ForeignKey(entity=Site::class,parentColumns=["id"],childColumns=["siteId"])], indices=[Index("siteId")])
data class Equipment(@PrimaryKey val id: String, val siteId: String, val json: String)
@Entity(tableName="work_orders", foreignKeys=[ForeignKey(entity=Equipment::class,parentColumns=["id"],childColumns=["equipmentId"]),ForeignKey(entity=Employee::class,parentColumns=["id"],childColumns=["assigneeId"])], indices=[Index("equipmentId"),Index("assigneeId")])
data class WorkOrder(@PrimaryKey val id: String, val equipmentId: String, val assigneeId: String, val json: String, val available: Boolean=true)
@Entity(tableName="checklist_items", foreignKeys=[ForeignKey(entity=WorkOrder::class,parentColumns=["id"],childColumns=["workOrderId"],onDelete=ForeignKey.CASCADE)], indices=[Index("workOrderId")])
data class ChecklistItem(@PrimaryKey val id: String, val workOrderId: String, val position: Int, val json: String)
@Entity(tableName="inspection_results", foreignKeys=[ForeignKey(entity=WorkOrder::class,parentColumns=["id"],childColumns=["workOrderId"],onDelete=ForeignKey.CASCADE)], indices=[Index(value=["workOrderId"],unique=true)])
data class InspectionResult(@PrimaryKey val id: String, val workOrderId: String, val lifecycle: String, val baseRevision: Long, val json: String)
@Entity(tableName="photos", foreignKeys=[ForeignKey(entity=WorkOrder::class,parentColumns=["id"],childColumns=["workOrderId"]),ForeignKey(entity=InspectionResult::class,parentColumns=["id"],childColumns=["resultId"],onDelete=ForeignKey.CASCADE),ForeignKey(entity=Employee::class,parentColumns=["id"],childColumns=["ownerId"])], indices=[Index("workOrderId"),Index("resultId"),Index("ownerId"),Index(value=["serverPhotoId"],unique=true)])
data class Photo(@PrimaryKey val id: String, val workOrderId: String, val resultId: String, val ownerId: String, val localPath: String?, val byteSize: Long, val sha256: String, val createdAt: String, val serverPhotoId: String?=null, val contentPath: String?=null, val position: Int=0)
@Entity(tableName="outbox", indices=[Index("workOrderId"),Index(value=["photoId"],unique=true)])
data class Operation(@PrimaryKey val id: String, val kind: String, val workOrderId: String, val photoId: String?=null, val sequence: Long, val state: String="PENDING", val frozenPayload: String, val wireBody: String?=null, val dependencies: String="[]", val attempts: Int=0, val nextAttemptAt: Long=0, val error: String?=null)
@Entity(tableName="app_logs") data class AppLog(@PrimaryKey val id: String, val at: Long, val level: String, val event: String, val operationId: String?, val requestId: String?, val message: String)
@Entity(tableName="server_shadow") data class Shadow(@PrimaryKey val id: String, val json: String)
@Entity(tableName="sync_state", indices=[Index(value=["employeeId"],unique=true)]) data class SyncState(@PrimaryKey val id: String, val employeeId: String, val lastMetadataSyncAt: String)

@Dao interface Store {
    @Query("SELECT * FROM work_orders ORDER BY id") fun orders(): Flow<List<WorkOrder>>
    @Query("SELECT * FROM inspection_results") fun results(): Flow<List<InspectionResult>>
    @Query("SELECT * FROM photos ORDER BY position,id") fun photosFlow(): Flow<List<Photo>>
    @Query("SELECT * FROM outbox ORDER BY sequence") fun operationsFlow(): Flow<List<Operation>>
    @Query("SELECT * FROM app_logs ORDER BY at DESC,id DESC") fun logs(): Flow<List<AppLog>>
    @Query("SELECT * FROM work_orders") suspend fun allOrders(): List<WorkOrder>
    @Query("SELECT * FROM work_orders WHERE id=:id") suspend fun order(id:String): WorkOrder?
    @Query("SELECT * FROM inspection_results WHERE workOrderId=:id") suspend fun result(id:String): InspectionResult?
    @Query("SELECT * FROM photos") suspend fun allPhotos(): List<Photo>
    @Query("SELECT * FROM photos WHERE workOrderId=:id ORDER BY position,id") suspend fun photos(id:String): List<Photo>
    @Query("SELECT * FROM photos WHERE id=:id") suspend fun photo(id:String): Photo?
    @Query("SELECT * FROM outbox ORDER BY sequence") suspend fun operations(): List<Operation>
    @Query("SELECT * FROM server_shadow WHERE id=:id") suspend fun shadow(id:String): Shadow?
    @Upsert suspend fun employee(x:Employee)
    @Upsert suspend fun site(x:Site)
    @Upsert suspend fun equipment(x:Equipment)
    @Upsert suspend fun order(x:WorkOrder)
    @Upsert suspend fun item(x:ChecklistItem)
    @Upsert suspend fun result(x:InspectionResult)
    @Upsert suspend fun photo(x:Photo)
    @Upsert suspend fun operation(x:Operation)
    @Upsert suspend fun shadow(x:Shadow)
    @Upsert suspend fun syncState(x:SyncState)
    @Insert suspend fun log(x:AppLog)
    @Query("DELETE FROM app_logs WHERE at < :cutoff OR id NOT IN (SELECT id FROM app_logs ORDER BY at DESC,id DESC LIMIT 1000)") suspend fun prune(cutoff:Long)
    @Query("DELETE FROM inspection_results WHERE workOrderId=:id") suspend fun deleteResult(id:String)
    @Query("DELETE FROM photos WHERE id=:id") suspend fun deletePhoto(id:String)
    @Query("DELETE FROM outbox WHERE workOrderId=:id") suspend fun deleteOperations(id:String)
    @Query("DELETE FROM work_orders WHERE id=:id") suspend fun deleteOrder(id:String)
    @Query("UPDATE outbox SET state='PENDING' WHERE state='RUNNING'") suspend fun recover()
    @Query("UPDATE outbox SET state='PENDING',nextAttemptAt=0 WHERE state='AUTH_REQUIRED'") suspend fun resumeAuth()
}
@Database(entities=[Employee::class,Site::class,Equipment::class,WorkOrder::class,ChecklistItem::class,InspectionResult::class,Photo::class,Operation::class,AppLog::class,Shadow::class,SyncState::class],version=1,exportSchema=true)
abstract class FsmDatabase: RoomDatabase() { abstract fun store(): Store }
