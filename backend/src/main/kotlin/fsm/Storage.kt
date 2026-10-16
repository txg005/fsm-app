package fsm

import io.ktor.http.*
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.*
import javax.imageio.ImageIO
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val PHOTO_MAX = 10 * 1024 * 1024
const val MULTIPART_MAX = 11 * 1024 * 1024
const val JSON_MAX = 256 * 1024
fun tooLarge(photo: Boolean): Nothing = throw ApiError(413, if(photo) "PHOTO_TOO_LARGE" else "REQUEST_TOO_LARGE", buildJsonObject { put("maxBytes", if(photo) PHOTO_MAX else JSON_MAX) })
fun photoInvalid(): Nothing = throw ApiError(422,"PHOTO_CONTENT_INVALID")
fun validatePhoto(m: PhotoMetadata, bytes: ByteArray) {
    uuid(m.photoId)
    if(m.mimeType != "image/jpeg" || !Regex("[0-9a-f]{64}").matches(m.sha256) || m.byteSize < 1) malformed()
    if(m.byteSize > PHOTO_MAX || bytes.size > PHOTO_MAX) tooLarge(true)
    if(bytes.size.toLong() != m.byteSize || sha(bytes) != m.sha256) photoInvalid()
    if(bytes.size < 4 || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte() || bytes[bytes.size-2] != 0xff.toByte() || bytes.last() != 0xd9.toByte()) photoInvalid()
    try {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            if(!readers.hasNext()) photoInvalid()
            val reader = readers.next()
            try {
                if(!reader.formatName.equals("JPEG", true)) photoInvalid()
                reader.input = stream
                // Limit decoded allocation without rejecting legitimate high resolution JPEGs.
                val width=reader.getWidth(0); val height=reader.getHeight(0)
                if(width < 1 || height < 1) photoInvalid()
                val param = reader.defaultReadParam
                val sample = maxOf(1, (maxOf(width,height)+1023)/1024)
                param.setSourceSubsampling(sample,sample,0,0)
                if(reader.read(0,param) == null) photoInvalid()
            } finally { reader.dispose() }
        }
    } catch(e: ApiError) { throw e } catch(_: Exception) { photoInvalid() }
}
data class Upload(val metadata: PhotoMetadata, val bytes: ByteArray)
// Bounded raw body allows an exact total limit (including framing), not merely file-size limits.
// ISO-8859-1 is a bijection of bytes: no JPEG data ever passes through UTF-8 decoding.
fun parseMultipart(contentType: String, bytes: ByteArray): Upload {
    if(bytes.size > MULTIPART_MAX) tooLarge(true)
    val ct = try { ContentType.parse(contentType) } catch(_: Exception) { malformed() }
    if(!ct.match(ContentType.MultiPart.FormData)) malformed()
    val boundary = ct.parameter("boundary") ?: malformed()
    if(boundary.length !in 1..70 || boundary.any { it.code !in 32..126 || it == '\r' || it == '\n' } || boundary.endsWith(' ')) malformed()
    val raw = bytes.toString(Charsets.ISO_8859_1)
    val marker = "--$boundary"
    if(!raw.startsWith("$marker\r\n") || !raw.endsWith("\r\n$marker--\r\n")) malformed()
    val parts = raw.substring(marker.length+2, raw.length-(marker.length+6)).split("\r\n$marker\r\n")
    if(parts.size != 2) malformed()
    fun part(rawPart: String, name: String, type: ContentType): ByteArray {
        val index=rawPart.indexOf("\r\n\r\n")
        if(index !in 1..8192) malformed()
        val headers = rawPart.substring(0,index).split("\r\n").map { line ->
            val colon=line.indexOf(':'); if(colon<1) malformed()
            line.substring(0,colon).lowercase() to line.substring(colon+1).trim()
        }
        if(headers.map { it.first }.distinct().size != headers.size || headers.any { it.first !in setOf("content-disposition","content-type") }) malformed()
        val map=headers.toMap()
        val disposition=try { ContentDisposition.parse(map["content-disposition"] ?: malformed()) } catch(_: Exception) { malformed() }
        val mime=try { ContentType.parse(map["content-type"] ?: malformed()) } catch(_: Exception) { malformed() }
        if(disposition.disposition != "form-data" || disposition.name != name || !mime.match(type)) malformed()
        if(name=="metadata" && disposition.parameter("filename") != null) malformed()
        if(name=="file" && disposition.parameter("filename")==null) malformed()
        return rawPart.substring(index+4).toByteArray(Charsets.ISO_8859_1)
    }
    val metadataBytes=part(parts[0],"metadata",ContentType.Application.Json)
    if(metadataBytes.size>4096) malformed()
    val metadata=decode<PhotoMetadata>(metadataBytes)
    val file=part(parts[1],"file",ContentType.Image.JPEG)
    validatePhoto(metadata,file)
    return Upload(metadata,file)
}
class PhotoStorage(val root: Path) {
    init { Files.createDirectories(root) }
    fun key(m: PhotoMetadata) = "${m.photoId}-${m.sha256}.jpg"
    fun path(key: String): Path {
        require(Regex("[0-9a-f-]{36}-[0-9a-f]{64}\\.jpg").matches(key))
        return root.resolve(key)
    }
    fun durable(m: PhotoMetadata, bytes: ByteArray): String {
        val key = key(m); val target=path(key)
        // Called while photo-id transaction lock is held. A rename-before-commit orphan is reusable.
        if(Files.exists(target)) {
            if(Files.size(target)!=m.byteSize || sha(Files.readAllBytes(target))!=m.sha256) throw java.io.IOException("storage integrity")
            FileChannel.open(target,WRITE).use { it.force(true) }
            syncDirectory()
            return key
        }
        val temp=Files.createTempFile(root,".upload-",".tmp")
        try {
            FileChannel.open(temp,WRITE).use { c -> val b=ByteBuffer.wrap(bytes); while(b.hasRemaining()) c.write(b); c.force(true) }
            Files.move(temp,target,ATOMIC_MOVE)
            syncDirectory()
        } finally { Files.deleteIfExists(temp) }
        return key
    }
    private fun syncDirectory() { FileChannel.open(root,READ).use { it.force(true) } }
}
