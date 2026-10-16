package fsm

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.util.UUID

class Database(url: String, user: String, password: String, schema: String = "public") : AutoCloseable {
    val pool = HikariDataSource(HikariConfig().apply {
        jdbcUrl = url; username = user; this.password = password
        maximumPoolSize = 12; minimumIdle = 1; this.schema = schema
        connectionTimeout = 5000
    })
    init { Flyway.configure().dataSource(pool).schemas(schema).defaultSchema(schema).load().migrate() }
    fun <T> tx(repeatable: Boolean = false, block: (Connection) -> T): T = pool.connection.use { c ->
        c.autoCommit = false
        c.transactionIsolation = if (repeatable) Connection.TRANSACTION_REPEATABLE_READ else Connection.TRANSACTION_READ_COMMITTED
        try { block(c).also { c.commit() } } catch (t: Throwable) { c.rollback(); throw t }
    }
    override fun close() = pool.close()
}
fun Connection.exec(sql: String, vararg args: Any?): Int = prepareStatement(sql).use { s ->
    args.forEachIndexed { i, a -> s.setObject(i + 1, a) }; s.executeUpdate()
}
fun <T> Connection.query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> = prepareStatement(sql).use { s ->
    args.forEachIndexed { i, a -> s.setObject(i + 1, a) }
    s.executeQuery().use { r -> buildList { while (r.next()) add(map(r)) } }
}
fun id(s: String): UUID = UUID.fromString(s)
fun Connection.lock(key: String) { query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", key) { true } }
fun ResultSet.instant(column: String): String? = getTimestamp(column)?.toInstant()?.let(::timestamp)
fun ResultSet.employee() = Employee(getString("id"), getString("display_name"), getString("login"))
fun ResultSet.photo() = Photo(getString("id"), getString("server_photo_id"), getString("mime_type"), getLong("byte_size"), getString("sha256"), instant("created_at")!!, "/api/v1/photos/${getString("server_photo_id")}/content")
fun Connection.employee(employeeId: String) = query("SELECT * FROM employees WHERE id=?", id(employeeId)) { it.employee() }.single()
fun Connection.order(employeeId: String, orderId: String, lock: Boolean = false): WorkOrder {
    val row = query("SELECT * FROM work_orders WHERE id=? AND assignee_id=?" + if(lock) " FOR UPDATE" else "", id(orderId), id(employeeId)) { r ->
        WorkOrder(r.getString("id"), r.getString("equipment_id"), r.getString("assignee_id"), r.getString("title"), r.getString("description"), r.instant("due_at"), OrderStatus.valueOf(r.getString("status")), r.getLong("revision"), emptyList(), wire.decodeFromString(r.getString("measurement_specs")), null)
    }.singleOrNull() ?: notFound()
    val checklist = query("SELECT * FROM checklist_items WHERE work_order_id=? ORDER BY position,id", id(orderId)) { r -> ChecklistItem(r.getString("id"), r.getInt("position"), r.getString("text"), r.getBoolean("required")) }
    val result = query("SELECT * FROM inspection_results WHERE work_order_id=?", id(orderId)) { r ->
        val photos = query("SELECT * FROM photos WHERE result_id=? ORDER BY position", r.getObject("id")) { it.photo() }
        Result(r.getString("id"), wire.decodeFromString(r.getString("answers")), wire.decodeFromString(r.getString("readings")), r.getString("comment"), wire.decodeFromString(r.getString("location")), r.instant("captured_at")!!, photos)
    }.singleOrNull()
    return row.copy(checklistItems = checklist, result = result)
}
fun Connection.orders(employeeId: String) = query("SELECT id FROM work_orders WHERE assignee_id=? ORDER BY id", id(employeeId)) { it.getString("id") }.map { order(employeeId, it) }
fun Connection.reference(employeeId: String): ReferenceData {
    val equipment = query("SELECT e.* FROM equipment e WHERE EXISTS(SELECT 1 FROM work_orders w WHERE w.equipment_id=e.id AND w.assignee_id=?) ORDER BY e.id", id(employeeId)) { r -> Equipment(r.getString("id"), r.getString("site_id"), r.getString("name"), r.getString("inventory_number")) }
    val sites = query("SELECT s.* FROM sites s WHERE EXISTS(SELECT 1 FROM equipment e JOIN work_orders w ON w.equipment_id=e.id WHERE e.site_id=s.id AND w.assignee_id=?) ORDER BY s.id", id(employeeId)) { r -> Site(r.getString("id"), r.getString("name"), r.getString("address")) }
    return ReferenceData(employee(employeeId), sites, equipment)
}
