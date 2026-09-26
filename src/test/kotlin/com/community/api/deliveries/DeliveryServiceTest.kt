package com.community.api.deliveries

import com.community.api.core.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class DeliveryServiceTest {
    private val clock = Clock.fixed(Instant.parse("2030-01-01T12:00:00Z"), ZoneOffset.UTC)
    private val service = DeliveryService(clock)
    private val staff = Context(Actor("staff", "tenant", "staff-session"), "standalone", setOf("*"))
    private val recipient = Context(Actor("recipient", "tenant", "resident-session"), "standalone", setOf("packages.read.own"))
    private val outsider = Context(Actor("outsider", "tenant", "other-session"), "standalone", setOf("packages.read.own"))

    private fun database(): Database = Database.memory().also { db ->
        db.tx { tx ->
