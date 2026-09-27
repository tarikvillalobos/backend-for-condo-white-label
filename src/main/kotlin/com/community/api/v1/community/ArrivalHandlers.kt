package com.community.api.v1.community

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*

private fun V1Context.arrivalStatus(row: Record) = if (row.data.text("status") == "pending" && !timestamp(row.data.text("expiresAt")!!).isAfter(now)) "expired" else row.data.text("status")
private fun V1Context.arrival(row: Record): JsonObject {
    val nodeId = row.data.text("nodeId")!!
    val parents = mutableListOf<JsonElement>()
    var current: String? = nodeId
    val seen = mutableSetOf<String>()
    while (current != null && seen.add(current)) {
        parents.add(0, node(current))
        current = store.get("node", current, locationId).data.text("parentId")
    }
    return view("Arrival", row, obj("condominiumId" to row.locationId, "node" to node(nodeId), "nodePath" to JsonArray(parents),
        "gateName" to row.data.text("gateId")?.let { store.find("gate", it, locationId)?.data?.get("name") },
        "visitorDocumentLast4" to row.data.text("visitorDocumentLast4"), "company" to row.data["company"],
        "photoUrl" to row.data.text("photoKey")?.let { fileUrl(it) }, "status" to arrivalStatus(row),
        "decidedByName" to row.data["decidedByName"], "decidedByKind" to row.data["decidedByKind"], "decisionNote" to row.data["decisionNote"], "decidedAt" to row.data["decidedAt"]))
}
private fun V1Context.arrivalRecord(): Record = store.get("arrival", id("arrivalId"), locationId).also {
    if (membershipId != null && !inSubtree(it.data.text("nodeId"), unitId)) fail(404, "NOT_FOUND", "Chegada não encontrada")
}
private fun V1Context.arrivalList(own: Boolean): V1Response = listResponse("arrival") { row ->
    if ((own && !inSubtree(row.data.text("nodeId"), unitId)) || query["status"]?.let { it != arrivalStatus(row) } == true) JsonNull else arrival(row)
}
internal fun arrivalHandlers(): Map<String, V1Handler> = mapOf(
    "createArrival" to V1Handler { c ->
        val nodeId = c.checkedNode(required = true)!!
        val gate = c.input.text("gateId")?.let { c.store.get("gate", it, c.locationId) }
        if (gate != null && !gate.data.flag("active", true)) c.fail(409, "GATE_INACTIVE", "Portão inativo")
        c.input.text("photoKey")?.let { c.store.get("upload", it, c.locationId) }
        val settings = c.store.find("condominium", c.location())?.data?.get("settings") as? JsonObject
        val duration = (settings?.number("arrival_timeout_seconds", 300) ?: 300).coerceIn(30, 3600)
        val row = c.save("arrival", JsonObject(c.input - "visitorDocument").merge(obj("nodeId" to nodeId, "status" to "pending",
            "visitorDocumentLast4" to c.input.text("visitorDocument")?.takeLast(4), "expiresAt" to c.now.plusSeconds(duration.toLong()).toString())))
        c.members().filter { it.data.text("nodeId") == nodeId }.forEach {
            c.notifyMember(it, "arrival", row.id, "Visitante aguardando autorização", c.input.text("visitorName"))
        }
        V1Response(c.arrival(row), 201)
    },
    "listArrivals" to V1Handler { c -> c.arrivalList(false) },
    "getArrival" to V1Handler { c -> V1Response(c.arrival(c.arrivalRecord())) },
    "listMyArrivals" to V1Handler { c -> c.arrivalList(true) },
    "porterDecideArrival" to V1Handler { c -> c.decideArrival(true) },
    "residentDecideArrival" to V1Handler { c -> c.decideArrival(false) },
)
private fun V1Context.decideArrival(staff: Boolean): V1Response {
    val row = arrivalRecord()
    if (arrivalStatus(row) != "pending") fail(409, "ARRIVAL_ALREADY_DECIDED", "Chegada expirada ou já decidida")
    if (staff && input.text("contactMethod") == null) fail(422, "CONTACT_METHOD_REQUIRED", "Informe como confirmou com o morador")
    val updated = change(row, obj("status" to if (input.text("decision") == "approve") "approved" else "denied",
        "decidedAt" to now.toString(), "decidedByName" to personName(), "decidedByKind" to if (staff) "staff" else "resident",
        "decisionNote" to input["note"], "contactMethod" to input["contactMethod"], "decidedByUserId" to userId), "arrival.decided")
    return V1Response(arrival(updated))
}
