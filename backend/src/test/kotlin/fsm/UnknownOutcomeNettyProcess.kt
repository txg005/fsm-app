package fsm

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CountDownLatch

/** Test-only child JVM: real production routes, no mutation/fault hooks. Not in installDist. */
object UnknownOutcomeNettyProcess {
    @JvmStatic fun main(args: Array<String>) {
        fun env(name: String) = System.getenv(name) ?: error("Missing proof configuration")
        try {
            val schema = env("PROOF_SCHEMA")
            require(Regex("outcome_[0-9a-f]{32}").matches(schema))
            val directory = Path.of(env("PROOF_STORAGE"))
            require(Files.isDirectory(directory))
            val db = Database(env("PROOF_DATABASE_URL"), env("PROOF_DATABASE_USER"), env("PROOF_DATABASE_PASSWORD"), schema)
            val auth = Auth(db, Base64.getDecoder().decode(env("PROOF_JWT_SECRET")))
            val operations = Operations(db, PhotoStorage(directory))
            val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { fsm(db, auth, operations) }
            Runtime.getRuntime().addShutdownHook(Thread {
                try { server.stop(500, 3000) } finally { db.close() }
            })
            server.start(wait = false)
            val port = runBlocking { server.engine.resolvedConnectors().single().port }
            println("PROOF_READY $port")
            System.out.flush()
            CountDownLatch(1).await()
        } catch (_: Throwable) {
            // Never emit exception/configuration values, credentials or request bodies.
            System.err.println("PROOF_START_FAILED")
            kotlin.system.exitProcess(1)
        }
    }
}
