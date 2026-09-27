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
