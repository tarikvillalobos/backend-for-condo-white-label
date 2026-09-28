package com.community.api.v1.deliveries

import com.community.api.core.ApiException
import com.community.api.core.Database
import com.community.api.v1.V1Context
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LockerProviderTest {
    @Test fun `remote command requires provider acceptance and forwards stable identifier`() {
        val command = UUID.randomUUID().toString()
        val received = AtomicReference<Triple<String, String, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/lockers/locker/compartments/A1/open") { exchange ->
            received.set(Triple(exchange.requestHeaders.getFirst("Idempotency-Key"),
                exchange.requestHeaders.getFirst("Authorization"), exchange.requestBody.bufferedReader().readText()))
            val body = "{\"commandId\":\"$command\"}".toByteArray()
            exchange.sendResponseHeaders(202, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            Database.memory().use { db -> db.scopedTx(null) { tx ->
                val c = V1Context(tx, "adminOpenCompartment", "tenant", "brand", UUID.randomUUID().toString())
                LockerProvider.open(c, "locker", "device", "A1", command, "Encomenda presa",
                    "http://127.0.0.1:${server.address.port}", "test-token", allowHttp = true)
                assertEquals(503, assertFailsWith<ApiException> {
                    LockerProvider.open(c, "locker", "device", "A1", command, "Motivo",
                        "http://127.0.0.1:${server.address.port}", "test-token", allowHttp = false)
                }.status)
            } }
            assertEquals(command, received.get().first)
            assertEquals("Bearer test-token", received.get().second)
            assertTrue(received.get().third.contains("\"commandId\":\"$command\""))
        } finally { server.stop(0) }
    }
}
