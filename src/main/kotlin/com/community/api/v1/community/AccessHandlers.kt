package com.community.api.v1.community

import com.community.api.v1.*
import kotlinx.serialization.json.*

internal fun accessHandlers(): Map<String, V1Handler> = mapOf(
    "validateAccessCredential" to V1Handler { c -> c.validateAccess() },
    "recordAccessEvent" to V1Handler { c -> c.recordAccess() },
    "listAccessEvents" to V1Handler { c -> c.listResponse("access_event") { c.view("AccessEvent", it) } },
    "adminListAccessEvents" to V1Handler { c -> c.listResponse("access_event") { c.view("AccessEvent", it) } },
    "adminListGates" to V1Handler { c -> V1Response(obj("items" to JsonArray(c.store.list("gate", c.locationId).map {
        c.view("Gate", it, obj("node" to c.node(it.data.text("nodeId")), "deviceId" to it.data["deviceId"]))
    }))) },
    "adminCreateGate" to V1Handler { c ->
        c.validateGate(c.input)
        val row = c.save("gate", c.input.merge(obj("active" to c.input.flag("active", true))))
        c.result("Gate", row, 201, obj("node" to c.node(row.data.text("nodeId")), "deviceId" to row.data["deviceId"]))
    },
    "adminUpdateGate" to V1Handler { c ->
        val row = c.store.get("gate", c.id("gateId"), c.locationId)
        c.validateGate(row.data.merge(c.input))
        c.result("Gate", c.change(row, c.input), extras = obj("node" to c.node(row.data.merge(c.input).text("nodeId")), "deviceId" to row.data.merge(c.input)["deviceId"]))
    },
)
private fun V1Context.validateGate(data: JsonObject) {
    checkedNode(data)
    data.text("deviceId")?.let {
        val device = store.get("device", it, locationId)
        if (device.data.text("status") == "revoked" || device.data.text("kind") == "locker") fail(422, "INVALID_GATE_DEVICE", "Dispositivo inválido para controle de acesso")
    }
}
private fun V1Context.recordAccess(): V1Response {
    val gate = store.get("gate", input.text("gateId")!!, locationId)
    if (!gate.data.flag("active", true)) fail(409, "GATE_INACTIVE", "Portão inativo")
    if (principal?.deviceId != null && gate.data.text("deviceId") != principal?.deviceId) fail(403, "DEVICE_GATE_MISMATCH", "Dispositivo não autorizado neste portão")
    if (timestamp(input.text("occurredAt")!!).isAfter(now.plusSeconds(300))) fail(422, "INVALID_EVENT_TIME", "Evento no futuro")
    val invite = input.text("inviteId")?.let { store.get("access_invite", it, locationId) }
    val visitorId = input.text("visitorId") ?: invite?.data?.text("visitorId")
    if (invite != null && input.text("visitorId")?.let { it != invite.data.text("visitorId") } == true)
        fail(422, "ACCESS_SUBJECT_MISMATCH", "Visitante não corresponde ao convite")
