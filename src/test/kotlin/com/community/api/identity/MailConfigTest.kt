package com.community.api.identity

import kotlin.test.*

class MailConfigTest {
    private val production = mapOf("APP_ENV" to "production", "SMTP_HOST" to "smtp.example.com",
        "SMTP_FROM" to "community@example.com", "SMTP_USER" to "smtp-user", "SMTP_PASSWORD" to "smtp-secret")

    @Test
    fun `production requires authenticated SMTP with STARTTLS`() {
        assertFailsWith<IllegalArgumentException> { MailConfig.fromEnvironment(mapOf("APP_ENV" to "production")) }
        assertFailsWith<IllegalArgumentException> { MailConfig.fromEnvironment(production + ("SMTP_STARTTLS" to "false")) }
        assertFailsWith<IllegalArgumentException> { MailConfig.fromEnvironment(production - "SMTP_USER" - "SMTP_PASSWORD") }
        val config = assertNotNull(MailConfig.fromEnvironment(production))
        assertTrue(config.startTls)
        assertEquals(587, config.port)
        assertFalse(config.toString().contains("smtp-secret"))
    }

    @Test
