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
    fun `development permits explicit local SMTP and rejects invalid settings`() {
        assertNull(MailConfig.fromEnvironment(emptyMap()))
        val local = mapOf("SMTP_HOST" to "localhost", "SMTP_PORT" to "1025", "SMTP_FROM" to "dev@example.com", "SMTP_STARTTLS" to "false")
        assertFalse(assertNotNull(MailConfig.fromEnvironment(local)).startTls)
        assertFails { MailConfig.fromEnvironment(local + ("SMTP_PORT" to "no")) }
        assertFails { MailConfig.fromEnvironment(local + ("SMTP_FROM" to "a@example.com\r\nBcc: b@example.com")) }
        assertFails { MailConfig.fromEnvironment(local + ("SMTP_STARTTLS" to "maybe")) }
        assertFails { MailConfig.fromEnvironment(local + ("SMTP_USER" to "incomplete")) }
    }
}
