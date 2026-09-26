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

class Database(url: String, user: String = "sa", password: String = "") : AutoCloseable {
    private val source = HikariDataSource(HikariConfig().apply {
        jdbcUrl = url
        username = user
        this.password = password
        maximumPoolSize = 8
        connectionTimeout = 5000
        transactionIsolation = "TRANSACTION_READ_COMMITTED"
    })

    init {
        try {
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
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
    fun healthy(): Boolean = runCatching { source.connection.use { it.isValid(2) } }.getOrDefault(false)
    override fun close() = source.close()

    companion object {
        fun memory(): Database = Database("jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
        fun fromEnvironment(env: Map<String, String> = System.getenv()): Database {
            val url = env["DATABASE_URL"] ?: "jdbc:h2:file:./data/community;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
            require(url.startsWith("jdbc:h2:") || url.startsWith("jdbc:postgresql:")) { "Unsupported DATABASE_URL" }
            if (env["APP_ENV"] == "production") {
                require(url.startsWith("jdbc:postgresql:")) { "Production requires PostgreSQL" }
