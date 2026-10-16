package fsm

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import kotlinx.serialization.descriptors.*
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.UUID

val wire = Json { explicitNulls = true; encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }
private val strictFactory = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
val uuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
val instantPattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z")
private val formatter = DateTimeFormatterBuilder().appendInstant(3).toFormatter()
fun now(): String = timestamp(Instant.now())
fun timestamp(i: Instant): String = formatter.format(i)
fun newId(): String = UUID.randomUUID().toString()
fun uuid(s: String): String { if (!uuidPattern.matches(s)) malformed(); return s }
fun validInstant(s: String): Boolean = instantPattern.matches(s) && runCatching { Instant.parse(s) }.isSuccess
fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
fun closeHash(path: String, bytes: ByteArray) = sha("POST\n$path\n${sha(bytes)}".toByteArray())
fun uploadHash(path: String, m: PhotoMetadata) = sha("POST\n$path\n${m.photoId}\nimage/jpeg\n${m.byteSize}\n${m.sha256}".toByteArray())
fun strictJson(bytes: ByteArray) {
    try { strictFactory.createParser(bytes).use { p -> while (p.nextToken() != null) { /* force duplicate-key and UTF8 validation */ } } }
    catch (_: Exception) { malformed() }
}
inline fun <reified T> decode(bytes: ByteArray): T {
    strictJson(bytes)
    return try {
        val text=bytes.toString(Charsets.UTF_8)
        strictTypes(wire.parseToJsonElement(text), serializer<T>().descriptor)
        wire.decodeFromString<T>(text)
    } catch (_: Exception) { malformed() }
}
inline fun <reified T> encode(value: T) = wire.encodeToString(value)
class ApiError(val status: Int, val code: String, val details: JsonObject = buildJsonObject {}, val retryAfter: Int? = null) : RuntimeException(code)
fun malformed(): Nothing = throw ApiError(400, "MALFORMED_REQUEST")
fun notFound(): Nothing = throw ApiError(404, "NOT_FOUND")
fun conflict(code: String): Nothing = throw ApiError(409, code)
fun validation(path: String, code: String): Nothing = throw ApiError(422, "VALIDATION_ERROR", buildJsonObject {
    put("fields", buildJsonArray { add(buildJsonObject { put("path", path); put("code", code) }) })
})
fun decimal(s: String, path: String): BigDecimal {
    if (!Regex("-?(0|[1-9][0-9]*)(\\.[0-9]{1,4})?").matches(s)) validation(path, "INVALID_FORMAT")
    val n = s.toBigDecimalOrNull() ?: validation(path, "INVALID_FORMAT")
    if (n.precision() > 12) validation(path, "OUT_OF_RANGE")
    return n
}
fun validateClose(r: CloseRequest, order: WorkOrder) {
    val v = r.result
    uuid(v.id); v.answers.forEach { uuid(it.itemId) }; v.photoIds.forEach(::uuid)
    if (r.baseRevision !in 1..9007199254740991L) malformed()
    if (!validInstant(v.capturedAt)) malformed()
    if (v.comment.codePointCount(0, v.comment.length) > 4000) validation("result.comment", "TOO_LONG")
    if (v.photoIds.size > 10) validation("result.photoIds", "OUT_OF_RANGE")
    if (v.photoIds.toSet().size != v.photoIds.size) validation("result.photoIds", "DUPLICATE")
    if (v.answers.map { it.itemId }.toSet().size != v.answers.size) validation("result.answers", "DUPLICATE")
    if (v.readings.map { it.key }.toSet().size != v.readings.size) validation("result.readings", "DUPLICATE")
    v.answers.forEachIndexed { i, a -> if (order.checklistItems.none { it.id == a.itemId }) validation("result.answers[$i].itemId", "UNKNOWN_REFERENCE") }
    order.checklistItems.filter { it.required }.forEach { item -> if (v.answers.none { it.itemId == item.id && it.checked }) validation("result.answers", "REQUIRED") }
    v.readings.forEachIndexed { i, reading ->
        val spec = order.measurementSpecs.find { it.key == reading.key } ?: validation("result.readings[$i].key", "UNKNOWN_REFERENCE")
        val n = decimal(reading.value, "result.readings[$i].value")
        if (spec.min != null && n < spec.min.toBigDecimal() || spec.max != null && n > spec.max.toBigDecimal()) validation("result.readings[$i].value", "OUT_OF_RANGE")
    }
    order.measurementSpecs.filter { it.required }.forEach { spec -> if (v.readings.none { it.key == spec.key }) validation("result.readings", "REQUIRED") }
    val l = v.location
    val valid = when (l.status) {
        LocationStatus.MISSING -> l.reason != null && l.latitude == null && l.longitude == null && l.accuracyMeters == null && l.capturedAt == null
        LocationStatus.CAPTURED -> l.reason == null && l.latitude != null && l.latitude.isFinite() && l.latitude in -90.0..90.0 && l.longitude != null && l.longitude.isFinite() && l.longitude in -180.0..180.0 && l.accuracyMeters != null && l.accuracyMeters.isFinite() && l.accuracyMeters >= 0 && l.capturedAt != null && validInstant(l.capturedAt)
    }
    if (!valid) validation("result.location", "INVALID_FORMAT")
}

// kotlinx JSON intentionally accepts quoted numbers for some serializers; v1 does not.
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
fun strictTypes(node: JsonElement, descriptor: SerialDescriptor) {
    if(node is JsonNull) { if(!descriptor.isNullable) malformed(); return }
    when(descriptor.kind) {
        StructureKind.CLASS, StructureKind.OBJECT -> {
            val obj=node as? JsonObject ?: malformed()
            obj.forEach { (name,value) ->
                val index=descriptor.getElementIndex(name)
                if(index<0) malformed()
                strictTypes(value,descriptor.getElementDescriptor(index))
            }
        }
        StructureKind.LIST -> (node as? JsonArray ?: malformed()).forEach { strictTypes(it,descriptor.getElementDescriptor(0)) }
        else -> {
            val p=node as? JsonPrimitive ?: malformed()
            when(descriptor.kind) {
                PrimitiveKind.STRING, SerialKind.ENUM -> if(!p.isString) malformed()
                PrimitiveKind.BOOLEAN -> if(p.isString || p.booleanOrNull==null) malformed()
                PrimitiveKind.INT, PrimitiveKind.LONG -> if(p.isString || !Regex("-?(0|[1-9][0-9]*)").matches(p.content)) malformed()
                PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT -> if(p.isString || p.doubleOrNull?.isFinite()!=true) malformed()
                else -> Unit
            }
        }
    }
}
