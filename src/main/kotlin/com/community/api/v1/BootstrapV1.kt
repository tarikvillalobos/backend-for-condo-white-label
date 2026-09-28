package com.community.api.v1

import com.community.api.core.*
import com.community.api.v1.identity.indexIdentityAccount
import kotlinx.serialization.json.*
import java.time.Instant

fun bootstrapV1(tx: Tx, tenantId: String, userId: String, name: String, brandId: String = tenantId) {
    val existing = tx.connection.prepareStatement("SELECT tenant_id FROM v1_brands WHERE brand_id = ?").use {
        it.setString(1,brandId); it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    }
    if (existing != null) {
        require(existing == tenantId)
        val store = V1Store(tx,tenantId,brandId)
        store.find("brand",brandId)?.let { brand ->
            val support = brand.data["support"]?.jsonObject ?: obj()
            val complete = JsonObject(obj("email" to null,"phone" to null,"whatsapp" to null,"hours" to null,
                "privacyPolicyUrl" to null,"termsUrl" to null) + support)
            if (complete != support) store.update(brand,JsonObject(brand.data+obj("support" to complete)))
        }
        return
    }
    tx.connection.prepareStatement("INSERT INTO v1_brands (brand_id,tenant_id) VALUES (?,?)").use {
        it.setString(1,brandId); it.setString(2,tenantId); it.executeUpdate()
    }
    val store = V1Store(tx,tenantId,brandId)
    val modules = JsonObject(Contract.schemas["Modules"]!!.jsonObject["properties"]!!.jsonObject.keys.associateWith { JsonPrimitive(true) })
    store.create("brand",obj("name" to name,"active" to true,"primaryColor" to "#2563EB","logoFileKey" to null,"logoUrl" to null,
        "apps" to listOf(obj("application" to "condo","name" to name,"bundleId" to null,"storeUrl" to null),obj("application" to "smartlocker","name" to name,"bundleId" to null,"storeUrl" to null)),
        "authMethods" to listOf("password","otp","invitation"),"modules" to modules,"support" to obj("email" to null,"phone" to null,"whatsapp" to null,"hours" to null,
        "privacyPolicyUrl" to null,"termsUrl" to null),
        "termsVersion" to null,"auditRetentionMonths" to 60),id=brandId)
    store.create("staff_assignment",obj("userId" to userId,"brandId" to brandId,"role" to "brand_admin","scope" to "brand",
        "permissions" to listOf("*"),"status" to "active","mfaRequired" to false,"startedAt" to Instant.now(),"endedAt" to null),ownerId=userId)
    indexIdentityAccount(tx,tx.get("account",userId,tenantId)!!)
}

fun migrateLegacyBrands(db: Database) {
    db.tx { tx -> tx.clients().forEach { client ->
        val admin = tx.list("membership",client.id).firstOrNull { it.data.string("role") == "client_admin" }?.ownerId
        if (admin != null) bootstrapV1(tx,client.id,admin,client.data.string("name") ?: "Community")
    } }
}
