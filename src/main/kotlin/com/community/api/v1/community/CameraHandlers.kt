package com.community.api.v1.community

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.time.LocalTime
import java.time.ZoneId

private fun V1Context.cameraAccess(row: Record): Pair<Boolean, Boolean> {
    if (membership == null) return row.data.flag("liveAllowed") to row.data.flag("recordingsAllowed")
    val grant = store.list("camera_grant", locationId, filters = mapOf("cameraId" to row.id, "membershipId" to membershipId!!))
        .firstOrNull { it.data.text("expiresAt")?.let { value -> timestamp(value).isAfter(now) } ?: true }
    if (grant != null) return (row.data.flag("liveAllowed") && grant.data.flag("allowLive")) to (row.data.flag("recordingsAllowed") && grant.data.flag("allowRecordings"))
    val policies = row.data.array("policies")
    if (policies.isEmpty()) return row.data.flag("liveAllowed") to row.data.flag("recordingsAllowed")
    val timezone = store.find("condominium", location())?.data?.text("timeZone") ?: "UTC"
    val local = now.atZone(ZoneId.of(timezone)).toLocalTime()
    val applicable = policies.map { it.jsonObject }.filter { policy ->
        val role = policy.text("role")
        val window = policy["timeWindow"] as? JsonObject
        val timeAllowed = if (window == null) true else {
            val from = LocalTime.parse(window.text("from")); val to = LocalTime.parse(window.text("to"))
            if (from <= to) local >= from && local <= to else local >= from || local <= to
        }
        (role == null || role == membership.data.text("role")) && inSubtree(unitId, policy.text("scopeNodeId")) && timeAllowed
    }
    return (row.data.flag("liveAllowed") && applicable.any { it.flag("allowLive") }) to
        (row.data.flag("recordingsAllowed") && applicable.any { it.flag("allowRecordings") })
}
internal fun V1Context.camera(row: Record, admin: Boolean = false): JsonObject {
    val access = cameraAccess(row)
    return view(if (admin) "CameraAdmin" else "Camera", row, obj("node" to node(row.data.text("nodeId")),
        "status" to (row.data.text("status") ?: "offline"), "liveAllowed" to access.first, "recordingsAllowed" to access.second,
        "thumbnailUrl" to null, "lastFrameAt" to row.data["lastFrameAt"], "gatewayDeviceId" to row.data["gatewayDeviceId"],
        "retentionDays" to row.data.number("retentionDays"), "policies" to row.data.array("policies"),
        "grantsCount" to store.list("camera_grant", locationId, filters = mapOf("cameraId" to row.id)).size))
}
internal fun V1Context.requireCamera(recordings: Boolean = false): Record {
    val row = store.get("camera", id("cameraId"), locationId)
    val permissions = cameraAccess(row)
    if (!(if (recordings) permissions.second else permissions.first)) fail(403, "CAMERA_ACCESS_DENIED", "Acesso à câmera não permitido")
    return row
}
private fun V1Context.validateCamera(data: JsonObject) {
    checkedNode(data)
    if (data.text("providerRef")?.contains("://") == true) fail(422, "CAMERA_PROVIDER_REF_INVALID", "Informe o identificador do provedor, sem URL ou credenciais")
    data.text("gatewayDeviceId")?.let { store.get("device", it, locationId) }
}
internal fun cameraHandlers(): Map<String, V1Handler> = mapOf(
    "listCameras" to V1Handler { c -> c.listResponse("camera") { row ->
        val access = c.cameraAccess(row)
        if (access.first || access.second) c.camera(row) else JsonNull
    } },
    "getCamera" to V1Handler { c ->
        val row = c.store.get("camera", c.id("cameraId"), c.locationId)
        val access = c.cameraAccess(row)
        if (!access.first && !access.second) c.fail(404, "NOT_FOUND", "Câmera não encontrada")
        V1Response(c.camera(row))
    },
    "adminListCameras" to V1Handler { c -> c.listResponse("camera") { c.camera(it, true) } },
