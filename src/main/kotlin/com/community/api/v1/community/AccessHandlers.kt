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
