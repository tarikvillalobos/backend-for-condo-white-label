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
        V1Response(c.project("WebhookSubscription",row.document()+obj("secret" to secret)),201)
    },
    "deleteWebhook" to V1Handler { c ->
        val row = c.store.get("webhook",c.path.getValue("webhookId"))
        c.store.update(row,JsonObject(row.data+obj("active" to false,"sealedSecret" to null,"leaseUntil" to null)))
        V1Response(status=204)
    },
)

internal fun validateWebhookUrl(value: String) {
    val uri = runCatching { URI(value) }.getOrElse { throw com.community.api.core.ApiException(422,"VALIDATION_ERROR","Invalid webhook URL") }
    if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null || uri.port !in setOf(-1,443))
        throw com.community.api.core.ApiException(422,"VALIDATION_ERROR","HTTPS webhook on port 443 required")
    val addresses = runCatching { InetAddress.getAllByName(uri.host) }.getOrElse { throw com.community.api.core.ApiException(422,"VALIDATION_ERROR","Webhook host cannot be resolved") }
    if (addresses.isEmpty() || addresses.any { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress })
        throw com.community.api.core.ApiException(422,"VALIDATION_ERROR","Webhook host must resolve to public addresses")
}
