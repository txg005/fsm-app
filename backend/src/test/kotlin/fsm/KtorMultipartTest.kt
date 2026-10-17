package fsm

import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

data class SerializedMultipart(val contentType: String, val bytes: ByteArray)
// Exact String/ByteArray forms and headers used by Android Api.upload, Ktor 3.1.3.
suspend fun realKtorMultipart(upload: Upload = fixtureUpload()): SerializedMultipart = coroutineScope {
    val body = MultiPartFormDataContent(formData {
        append("metadata",encode(upload.metadata),Headers.build { append(HttpHeaders.ContentType,"application/json") })
        append("file",upload.bytes,Headers.build { append(HttpHeaders.ContentType,"image/jpeg"); append(HttpHeaders.ContentDisposition,"filename=\"photo.jpg\"") })
    })
    val channel=ByteChannel(autoFlush=true)
    val writer=launch { try { body.writeTo(channel) } finally { channel.close() } }
    val output=ByteArrayOutputStream(); val buffer=ByteArray(8192)
    while(true) { val n=channel.readAvailable(buffer,0,buffer.size); if(n<0) break; if(n>0) output.write(buffer,0,n) }
    writer.join()
    SerializedMultipart(body.contentType.toString(),output.toByteArray())
}
class KtorMultipartTest {
    @Test fun `real Ktor Android bytes accepted with exact part lengths`(): Unit = runBlocking {
        val actual=realKtorMultipart()
        val raw=actual.bytes.toString(Charsets.ISO_8859_1)
        println("KTOR_EMITTED_HEADERS="+raw.lineSequence().filter { it.startsWith("Content-",true) }.joinToString(" | "))
        println("KTOR_TERMINATES_CRLF="+raw.endsWith("--\r\n"))
        assertTrue(raw.contains("Content-Length: 165\r\n"))
        assertTrue(raw.contains("Content-Length: 631\r\n"))
        assertContentEquals(fixtureUpload().bytes,parseMultipart(actual.contentType,actual.bytes).bytes)
        Files.createDirectories(Path.of("build"))
        Files.write(Path.of("build/ktor-multipart.body"),actual.bytes)
        Files.writeString(Path.of("build/ktor-multipart.content-type"),actual.contentType)
        Unit
    }
    @Test fun `part lengths reject invalid mismatched overflowing duplicate and unknown headers`() = runBlocking {
        val actual=realKtorMultipart(); val raw=actual.bytes.toString(Charsets.ISO_8859_1)
        listOf("630","632","-631","+631","631x","","9223372036854775808").forEach { value ->
            val changed=raw.replace("Content-Length: 631", "Content-Length: $value")
            assertEquals("MALFORMED_REQUEST",assertFailsWith<ApiError> { parseMultipart(actual.contentType,changed.toByteArray(Charsets.ISO_8859_1)) }.code)
        }
        listOf(
            raw.replace("Content-Length: 165","Content-Length: 164"),
            raw.replace("Content-Length: 631","Content-Length: 631\r\ncOnTeNt-LeNgTh: 631"),
            raw.replace("Content-Length: 631","Content-Length: 631\r\nX-Unknown: yes"),
            raw.replace("name=metadata","name=file"),
            raw.replace("name=file;","name=metadata;")
        ).forEach { changed -> assertFailsWith<ApiError> { parseMultipart(actual.contentType,changed.toByteArray(Charsets.ISO_8859_1)) } }
    }
    @Test fun `legitimate header case charset filename and optional lengths preserve validation`() = runBlocking {
        val actual=realKtorMultipart(); val raw=actual.bytes.toString(Charsets.ISO_8859_1)
        val changed=raw.replace("Content-Length:","cOnTeNt-LeNgTh:")
            .replace("Content-Type: application/json","content-type: application/json; charset=UTF-8")
            .replace("filename=\"photo.jpg\"","filename=\"../../untrusted.jpg\"")
        assertContentEquals(fixtureUpload().bytes,parseMultipart(actual.contentType,changed.toByteArray(Charsets.ISO_8859_1)).bytes)
        // The frozen manually framed fixture deliberately has no per-part lengths.
        assertEquals(631,fixtureUpload().bytes.size)
    }

}
