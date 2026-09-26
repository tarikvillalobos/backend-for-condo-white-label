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
