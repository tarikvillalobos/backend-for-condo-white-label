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
