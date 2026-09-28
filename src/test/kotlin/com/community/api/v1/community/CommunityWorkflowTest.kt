package com.community.api.v1.community

import com.community.api.core.ApiException
import kotlinx.serialization.json.*
import kotlin.test.*

class CommunityWorkflowTest {
    @Test fun `event capacity is enforced and cancellation ends attendance`(): Unit = CommunityFixture().use { f ->
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
            "pinned" to false, "pushNotify" to true, "targetNodeIds" to JsonArray(listOf(JsonPrimitive(f.unit)))), staff = true)
        assertEquals(1, f.run("listAnnouncements").items().size)
        assertEquals(0, f.run("listAnnouncements", other = true).items().size)
        assertEquals(404, assertFailsWith<ApiException> { f.run("getAnnouncement", ids = mapOf("announcementId" to announcement.id()), other = true) }.status)
        f.run("markAnnouncementRead", ids = mapOf("announcementId" to announcement.id()))
        assertEquals(0, f.run("adminListAnnouncementReceipts", ids = mapOf("announcementId" to announcement.id()), staff = true, query = mapOf("pending" to "true")).items().size)
        assertEquals(1, f.run("listInbox").items().size)
        assertEquals(0, f.run("listInbox", other = true).items().size)
    }
    @Test fun `internal staff comments never reach resident`() = CommunityFixture().use { f ->
        val ticket = f.run("createServiceRequest", obj("title" to "Vazamento", "description" to "Cano vazando", "category" to "maintenance"))
        val ids = mapOf("ticketId" to ticket.id())
        f.run("adminCommentTicket", obj("body" to "Informação interna", "internal" to true), ids, staff = true)
        f.run("adminCommentTicket", obj("body" to "Equipe a caminho", "internal" to false), ids, staff = true)
        val resident = f.run("getServiceRequest", ids = mapOf("requestId" to ticket.id())).body.jsonObject["comments"]!!.jsonArray
        assertEquals(1, resident.size)
        assertFalse(resident.toString().contains("interna"))
        assertEquals(2, f.run("adminGetTicket", ids = ids, staff = true).body.jsonObject["comments"]!!.jsonArray.size)
        assertEquals(409, assertFailsWith<ApiException> { f.run("updateTicket", obj("status" to "resolved"), ids, staff = true) }.status)
        f.run("updateTicket", obj("status" to "in_progress"), ids, staff = true)
        f.run("updateTicket", obj("status" to "resolved", "resolution" to "Reparado"), ids, staff = true)
    }
    @Test fun `work order completion requires valid transition and evidence`() = CommunityFixture().use { f ->
        val order = f.run("adminCreateWorkOrder", obj("title" to "Inspeção", "scheduledAt" to future(3600)), staff = true)
        val ids = mapOf("workOrderId" to order.id())
        assertEquals(409, assertFailsWith<ApiException> { f.run("adminTransitionWorkOrder", obj("status" to "completed", "notes" to "Pronto"), ids, staff = true) }.status)
        f.run("adminTransitionWorkOrder", obj("status" to "in_progress", "notes" to "Iniciando"), ids, staff = true)
        assertEquals(422, assertFailsWith<ApiException> { f.run("adminTransitionWorkOrder", obj("status" to "completed", "notes" to "Pronto"), ids, staff = true) }.status)
        f.run("adminTransitionWorkOrder", obj("status" to "cancelled", "notes" to "Cancelamento solicitado"), ids, staff = true)
        assertEquals(409, assertFailsWith<ApiException> { f.run("adminUpdateWorkOrder", obj("title" to "Reaberta"), ids, staff = true, headers = mapOf("If-Match" to "\"3\"")) }.status)
    }
}
