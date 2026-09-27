package com.community.api.v1

import com.community.api.core.*
import com.community.api.v1.identity.indexIdentityAccount
import kotlinx.serialization.json.*
import java.time.Instant

fun bootstrapV1(tx: Tx, tenantId: String, userId: String, name: String, brandId: String = tenantId) {
    val existing = tx.connection.prepareStatement("SELECT tenant_id FROM v1_brands WHERE brand_id = ?").use {
        it.setString(1,brandId); it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    }
    if (existing != null) { require(existing == tenantId); return }
    tx.connection.prepareStatement("INSERT INTO v1_brands (brand_id,tenant_id) VALUES (?,?)").use {
        it.setString(1,brandId); it.setString(2,tenantId); it.executeUpdate()
    }
    val store = V1Store(tx,tenantId,brandId)
    val modules = JsonObject(Contract.schemas["Modules"]!!.jsonObject["properties"]!!.jsonObject.keys.associateWith { JsonPrimitive(true) })
    store.create("brand",obj("name" to name,"active" to true,"primaryColor" to "#2563EB","logoFileKey" to null,"logoUrl" to null,
        "apps" to listOf(obj("application" to "condo","name" to name,"bundleId" to null,"storeUrl" to null),obj("application" to "smartlocker","name" to name,"bundleId" to null,"storeUrl" to null)),
        "authMethods" to listOf("password","otp","invitation"),"modules" to modules,"support" to obj("email" to null,"phone" to null,"whatsapp" to null),
