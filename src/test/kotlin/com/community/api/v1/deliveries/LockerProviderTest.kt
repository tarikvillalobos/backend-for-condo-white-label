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
