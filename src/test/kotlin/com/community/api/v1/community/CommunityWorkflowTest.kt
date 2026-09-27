package com.community.api.v1.community

import com.community.api.core.ApiException
import kotlinx.serialization.json.*
import kotlin.test.*

class CommunityWorkflowTest {
    @Test fun `event capacity is enforced and cancellation ends attendance`() = CommunityFixture().use { f ->
        val event = f.run("adminCreateEvent", obj("title" to "Assembleia", "kind" to "assembly", "startsAt" to future(60),
            "endsAt" to future(3600), "allDay" to false, "notify" to false, "capacity" to 1, "rsvpEnabled" to true), staff = true)
        f.run("attendEvent", ids = mapOf("eventId" to event.id()))
        assertEquals(409, assertFailsWith<ApiException> { f.run("attendEvent", ids = mapOf("eventId" to event.id()), other = true) }.status)
        f.run("unattendEvent", ids = mapOf("eventId" to event.id()))
        f.run("attendEvent", ids = mapOf("eventId" to event.id()), other = true)
        assertEquals(1, f.run("adminListAttendance", ids = mapOf("eventId" to event.id()), staff = true).items().size)
        f.run("adminCancelEvent", obj("reason" to "Cancelado por chuva"), mapOf("eventId" to event.id()), staff = true)
        assertEquals(409, assertFailsWith<ApiException> { f.run("attendEvent", ids = mapOf("eventId" to event.id())) }.status)
    }
    @Test fun `announcement targets and receipts respect audience`() = CommunityFixture().use { f ->
        val announcement = f.run("publishAnnouncement", obj("title" to "Aviso importante", "body" to "Texto", "category" to "general",
