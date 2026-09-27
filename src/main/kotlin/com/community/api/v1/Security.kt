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
    if (security.isNotEmpty()) {
        val key = header("X-Device-Key")
        if (key != null && "DeviceKey" in accepted) {
            val deviceId = key.substringBefore('.')
            val device = store.find("device", deviceId) ?: throw ApiException(401,"DEVICE_UNKNOWN","Invalid device credential")
            if (device.data.string("status") != "active" || device.data.string("keyHash") != Secrets.hash(key)) throw ApiException(401,"DEVICE_REVOKED","Invalid device credential")
            principal = V1Principal(deviceId = device.id)
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
