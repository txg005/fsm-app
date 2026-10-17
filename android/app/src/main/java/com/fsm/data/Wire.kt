package com.fsm.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.UUID

val wire = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
fun uuid(): String = UUID.randomUUID().toString()
fun now(): String = instant(System.currentTimeMillis())
fun instant(ms: Long): String = DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(Instant.ofEpochMilli(ms))
@Serializable data class EmployeeDto(val id: String, val displayName: String, val login: String)
@Serializable data class SiteDto(val id: String, val name: String, val address: String)
@Serializable data class EquipmentDto(val id: String, val siteId: String, val name: String, val inventoryNumber: String)
@Serializable data class ChecklistItemDto(val id: String, val position: Int, val text: String, val required: Boolean)
@Serializable data class MeasurementSpecDto(val key: String, val label: String, val unit: String, val required: Boolean, val min: String?, val max: String?)
@Serializable data class AnswerDto(val itemId: String, val checked: Boolean)
@Serializable data class ReadingDto(val key: String, val value: String)
@Serializable data class LocationDto(val status: String, val reason: String?, val latitude: Double?, val longitude: Double?, val accuracyMeters: Double?, val capturedAt: String?) {
    companion object { fun missing(reason: String) = LocationDto("MISSING", reason, null, null, null, null) }
}
@Serializable data class PhotoDto(val id: String, val serverPhotoId: String, val mimeType: String, val byteSize: Long, val sha256: String, val createdAt: String, val contentPath: String)
@Serializable data class ResultDto(val id: String, val answers: List<AnswerDto>, val readings: List<ReadingDto>, val comment: String, val location: LocationDto, val capturedAt: String, val photos: List<PhotoDto>)
@Serializable data class WorkOrderDto(val id: String, val equipmentId: String, val assigneeId: String, val title: String, val description: String, val dueAt: String?, val status: String, val revision: Long, val checklistItems: List<ChecklistItemDto>, val measurementSpecs: List<MeasurementSpecDto>, val result: ResultDto?)
@Serializable data class SnapshotDto(val generatedAt: String, val employee: EmployeeDto, val sites: List<SiteDto>, val equipment: List<EquipmentDto>, val workOrders: List<WorkOrderDto>)
@Serializable data class LoginRequest(val login: String, val password: String)
@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class TokenResponse(val accessToken: String, val accessExpiresAt: String, val refreshToken: String, val refreshExpiresAt: String, val employee: EmployeeDto)
@Serializable data class PhotoUploadMetadata(val photoId: String, val mimeType: String, val byteSize: Long, val sha256: String)
@Serializable data class PhotoUploadReceipt(val operationId: String, val photo: PhotoDto)
@Serializable data class ResultWriteDto(val id: String, val answers: List<AnswerDto>, val readings: List<ReadingDto>, val comment: String, val location: LocationDto, val capturedAt: String, val photoIds: List<String>)
@Serializable data class CloseRequest(val baseRevision: Long, val result: ResultWriteDto)
@Serializable data class CloseReceipt(val operationId: String, val workOrder: WorkOrderDto)
@Serializable data class ErrorDetail(val code: String, val message: String, val retryable: Boolean, val details: JsonObject)
@Serializable data class ErrorBody(val error: ErrorDetail, val requestId: String)

class InvalidInspection(val problems:List<String>):Exception()

object Validation {
    private val decimal = Regex("^-?(0|[1-9][0-9]*)(\\.[0-9]{1,4})?$")
    fun validDecimal(value: String): Boolean = decimal.matches(value) && runCatching { BigDecimal(value).precision() <= 12 }.getOrDefault(false)
    fun errors(order: WorkOrderDto, result: ResultWriteDto): List<String> = buildList {
        if (result.comment.contains('\u0000')) add("Комментарий: недопустимый нулевой символ")
        if (result.comment.codePointCount(0, result.comment.length) > 4000) add("Комментарий: максимум 4000 символов")
        if (result.answers.map { it.itemId }.distinct().size != result.answers.size) add("Повтор пункта")
        if (result.answers.any { a -> order.checklistItems.none { it.id == a.itemId } }) add("Неизвестный пункт")
        order.checklistItems.filter { it.required }.forEach { item ->
            if (result.answers.none { it.itemId == item.id && it.checked }) add("Обязательный пункт: ${item.text}")
        }
        if (result.readings.map { it.key }.distinct().size != result.readings.size) add("Повтор показания")
        if (result.readings.any { r -> order.measurementSpecs.none { it.key == r.key } }) add("Неизвестное показание")
        order.measurementSpecs.forEach { spec ->
            val value = result.readings.find { it.key == spec.key }?.value
            if (value == null) { if (spec.required) add("Требуется ${spec.label}") }
            else if (!validDecimal(value)) add("${spec.label}: десятичное число, до 4 знаков после точки и 12 значащих цифр")
            else {
                val number = BigDecimal(value)
                if (spec.min != null && number < BigDecimal(spec.min) || spec.max != null && number > BigDecimal(spec.max)) add("${spec.label}: вне диапазона")
            }
        }
        if (result.photoIds.size > 10) add("Максимум 10 фото")
    }
}
