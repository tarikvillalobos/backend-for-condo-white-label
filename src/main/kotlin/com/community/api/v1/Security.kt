package com.community.api.v1

import com.community.api.core.*
import com.community.api.identity.requireRecentAuthentication
import com.community.api.v1.identity.authenticateV1Session
import kotlinx.serialization.json.*
import java.time.Instant

fun Tx.tenantForBrand(brandId: String): String = connection.prepareStatement("SELECT tenant_id FROM v1_brands WHERE brand_id = ?").use {
    it.setString(1, brandId)
    it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else throw ApiException(404, "RESOURCE_NOT_FOUND", "Brand not found") }
}

fun authorizeV1(tx: Tx, operation: ContractOperation, brandId: String, tenantId: String, headers: Map<String, String>, path: Map<String, String>, input: JsonObject, query: Map<String, String>, requestId: String): V1Context {
    val store = V1Store(tx, tenantId, brandId)
    val brand = store.get("brand", brandId)
    if (brand.data["active"] == JsonPrimitive(false) || tx.get("client", tenantId, tenantId)?.data?.get("active") == JsonPrimitive(false)) throw ApiException(403,"ACCESS_DENIED","Client or brand is suspended")
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
    val security = (operation.definition["security"] ?: Contract.document["security"])!!.jsonArray
    val accepted = security.flatMap { it.jsonObject.keys }.toSet()
    var principal: V1Principal? = null
    if (security.isNotEmpty() && security.none { it.jsonObject.isEmpty() }) {
        val key = header("X-Device-Key")
        if (key != null && "DeviceKey" in accepted) {
            val deviceId = key.substringBefore('.')
            val device = store.find("device", deviceId) ?: throw ApiException(401,"DEVICE_UNKNOWN","Invalid device credential")
            if (device.data.string("status") != "active" || device.data.string("keyHash") != Secrets.hash(key)) throw ApiException(401,"DEVICE_REVOKED","Invalid device credential")
            val permissions = if (device.data.string("type") == "access_reader") setOf("visitors.checkin") else emptySet()
            if (operation.path.startsWith("/ops/access/") && device.data.string("type") != "access_reader") throw ApiException(403,"ACCESS_DENIED","Access reader required")
            principal = V1Principal(deviceId = device.id, permissions = permissions)
            path["lockerId"]?.let { if (device.data.string("lockerId") != it) throw ApiException(404,"RESOURCE_NOT_FOUND","Locker not found") }
        } else {
            if (accepted.none { it in setOf("SessionBearer","StaffBearer") }) throw ApiException(401,"DEVICE_UNKNOWN","Device credential required")
            val bearer = header("Authorization")?.takeIf { it.startsWith("Bearer ",true) }?.substring(7)
                ?: throw ApiException(401,"SESSION_EXPIRED","Authentication required")
            val actor = authenticateV1Session(tx,tenantId,brandId,bearer)
            val metadata = store.get("session",actor.sessionId)
            principal = V1Principal(actor, staff = metadata.data["staff"] == JsonPrimitive(true))
            if (accepted == setOf("StaffBearer") && !principal.staff) throw ApiException(403,"ACCESS_DENIED","Staff session required")
        }
    }
    val membership = if (operation.path.startsWith("/memberships/")) path["membershipId"]?.let {
        store.get("membership",it).also { member ->
            if (member.ownerId != principal?.userId || member.data.string("status") != "active") throw ApiException(404,"RESOURCE_NOT_FOUND","Membership not found")
        }
    } else null
    val location = path["condominiumId"] ?: membership?.locationId ?: input.string("condominiumId") ?: query["condominiumId"]
        ?: path["lockerId"]?.let { store.get("locker",it).locationId }
        ?: path["parcelId"]?.let { store.get("parcel",it).locationId }
        ?: path["arrivalId"]?.let { store.get("arrival",it).locationId }
        ?: input.string("nodeId")?.let { store.get("node",it).locationId }
        ?: input.string("gateId")?.let { store.get("gate",it).locationId }
        ?: principal?.deviceId?.let { store.get("device",it).locationId }
    val provisional = V1Context(tx,operation.id,tenantId,brandId,requestId,input,path,query,headers,principal,location,membership)
    if (location != null) {
        val condo = store.get("condominium",location)
        if (condo.data.string("status") in setOf("suspended","inactive","deleted")) provisional.fail(403,"ACCESS_DENIED","Condominium is unavailable")
    }
    val permissions = if (principal?.userId != null) effectivePermissions(provisional,principal.userId) else principal?.permissions.orEmpty()
    val c = V1Context(tx,operation.id,tenantId,brandId,requestId,input,path,query,headers,principal?.copy(permissions=permissions),location,membership)
    if (principal?.deviceId != null && location != store.get("device",principal.deviceId).locationId) c.fail(404,"RESOURCE_NOT_FOUND","Resource not found")
    operation.definition.string("x-required-permission")?.let { c.requirePermission(*it.split('|').map(String::trim).toTypedArray()) }
    if ("x-step-up" in operation.definition) {
        val actor = principal?.actor ?: c.fail(403,"VERIFICATION_REQUIRED","Verify your identity")
        try { tx.requireRecentAuthentication(actor) } catch (_: ApiException) { c.fail(403,"VERIFICATION_REQUIRED","Verify your identity within the last ten minutes") }
    }
    enforceModule(c,operation)
    return c
}

fun effectivePermissions(c: V1Context, userId: String): Set<String> {
    val grants = mutableSetOf("profile.read","profile.manage","sessions.manage","devices.register")
    c.membership?.let { member ->
        grants += Contract.document["x-permission-catalog"]!!.jsonArray.filter { it.jsonObject.string("audience") == "resident" }.map { it.jsonObject.string("code")!! }
        if (member.data.string("role") !in setOf("owner","tenant")) grants -= "unit.manage"
        (member.data["permissions"] as? JsonArray)?.forEach { grants += it.jsonPrimitive.content }
    }
    if (c.principal?.staff == true) c.store.list("staff_assignment",ownerId=userId).filter { it.data.string("status") == "active" }.forEach { assignment ->
        val orgId = assignment.data.string("organizationId")
        val authorized = when {
            assignment.data.string("scope") == "brand" -> true
            orgId != null -> (c.path["organizationId"] == orgId && c.locationId == null) ||
                c.store.list("organization_condominium",filters=mapOf("organizationId" to orgId,"status" to "active")).any { it.locationId == c.locationId && c.locationId != null }
            else -> c.locationId != null && assignment.locationId == c.locationId
        }
        if (authorized) {
            val role = assignment.data.string("customRoleId")?.let { c.store.find("custom_role",it) }
            ((role?.data?.get("permissions") ?: assignment.data["permissions"]) as? JsonArray)?.forEach { grants += it.jsonPrimitive.content }
        }
    }
    return grants
}

private fun enforceModule(c: V1Context, operation: ContractOperation) {
    val tag = operation.definition["tags"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content ?: return
    val module = when {
        tag.contains("Parcels",true) || tag.contains("Encomendas",true) -> "parcels"
        tag.contains("Reservations",true) || tag.contains("Reservas",true) -> "reservations"
        tag.contains("Cameras",true) || tag.contains("Câmeras",true) -> "cameras"
        tag == "Pets" -> "pets"
        tag == "Access" -> "visitors"
        tag == "Documents" -> "documents"
        else -> return
    }
    val brandModules = c.store.get("brand",c.brandId).data["modules"] as? JsonObject
    val condoModules = c.locationId?.let { c.store.get("condominium",it).data["modules"] as? JsonObject }
    if (brandModules?.get(module) == JsonPrimitive(false) || condoModules?.get(module) == JsonPrimitive(false)) c.fail(403,"MODULE_DISABLED","Module is disabled")
}
