package fsm

import kotlinx.serialization.Serializable

@Serializable data class Employee(val id: String, val displayName: String, val login: String)
@Serializable data class Site(val id: String, val name: String, val address: String)
@Serializable data class Equipment(val id: String, val siteId: String, val name: String, val inventoryNumber: String)
@Serializable data class ChecklistItem(val id: String, val position: Int, val text: String, val required: Boolean)
@Serializable data class MeasurementSpec(val key: String, val label: String, val unit: String, val required: Boolean, val min: String?, val max: String?)
@Serializable data class Answer(val itemId: String, val checked: Boolean)
@Serializable data class Reading(val key: String, val value: String)
@Serializable enum class LocationStatus { CAPTURED, MISSING }
@Serializable enum class LocationReason { DENIED, TIMEOUT, PROVIDER_DISABLED, UNAVAILABLE }
@Serializable data class Location(val status: LocationStatus, val reason: LocationReason?, val latitude: Double?, val longitude: Double?, val accuracyMeters: Double?, val capturedAt: String?)
@Serializable data class Photo(val id: String, val serverPhotoId: String, val mimeType: String, val byteSize: Long, val sha256: String, val createdAt: String, val contentPath: String)
@Serializable data class Result(val id: String, val answers: List<Answer>, val readings: List<Reading>, val comment: String, val location: Location, val capturedAt: String, val photos: List<Photo>)
@Serializable enum class OrderStatus { ASSIGNED, CLOSED }
@Serializable data class WorkOrder(val id: String, val equipmentId: String, val assigneeId: String, val title: String, val description: String, val dueAt: String?, val status: OrderStatus, val revision: Long, val checklistItems: List<ChecklistItem>, val measurementSpecs: List<MeasurementSpec>, val result: Result?)
@Serializable data class ReferenceData(val employee: Employee, val sites: List<Site>, val equipment: List<Equipment>)
@Serializable data class WorkOrderList(val items: List<WorkOrder>)
@Serializable data class Snapshot(val generatedAt: String, val employee: Employee, val sites: List<Site>, val equipment: List<Equipment>, val workOrders: List<WorkOrder>)
@Serializable data class LoginRequest(val login: String, val password: String)
@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class TokenResponse(val accessToken: String, val accessExpiresAt: String, val refreshToken: String, val refreshExpiresAt: String, val employee: Employee)
@Serializable data class PhotoMetadata(val photoId: String, val mimeType: String, val byteSize: Long, val sha256: String)
@Serializable data class UploadReceipt(val operationId: String, val photo: Photo)
@Serializable data class ResultWrite(val id: String, val answers: List<Answer>, val readings: List<Reading>, val comment: String, val location: Location, val capturedAt: String, val photoIds: List<String>)
@Serializable data class CloseRequest(val baseRevision: Long, val result: ResultWrite)
@Serializable data class CloseReceipt(val operationId: String, val workOrder: WorkOrder)
