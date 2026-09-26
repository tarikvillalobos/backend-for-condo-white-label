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
            require(!from.contains('\r') && !from.contains('\n')) { "Invalid SMTP_FROM" }
            runCatching { InternetAddress(from, true).validate() }.getOrElse { error("Invalid SMTP_FROM") }
            val startTls = env["SMTP_STARTTLS"]?.toBooleanStrictOrNull()
                ?: if (env["SMTP_STARTTLS"] == null) true else error("SMTP_STARTTLS must be true or false")
            val username = env["SMTP_USER"]?.takeIf { it.isNotBlank() }
            val password = env["SMTP_PASSWORD"]?.takeIf { it.isNotBlank() }
            require((username == null) == (password == null)) { "SMTP_USER and SMTP_PASSWORD must be configured together" }
            require(!production || startTls) { "SMTP_STARTTLS=true is required in production" }
            require(!production || username != null) { "Authenticated SMTP is required in production" }
            return MailConfig(host, port, from, username, password, startTls)
        }
    }
}
