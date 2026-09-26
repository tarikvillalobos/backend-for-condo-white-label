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
