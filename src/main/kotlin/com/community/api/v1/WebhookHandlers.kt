package com.community.api.v1

import java.net.InetAddress
import java.net.URI
import kotlinx.serialization.json.*

fun webhookHandlers(): Map<String,V1Handler> = mapOf(
    "createWebhook" to V1Handler { c ->
        val url = c.input.string("url") ?: c.fail(422,"VALIDATION_ERROR","Webhook URL required")
        validateWebhookUrl(url)
        val location = c.input.string("condominiumId")
        location?.let { c.store.get("condominium",it) }
        val secret = Secrets.token()
        val sequence = c.tx.connection.prepareStatement("SELECT COALESCE(MAX(sequence),0) FROM audit_log WHERE tenant_id=? AND brand_id=?").use {
            it.setString(1,c.tenantId); it.setString(2,c.brandId)
            it.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
        }
        val row = c.store.create("webhook",obj("url" to url,"events" to c.input["events"],"condominiumId" to location,
            "active" to true,"sealedSecret" to c.seal(secret),"lastSequence" to sequence,"attempts" to 0,
            "nextAttemptAt" to null,"leaseUntil" to null),location)
