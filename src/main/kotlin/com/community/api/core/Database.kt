package com.community.api.core

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

class Database(url: String, user: String = "sa", password: String = "", poolSize: Int = 8) : AutoCloseable {
    init { require(poolSize in 2..64) { "DB_POOL_SIZE must be between 2 and 64" } }
    private val source = HikariDataSource(HikariConfig().apply {
        jdbcUrl = url
        username = user
        this.password = password
        maximumPoolSize = poolSize
        minimumIdle = minOf(2,poolSize)
        connectionTimeout = 5000
        transactionIsolation = "TRANSACTION_READ_COMMITTED"
    })

    init {
        try {
            val locations = mutableListOf("classpath:db/migration")
            if (url.startsWith("jdbc:postgresql:")) locations += "classpath:db/postgresql"
            Flyway.configure().dataSource(source).locations(*locations.toTypedArray()).load().migrate()
        } catch (failure: Exception) {
            source.close()
            throw failure
        }
    }

    fun <T> tx(block: (Tx) -> T): T = source.connection.use { connection ->
        connection.autoCommit = false
        try {
            // A database lock serializes state transitions across all API instances.
            // Replace with narrower locks only alongside concurrency regression tests.
            connection.prepareStatement("SELECT id FROM app_mutex WHERE id = 1 FOR UPDATE").use {
                it.queryTimeout = 10
                it.executeQuery().use { rows -> check(rows.next()) }
            }
            val result = block(Tx(connection))
            connection.commit()
            result
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        }
    }

    suspend fun <T> query(block: (Tx) -> T): T = withContext(Dispatchers.IO) { tx(block) }
    fun <T> scopedTx(scope: String?, block: (Tx) -> T): T = source.connection.use { connection ->
        connection.autoCommit = false
        try {
            val transaction = Tx(connection)
            if (scope != null) transaction.lock(scope)
            val result = block(transaction)
            connection.commit()
            result
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        }
    }
    suspend fun <T> scopedQuery(scope: String?, block: (Tx) -> T): T =
        withContext(Dispatchers.IO) { scopedTx(scope, block) }
    fun healthy(): Boolean = runCatching { source.connection.use { it.isValid(2) } }.getOrDefault(false)
    override fun close() = source.close()

    companion object {
        fun memory(): Database = Database("jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
        fun fromEnvironment(env: Map<String, String> = System.getenv()): Database {
            val url = env["DATABASE_URL"] ?: "jdbc:h2:file:./data/community;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
            require(url.startsWith("jdbc:h2:") || url.startsWith("jdbc:postgresql:")) { "Unsupported DATABASE_URL" }
            if (env["APP_ENV"] == "production") {
                require(url.startsWith("jdbc:postgresql:")) { "Production requires PostgreSQL" }
                require(!env["DATABASE_PASSWORD"].isNullOrBlank()) { "DATABASE_PASSWORD is required in production" }
            }
            return Database(url, env["DATABASE_USER"] ?: "sa", env["DATABASE_PASSWORD"] ?: "")
        }
    }
}

class Tx internal constructor(internal val connection: Connection) {
    val postgres: Boolean = connection.metaData.databaseProductName == "PostgreSQL"
    fun lock(scope: String) {
        if (postgres) connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use {
            it.setString(1, scope); it.queryTimeout = 15; it.execute()
        } else {
            connection.prepareStatement("MERGE INTO v1_scope_locks (scope_id) KEY(scope_id) VALUES (?)").use {
                it.setString(1, scope); it.executeUpdate()
            }
            connection.prepareStatement("SELECT scope_id FROM v1_scope_locks WHERE scope_id = ? FOR UPDATE").use {
                it.setString(1, scope); it.execute()
            }
        }
    }
    // Only bootstrap/maintenance workers may enumerate tenants. Never expose this over HTTP.
    fun clients(): List<Record> = connection.prepareStatement("SELECT * FROM app_records WHERE kind = 'client' ORDER BY id").use {
        it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.record()) } }
    }

    fun get(kind: String, id: String, tenantId: String? = null): Record? {
        val sql = "SELECT * FROM app_records WHERE kind = ? AND id = ?" + if (tenantId != null) " AND tenant_id = ?" else ""
        return connection.prepareStatement(sql).use {
            it.setString(1, kind)
            it.setString(2, id)
            if (tenantId != null) it.setString(3, tenantId)
            it.executeQuery().use { rows -> if (rows.next()) rows.record() else null }
        }
    }

    fun list(kind: String, tenantId: String, locationId: String? = null, ownerId: String? = null): List<Record> {
        val values = mutableListOf(kind, tenantId)
        val sql = buildString {
            append("SELECT * FROM app_records WHERE kind = ? AND tenant_id = ?")
            if (locationId != null) { append(" AND location_id = ?"); values += locationId }
            if (ownerId != null) { append(" AND owner_id = ?"); values += ownerId }
            append(" ORDER BY created_at, id")
        }
        return connection.prepareStatement(sql).use {
            values.forEachIndexed { index, value -> it.setString(index + 1, value) }
            it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.record()) } }
        }
    }

    fun create(
        kind: String,
        tenantId: String,
        locationId: String? = null,
        ownerId: String? = null,
        data: kotlinx.serialization.json.JsonObject,
        id: String = UUID.randomUUID().toString(),
    ): Record {
        val now = Instant.now().toString()
        val record = Record(id, kind, tenantId, locationId, ownerId, data, now, now, 1)
        connection.prepareStatement("INSERT INTO app_records (id,kind,tenant_id,location_id,owner_id,payload,created_at,updated_at,version) VALUES (?,?,?,?,?,?,?,?,?)").use {
            it.setString(1, id)
            it.setString(2, kind)
            it.setString(3, tenantId)
            it.setString(4, locationId)
            it.setString(5, ownerId)
            it.setString(6, json.encodeToString(data))
            it.setString(7, now)
            it.setString(8, now)
            it.setInt(9, 1)
            it.executeUpdate()
        }
        return record
    }

    fun update(record: Record, data: kotlinx.serialization.json.JsonObject, ownerId: String? = record.ownerId): Record {
        val updated = record.copy(data = data, ownerId = ownerId, updatedAt = Instant.now().toString(), version = record.version + 1)
        connection.prepareStatement("UPDATE app_records SET payload=?, updated_at=?, version=?, owner_id=? WHERE id=? AND tenant_id=? AND version=?").use {
            it.setString(1, json.encodeToString(data))
            it.setString(2, updated.updatedAt)
            it.setInt(3, updated.version)
            it.setString(4, ownerId)
            it.setString(5, record.id)
            it.setString(6, record.tenantId)
            it.setInt(7, record.version)
            if (it.executeUpdate() != 1) conflict("Resource changed; reload before retrying")
        }
        return updated
    }

    fun delete(record: Record) {
        connection.prepareStatement("DELETE FROM app_records WHERE id=? AND tenant_id=? AND version=?").use {
            it.setString(1, record.id)
            it.setString(2, record.tenantId)
            it.setInt(3, record.version)
            if (it.executeUpdate() != 1) conflict()
        }
    }

    private fun ResultSet.record() = Record(
        getString("id"), getString("kind"), getString("tenant_id"), getString("location_id"),
        getString("owner_id"), json.parseToJsonElement(getString("payload")) as kotlinx.serialization.json.JsonObject,
        getString("created_at"), getString("updated_at"), getInt("version"),
    )
}
