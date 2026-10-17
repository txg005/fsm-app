package fsm

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import de.mkammerer.argon2.Argon2Factory
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

class LoginLimiter(private val clock: () -> Long = System::currentTimeMillis) {
    private data class Window(var start: Long, var count: Int)
    private val windows = ConcurrentHashMap<String, Window>()
    @Synchronized fun attempt(ip: String) {
        val now = clock()
        windows.entries.removeIf { now - it.value.start >= 60000 }
        val w = windows.getOrPut(ip) { Window(now, 0) }
        if (w.count >= 10) throw ApiError(429, "RATE_LIMITED", retryAfter = ((60000 - (now-w.start)+999)/1000).toInt())
        w.count++
    }
}
class Auth(private val db: Database, secret: ByteArray) {
    init { require(secret.size >= 32) { "JWT secret must contain at least 32 bytes" } }
    private val algorithm = Algorithm.HMAC256(secret)
    private val verifier = JWT.require(algorithm).withIssuer("fsm-api").withAudience("fsm-android").build()
    private val random = SecureRandom()
    private val argon = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)
    private val dummyHash = argon.hash(3, 65536, 1, newId().toCharArray())
    val limiter = LoginLimiter()
    fun login(r: LoginRequest, ip: String): TokenResponse {
        limiter.attempt(ip)
        if (r.login.length !in 1..100 || r.password.length !in 1..128 || r.login.any { it.code > 127 }) malformed()
        val entry = db.tx { c -> c.query("SELECT e.id,c.password_hash FROM employees e JOIN auth_credentials c ON c.employee_id=e.id WHERE e.login=?", r.login.lowercase(java.util.Locale.ROOT)) { it.getString("id") to it.getString("password_hash") }.singleOrNull() }
        val chars = r.password.toCharArray()
        val ok = try { argon.verify(entry?.second ?: dummyHash, chars) } finally { chars.fill('\u0000') }
        if (!ok || entry == null) throw ApiError(401, "INVALID_CREDENTIALS")
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val sid = newId(); val expires = Instant.now().truncatedTo(ChronoUnit.MILLIS).plus(30, ChronoUnit.DAYS)
        return db.tx { c ->
            c.exec("INSERT INTO refresh_sessions(id,employee_id,token_hash,expires_at) VALUES (?,?,?,?::timestamptz)", id(sid), id(entry.first), sha(token.toByteArray()), expires.toString())
            tokens(entry.first, sid, token, expires, c.employee(entry.first))
        }
    }
    private fun tokens(employeeId: String, sid: String, token: String, refreshExpires: Instant, employee: Employee): TokenResponse {
        val issued = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val expires = issued.plusSeconds(900)
        val access = JWT.create().withIssuer("fsm-api").withAudience("fsm-android").withSubject(employeeId).withClaim("sid",sid).withIssuedAt(Date.from(issued)).withExpiresAt(Date.from(expires)).sign(algorithm)
        return TokenResponse(access, timestamp(expires), token, timestamp(refreshExpires), employee)
    }
    fun refresh(r: RefreshRequest): TokenResponse {
        checkRefresh(r)
        return db.tx { c ->
            val row = c.query("SELECT * FROM refresh_sessions WHERE token_hash=? AND revoked_at IS NULL AND expires_at>now() FOR SHARE", sha(r.refreshToken.toByteArray())) { Triple(it.getString("employee_id"), it.getString("id"), it.getTimestamp("expires_at").toInstant()) }.singleOrNull() ?: throw ApiError(401,"SESSION_EXPIRED")
            tokens(row.first, row.second, r.refreshToken, row.third, c.employee(row.first))
        }
    }
    fun logout(r: RefreshRequest) {
        checkRefresh(r)
        db.tx { c -> c.exec("UPDATE refresh_sessions SET revoked_at=COALESCE(revoked_at,now()) WHERE token_hash=?", sha(r.refreshToken.toByteArray())) }
    }
    private fun checkRefresh(r: RefreshRequest) { if (r.refreshToken.length !in 1..512) malformed() }
    fun authenticate(header: String?): String {
        try {
            if (header == null || !header.startsWith("Bearer ") || header.length > 8192) throw IllegalArgumentException()
            val jwt = verifier.verify(header.removePrefix("Bearer "))
            val employee = uuid(jwt.subject ?: throw IllegalArgumentException())
            val sid = uuid(jwt.getClaim("sid").asString() ?: throw IllegalArgumentException())
            if (jwt.issuedAt == null || jwt.expiresAt == null || jwt.expiresAt.time - jwt.issuedAt.time != 900000L) throw IllegalArgumentException()
            val valid = db.tx { c -> c.query("SELECT id FROM refresh_sessions WHERE id=? AND employee_id=? AND revoked_at IS NULL AND expires_at>now()", id(sid), id(employee)) { true }.isNotEmpty() }
            if (!valid) throw IllegalArgumentException()
            return employee
        } catch (e: java.sql.SQLException) { throw e }
        catch (_: Exception) { throw ApiError(401,"AUTH_REQUIRED") }
    }
}
