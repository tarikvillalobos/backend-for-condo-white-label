package com.community.api.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.*

class DatabaseTest {
    @Test
    fun `transaction failures roll back all writes`() {
        Database.memory().use { db ->
            assertFailsWith<IllegalStateException> {
                db.tx { tx -> tx.create("counter", "test", data = counter(1), id = "rollback"); error("abort") }
            }
            assertNull(db.tx { it.get("counter", "rollback", "test") })
        }
    }

    @Test
    fun `simultaneous read modify write transactions do not lose updates`() {
        Database.memory().use { verifyConcurrency(it) }
    }

    @Test
    fun `PostgreSQL migrations persistence rollback and concurrent transactions`() {
        val url = System.getenv("TEST_DATABASE_URL")
        assumeTrue(url != null, "PostgreSQL integration runs in CI or with TEST_DATABASE_URL")
        val user = System.getenv("TEST_DATABASE_USER") ?: "community"
        val password = System.getenv("TEST_DATABASE_PASSWORD") ?: "community"
        val id = UUID.randomUUID().toString()
        Database(url!!, user, password).use { db ->
            verifyConcurrency(db)
            db.tx { it.create("persistence_test", id, data = counter(42), id = id) }
            assertFailsWith<IllegalStateException> { db.tx { tx -> tx.update(tx.requireRecord("persistence_test", id, id), counter(0)); error("rollback") } }
        }
        Database(url, user, password).use { db ->
            assertEquals(42, db.tx { it.requireRecord("persistence_test", id, id).data.getValue("value").jsonPrimitive.int })
        }
    }

    private fun verifyConcurrency(db: Database) {
        val id = UUID.randomUUID().toString()
        db.tx { it.create("counter", id, data = counter(0), id = id) }
        Executors.newFixedThreadPool(4).use { executor ->
            executor.invokeAll((1..20).map { Callable {
                db.tx { tx ->
                    val row = tx.requireRecord("counter", id, id)
                    val value = row.data.getValue("value").jsonPrimitive.int
                    tx.update(row, counter(value + 1))
                }
            } }).forEach { it.get() }
        }
        assertEquals(20, db.tx { it.requireRecord("counter", id, id).data.getValue("value").jsonPrimitive.int })
    }

    private fun counter(value: Int) = buildJsonObject { put("value", value) }
}
