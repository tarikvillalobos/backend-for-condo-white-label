package com.community.api.v1

import com.community.api.core.Database
import com.community.api.core.json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.*

private data class WebhookJob(val tenant:String,val brand:String,val id:String,val url:String,val secret:String,
    val lease:String,val sequence:Long,val eventId:String,val action:String,val occurredAt:String,val target:JsonElement?)
private val webhookClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
    .followRedirects(HttpClient.Redirect.NEVER).build()

fun processWebhooks(db: Database): Int {
    val subscriptions = db.scopedTx(null) { tx ->
        tx.connection.prepareStatement("SELECT tenant_id,payload FROM app_records WHERE kind='v1_webhook' ORDER BY created_at LIMIT 500").use {
            it.executeQuery().use { rows -> buildList {
                while (rows.next()) {
                    val data = json.parseToJsonElement(rows.getString("payload")).jsonObject
                    if (data["active"] == JsonPrimitive(true) && data.string("_deletedAt") == null)
                        add(Triple(rows.getString("tenant_id"),data.string("_brandId")!!,data.string("_id")!!))
                }
            } }
        }
    }
    var delivered = 0
    for ((tenant,brand,id) in subscriptions) {
        val job = claimWebhook(db,tenant,brand,id) ?: continue
        val ok = runCatching { sendWebhook(job) }.getOrDefault(false)
        db.scopedTx("webhook:$tenant:$brand:$id") { tx ->
            val store = V1Store(tx,tenant,brand)
            val row = store.find("webhook",id) ?: return@scopedTx
            if (row.data.string("leaseId") != job.lease) return@scopedTx
            val next = if (ok) obj("lastSequence" to job.sequence,"attempts" to 0,"nextAttemptAt" to null)
                else obj("attempts" to ((row.data["attempts"]?.jsonPrimitive?.intOrNull ?: 0) + 1),
                    "nextAttemptAt" to Instant.now().plusSeconds((30L shl (row.data["attempts"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtMost(7)).coerceAtMost(3600)))
            store.update(row,JsonObject(row.data+next+obj("leaseId" to null,"leaseUntil" to null)))
        }
        if (ok) delivered++
    }
    return delivered
}

private fun claimWebhook(db:Database,tenant:String,brand:String,id:String):WebhookJob? = db.scopedTx("webhook:$tenant:$brand:$id") { tx ->
    val store = V1Store(tx,tenant,brand)
    val row = store.find("webhook",id) ?: return@scopedTx null
    val data = row.data
    val now = Instant.now()
    if (data["active"] != JsonPrimitive(true) || data.string("leaseUntil")?.let { Instant.parse(it).isAfter(now) } == true ||
        data.string("nextAttemptAt")?.let { Instant.parse(it).isAfter(now) } == true) return@scopedTx null
    val events = data["events"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    if (events.isEmpty()) return@scopedTx null
    val placeholders = events.joinToString(",") { "?" }
