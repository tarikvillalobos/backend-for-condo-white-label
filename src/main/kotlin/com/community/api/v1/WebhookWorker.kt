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
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.*

private data class WebhookJob(val tenant:String,val brand:String,val id:String,val url:String,val secret:String,
    val lease:String,val sequence:Long,val eventId:String,val action:String,val occurredAt:String,val target:JsonElement?)
private val webhookAliases = mapOf(
    "parcel.registered" to "parcel.deposited",
    "access_invite.validated" to "invite.used",
    "access_invite.revoked" to "invite.revoked",
    "arrival.created" to "access.arrival_requested",
    "arrival.decided" to "access.arrival_decided",
    "ticket.status_changed" to "request.updated",
    "ticket.commented" to "request.updated",
    "ticket.updated" to "request.updated",
    "reservation.cancelled_by_block" to "reservation.cancelled",
)
private val webhookClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
    .followRedirects(HttpClient.Redirect.NEVER).build()
private val webhookScanAfter = AtomicReference<Pair<Database,Pair<String,String>>?>(null)

fun processWebhooks(db: Database): Int {
    var delivered = 0
    repeat(5) {
        val subscriptions = nextWebhookBatch(db)
        if (subscriptions.isEmpty()) return delivered
        for ((tenant,brand,id) in subscriptions) {
            delivered += processWebhook(db,tenant,brand,id)
        }
    }
    return delivered
}

private fun nextWebhookBatch(db: Database): List<Triple<String,String,String>> = db.scopedTx(null) { tx ->
    val after = webhookScanAfter.get()?.takeIf { it.first === db }?.second
    val filter = if (tx.postgres) "payload::jsonb ->> 'active' = 'true' AND payload::jsonb ->> '_deletedAt' IS NULL"
        else "payload LIKE '%\"active\":true%' AND payload NOT LIKE '%\"_deletedAt\"%'"
    val cursor = if (after == null) "" else " AND (created_at > ? OR (created_at = ? AND id > ?))"
    val sql = "SELECT tenant_id,payload,created_at,id FROM app_records WHERE kind='v1_webhook' AND $filter$cursor ORDER BY created_at,id LIMIT 500"
    val rows = tx.connection.prepareStatement(sql).use { statement ->
        if (after != null) { statement.setString(1,after.first); statement.setString(2,after.first); statement.setString(3,after.second) }
        statement.executeQuery().use { result -> buildList {
            while (result.next()) add(Triple(result.getString("tenant_id"),result.getString("payload"),
                result.getString("created_at") to result.getString("id")))
        } }
    }
    webhookScanAfter.set(rows.lastOrNull()?.third?.let { db to it })
    rows.mapNotNull { (tenant,payload,_) ->
        val data = json.parseToJsonElement(payload).jsonObject
        if (data["active"] == JsonPrimitive(true) && data.string("_deletedAt") == null)
            Triple(tenant,data.string("_brandId")!!,data.string("_id")!!) else null
    }
}

private fun processWebhook(db: Database, tenant: String, brand: String, id: String): Int {
        val job = claimWebhook(db,tenant,brand,id) ?: return 0
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
        return if (ok) 1 else 0
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
    val observed = (events + webhookAliases.filterValues { it in events }.keys).distinct()
    val placeholders = observed.joinToString(",") { "?" }
    val scope = if (row.locationId == null) "" else " AND location_id=?"
    val sql = "SELECT sequence,id,action,created_at,payload FROM audit_log WHERE tenant_id=? AND brand_id=? AND sequence>? AND created_at>=?$scope AND action IN ($placeholders) ORDER BY sequence LIMIT 1"
    val result = tx.connection.prepareStatement(sql).use { statement ->
        val values = listOf(tenant,brand,data["lastSequence"]?.jsonPrimitive?.longOrNull ?: 0L) +
            (row.locationId?.let(::listOf) ?: emptyList()) + observed
        values.forEachIndexed { index,value -> statement.setObject(index+1,value) }
        statement.executeQuery().use { rows -> if (rows.next()) listOf(rows.getLong(1),rows.getString(2),rows.getString(3),rows.getString(4),rows.getString(5)) else null }
    } ?: return@scopedTx null
    val lease = UUID.randomUUID().toString()
    store.update(row,JsonObject(data+obj("leaseId" to lease,"leaseUntil" to now.plusSeconds(30))))
    val payload = json.parseToJsonElement(result[4].toString()).jsonObject
    WebhookJob(tenant,brand,id,data.string("url")!!,Secrets.unseal(data.string("sealedSecret")!!),lease,
        result[0] as Long,result[1].toString(),webhookAliases[result[2].toString()] ?: result[2].toString(),result[3].toString(),payload["target"])
}

private fun sendWebhook(job:WebhookJob):Boolean {
    validateWebhookUrl(job.url)
    val timestamp = Instant.now().epochSecond.toString()
    val body = obj("id" to job.eventId,"event" to job.action,"occurredAt" to job.occurredAt,
        "brandId" to job.brand,"target" to job.target).toString()
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(job.secret.toByteArray(),"HmacSHA256")) }
    val signature = mac.doFinal("$timestamp.$body".toByteArray()).joinToString("") { "%02x".format(it) }
    val request = HttpRequest.newBuilder(URI(job.url)).timeout(Duration.ofSeconds(5))
        .header("Content-Type","application/json").header("X-Community-Event-Id",job.eventId)
        .header("X-Community-Timestamp",timestamp).header("X-Community-Signature","sha256=$signature")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build()
    return webhookClient.send(request,HttpResponse.BodyHandlers.discarding()).statusCode() in 200..299
}
