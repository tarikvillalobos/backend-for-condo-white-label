package com.community.api.v1.community

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.util.UUID

internal fun V1Context.workOrder(row: Record): JsonObject {
    fun named(kind: String, field: String): JsonElement = row.data.text(field)?.let { id ->
        store.find(kind, id, locationId)?.let { obj("id" to it.id, "name" to it.data["name"]) }
    } ?: JsonNull
    return view("WorkOrder", row, obj("description" to row.data["description"], "node" to node(row.data.text("nodeId")),
        "equipment" to named("equipment", "equipmentId"), "contractor" to named("contractor", "contractorId"),
        "assigneeName" to row.data.text("assigneeUserId")?.let { personName(it) }, "ticketId" to row.data["ticketId"],
        "completedAt" to row.data["completedAt"], "cost" to row.data["cost"], "history" to JsonArray(row.data.array("history").map {
            val entry = it.jsonObject
            JsonObject(entry - "evidenceKeys").merge(obj("evidenceUrls" to JsonArray(entry.array("evidenceKeys").map { key -> JsonPrimitive(fileUrl(key.jsonPrimitive.content)) })))
        })))
}
private fun V1Context.validateWorkOrder(data: JsonObject) {
    checkedNode(data)
    data.text("equipmentId")?.let { if (!store.get("equipment", it, locationId).data.flag("active", true)) fail(422, "EQUIPMENT_INACTIVE", "Equipamento inativo") }
    data.text("contractorId")?.let { if (!store.get("contractor", it, locationId).data.flag("approved")) fail(422, "CONTRACTOR_NOT_APPROVED", "Prestador não aprovado") }
    requireStaffUser(data.text("assigneeUserId"))
}
internal fun V1Context.createWorkOrder(data: JsonObject): Record {
    validateWorkOrder(data)
    return save("work_order", data.merge(obj("status" to "scheduled", "priority" to (data.text("priority") ?: "normal"),
        "reference" to "OS-${UUID.randomUUID().toString().take(8).uppercase()}", "history" to JsonArray(emptyList()))))
}
private fun V1Context.equipment(row: Record) = view("Equipment", row, obj("node" to node(row.data.text("nodeId")),
    "description" to row.data["description"], "serialNumber" to row.data["serialNumber"], "manufacturer" to row.data["manufacturer"],
    "installedAt" to row.data["installedAt"], "lastInspectionAt" to row.data["lastInspectionAt"], "nextInspectionAt" to row.data["nextInspectionAt"],
    "inspectionIntervalDays" to row.data["inspectionIntervalDays"], "active" to row.data.flag("active", true)))
private fun V1Context.contractor(row: Record) = view("Contractor", row, obj("document" to row.data.text("document")?.let { "***${it.takeLast(4)}" },
    "phone" to row.data["phone"], "email" to row.data["email"], "notes" to row.data["notes"]))
internal fun maintenanceHandlers(): Map<String, V1Handler> = mapOf(
    "adminListContractors" to V1Handler { c -> V1Response(obj("items" to JsonArray(c.store.list("contractor", c.locationId).map { c.contractor(it) }))) },
    "adminCreateContractor" to V1Handler { c -> V1Response(c.contractor(c.save("contractor", c.input)), 201) },
    "adminUpdateContractor" to V1Handler { c -> V1Response(c.contractor(c.change(c.store.get("contractor", c.id("contractorId"), c.locationId), c.input))) },
    "adminListEquipment" to V1Handler { c -> V1Response(obj("items" to JsonArray(c.store.list("equipment", c.locationId).filter {
        c.query["nodeId"]?.let { scope -> c.inSubtree(it.data.text("nodeId"), scope) } ?: true
    }.map { c.equipment(it) }))) },
    "adminCreateEquipment" to V1Handler { c ->
        c.checkedNode()
        V1Response(c.equipment(c.save("equipment", c.input.merge(obj("active" to c.input.flag("active", true))))), 201)
    },
    "adminUpdateEquipment" to V1Handler { c ->
        val row = c.store.get("equipment", c.id("equipmentId"), c.locationId)
        c.checkedNode(row.data.merge(c.input))
        V1Response(c.equipment(c.change(row, c.input)))
    },
    "adminListWorkOrders" to V1Handler { c -> c.listResponse("work_order") { c.workOrder(it) } },
    "adminCreateWorkOrder" to V1Handler { c -> V1Response(c.workOrder(c.createWorkOrder(c.input)), 201) },
    "adminGetWorkOrder" to V1Handler { c ->
        val row = c.store.get("work_order", c.id("workOrderId"), c.locationId)
        V1Response(c.workOrder(row), headers = mapOf("ETag" to "\"${row.version}\""))
    },
    "adminUpdateWorkOrder" to V1Handler { c ->
        val row = c.store.get("work_order", c.id("workOrderId"), c.locationId)
        if (row.data.text("status") in setOf("completed", "cancelled")) c.fail(409, "WORK_ORDER_CLOSED", "Ordem encerrada")
        c.validateWorkOrder(row.data.merge(c.input))
        V1Response(c.workOrder(c.change(row, c.input)))
    },
    "adminTransitionWorkOrder" to V1Handler { c -> c.transitionWorkOrder() },
    "listShiftNotes" to V1Handler { c -> c.listResponse("shift_note") { row ->
        if (c.query["incidentOnly"] == "true" && !row.data.flag("incident")) JsonNull else c.view("ShiftNote", row)
    } },
    "createShiftNote" to V1Handler { c ->
        val nodeId = c.checkedNode()
        val row = c.save("shift_note", c.input.merge(obj("node" to c.node(nodeId), "nodeId" to nodeId,
            "relatedId" to c.input["relatedId"], "authorName" to c.personName(), "organizationName" to null)))
        c.result("ShiftNote", row, 201)
    },
)
private fun V1Context.transitionWorkOrder(): V1Response {
    val row = store.get("work_order", id("workOrderId"), locationId)
    if (principal?.permissions?.let { "*" !in it && "maintenance.manage" !in it } == true && row.data.text("assigneeUserId") != userId)
        fail(403, "WORK_ORDER_NOT_ASSIGNED", "Esta ordem não está atribuída a você")
    val next = input.text("status")!!
    if (next !in workOrderTransitions[row.data.text("status")].orEmpty()) fail(409, "WORK_ORDER_INVALID_TRANSITION", "Transição inválida")
    val keys = input.array("evidenceKeys")
    if (next == "completed" && keys.isEmpty()) fail(422, "WORK_ORDER_EVIDENCE_REQUIRED", "Conclusão exige evidência")
    val evidence = JsonArray(keys.map { JsonPrimitive(fileUrl(it.jsonPrimitive.content)) })
    val entry = obj("status" to next, "notes" to input["notes"], "evidenceKeys" to keys, "byName" to personName(), "at" to now.toString())
    val updated = change(row, obj("status" to next, "completedAt" to if (next == "completed") now.toString() else null,
        "history" to JsonArray(row.data.array("history") + entry)), "work_order.status_changed")
    if (next == "completed") row.data.text("equipmentId")?.let { id ->
        val equipment = store.get("equipment", id, locationId)
        val day = LocalDate.now()
        change(equipment, obj("lastInspectionAt" to day.toString(), "nextInspectionAt" to equipment.data.text("inspectionIntervalDays")?.toLongOrNull()?.let { day.plusDays(it).toString() }), "equipment.inspected")
    }
    return V1Response(workOrder(updated))
}
