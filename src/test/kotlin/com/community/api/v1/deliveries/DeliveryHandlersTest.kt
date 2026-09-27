package com.community.api.v1.deliveries

import com.community.api.core.*
import com.community.api.v1.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class DeliveryHandlersTest {
    private val now = Instant.parse("2030-01-01T12:00:00Z")
    private val handlers = deliveryHandlers()
    private fun database(): Database = Database.memory().also { db -> db.tx { tx ->
        val store = V1Store(tx, "tenant", "brand")
        store.create("condominium", obj("name" to "Condo", "timeZone" to "America/Sao_Paulo", "address" to "Rua 1"), id = "condo")
        store.create("node", obj("type" to "unit", "label" to "101"), "condo", id = "unit")
        listOf("alice", "bob").forEach { user ->
            tx.create("account", "tenant", data = obj("name" to user, "email" to "$user@example.test", "active" to true), id = user)
            store.create("membership", obj("userId" to user, "nodeId" to "unit", "status" to "active"), "condo", user, user)
