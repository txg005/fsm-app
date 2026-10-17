package fsm

import de.mkammerer.argon2.Argon2Factory

// Stable v4 IDs align with frozen fixture. Each engineer owns two orders for retry/conflict demos.
fun demoId(n: Int) = "10000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
data class SeedAccount(val login: String, val password: String)
fun seed(db: Database, accounts: List<SeedAccount>) {
    require(accounts.size == 2)
    require(accounts.map { it.login.lowercase() }.distinct().size == 2)
    accounts.forEach { require(it.login.matches(Regex("[A-Za-z0-9._-]{1,100}")) && it.password.length in 12..128) }
    db.tx { c ->
        c.lock("seed-v1")
        accounts.forEachIndexed { index, account ->
            val offset = index * 100
            val employee = demoId(offset+1)
            val exists = c.query("SELECT id FROM employees WHERE id=?", id(employee)) { true }.isNotEmpty()
            if (!exists) {
                c.exec("INSERT INTO employees VALUES (?,?,?)", id(employee), if(index==0) "Демо инженер" else "Второй инженер", account.login.lowercase())
                val chars = account.password.toCharArray()
                val hash = try { Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id).hash(3, 65536, 1, chars) } finally { chars.fill('\u0000') }
                c.exec("INSERT INTO auth_credentials VALUES (?,?)", id(employee), hash)
                c.exec("INSERT INTO sites VALUES (?,?,?)", id(demoId(offset+2)), "Демо объект ${index+1}", "Демонстрационный адрес")
                c.exec("INSERT INTO equipment VALUES (?,?,?,?)", id(demoId(offset+3)), id(demoId(offset+2)), "Насос", "DEMO-00${index+1}")
                val specs = encode(listOf(MeasurementSpec("pressure", "Давление", "bar", true, "0", "10")))
                listOf(4, 14).forEach { order ->
                    c.exec("INSERT INTO work_orders VALUES (?,?,?,?,?,NULL,'ASSIGNED',1,?::jsonb)", id(demoId(offset+order)), id(demoId(offset+3)), id(employee), "Осмотр насоса", "Проверить оборудование", specs)
                    c.exec("INSERT INTO checklist_items VALUES (?,?,0,?,true)", id(demoId(offset+order+1)), id(demoId(offset+order)), "Внешний осмотр выполнен")
                }
            }
        }
    }
}
