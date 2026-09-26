package com.community.api.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppConfigTest {
    @Test
    fun `empty environment defaults to a local development server`() {
        val config = AppConfig.fromEnvironment(emptyMap())

        assertEquals("127.0.0.1", config.host)
        assertEquals(8080, config.port)
        assertEquals(Environment.DEVELOPMENT, config.environment)
    }

    @Test
    fun `server configuration can be supplied through environment variables`() {
        val config = AppConfig.fromEnvironment(
            mapOf("HOST" to "0.0.0.0", "PORT" to "9090", "APP_ENV" to "production"),
        )

        assertEquals("0.0.0.0", config.host)
        assertEquals(9090, config.port)
        assertEquals(Environment.PRODUCTION, config.environment)
    }

    @Test
    fun `each supported environment has an explicit value`() {
        mapOf(
            "development" to Environment.DEVELOPMENT,
            "test" to Environment.TEST,
            "production" to Environment.PRODUCTION,
        ).forEach { (value, expected) ->
            assertEquals(expected, AppConfig.fromEnvironment(mapOf("APP_ENV" to value)).environment)
        }
    }

    @Test
    fun `unknown environment values fail instead of silently selecting development`() {
