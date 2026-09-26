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
        server.accept().use { socket ->
            socket.soTimeout = 5000
            val reader = socket.getInputStream().bufferedReader()
            val writer = socket.getOutputStream().bufferedWriter()
            fun reply(value: String) { writer.write("$value\r\n"); writer.flush() }
            reply("220 localhost test SMTP")
            while (true) {
                val line = reader.readLine() ?: break
                lines += line
                when {
                    line.startsWith("EHLO") -> reply("250-localhost\r\n250-AUTH PLAIN\r\n250 8BITMIME")
                    line.startsWith("AUTH PLAIN") -> reply("235 Authentication accepted")
                    line.startsWith("MAIL FROM") || line.startsWith("RCPT TO") -> reply("250 OK")
                    line == "DATA" -> {
                        reply("354 Send data")
                        while (true) {
                            val content = reader.readLine() ?: break
                            if (content == ".") break
                            lines += content
                        }
                        reply("250 Queued")
                    }
                    line == "QUIT" -> { reply("221 Bye"); break }
                    else -> reply("250 OK")
                }
            }
        }
        return lines
    }
}
