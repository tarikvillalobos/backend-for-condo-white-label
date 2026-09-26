package com.community.api.identity

import jakarta.mail.internet.InternetAddress

class MailConfig(
    val host: String, val port: Int, val from: String, val username: String? = null,
    val password: String? = null, val startTls: Boolean = true,
) {
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): MailConfig? {
            val production = env["APP_ENV"] == "production"
            val host = env["SMTP_HOST"]?.trim()?.takeIf { it.isNotEmpty() }
            if (host == null) {
                require(!production) { "SMTP_HOST is required in production for account verification and recovery" }
                return null
            }
            require(host.length <= 253 && host.none { it.isWhitespace() || it == '/' }) { "Invalid SMTP_HOST" }
            val port = env["SMTP_PORT"]?.toIntOrNull() ?: if (env["SMTP_PORT"] == null) 587 else error("Invalid SMTP_PORT")
            require(port in 1..65535) { "Invalid SMTP_PORT" }
            val from = env["SMTP_FROM"]?.trim()?.takeIf { it.isNotEmpty() } ?: error("SMTP_FROM is required")
