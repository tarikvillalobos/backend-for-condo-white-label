package com.community.api.identity

import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SmtpMailSenderTest {
    @Test
    fun `SMTP sender authenticates and delivers the message through a local server`() = runBlocking {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            Executors.newSingleThreadExecutor().use { executor ->
                val exchange = executor.submit<List<String>> { serve(server) }
                val config = MailConfig("localhost", server.localPort, "from@example.com", "test-user", "test-password", false)
                SmtpMailSender.send(config, MailMessage("to@example.com", "Test subject", "Synthetic recovery credential", "test-message-id"))
                val lines = exchange.get(10, TimeUnit.SECONDS)
                assertTrue(lines.any { it.startsWith("AUTH PLAIN") })
                assertTrue(lines.any { it.startsWith("RCPT TO:<to@example.com>", ignoreCase = true) })
                assertTrue(lines.any { it.contains("Synthetic recovery credential") })
                assertTrue(lines.any { it.contains("<test-message-id@community-api>") })
            }
        }
    }

    @Test
    fun `required STARTTLS refuses a server that only supports cleartext`() = runBlocking {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            Executors.newSingleThreadExecutor().use { executor ->
                val exchange = executor.submit<List<String>> { serve(server) }
                val config = MailConfig("localhost", server.localPort, "from@example.com", "test-user", "test-password", true)
                assertFails { SmtpMailSender.send(config, MailMessage("to@example.com", "Test", "Secret", "test")) }
                assertFalse(exchange.get(10, TimeUnit.SECONDS).any { it.startsWith("AUTH") })
            }
        }
    }

    private fun serve(server: ServerSocket): List<String> {
        server.soTimeout = 5000
        val lines = mutableListOf<String>()
