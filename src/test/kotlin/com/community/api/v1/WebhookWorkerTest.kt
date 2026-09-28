package com.community.api.v1

import com.community.api.core.Database
import java.util.UUID
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class WebhookWorkerTest {
    @Test fun `webhook body and signature follow published contract`() {
        val id = UUID.randomUUID().toString()
        val brand = UUID.randomUUID().toString()
        val body = webhookEnvelope(id,"parcel.deposited",java.time.Instant.now().toString(),brand,null,
            obj("target" to obj("type" to "parcel","id" to id)))
        Contract.validate(Contract.schemas.getValue("WebhookEnvelope").jsonObject,body)
        assertEquals("bac79868ba85cd66bf48432a71b8667a28af2222a6933283ee0df24af0c496d3",
            webhookSignature("secret","1700000000","abc"))
    }

    @Test fun `events older than retry window are skipped`() = Database.memory().use { db ->
        var id = ""
        db.scopedTx(null) { tx ->
            val store = V1Store(tx,"tenant","brand")
            id = store.create("webhook",obj("active" to true,"events" to listOf("target"),
                "url" to "https://127.0.0.1/hook","sealedSecret" to Secrets.seal("secret"),
                "lastSequence" to 0,"attempts" to 0,"leaseUntil" to null,"nextAttemptAt" to null)).id
            appendAudit(V1Context(tx,"testWebhook","tenant","brand",UUID.randomUUID().toString()),"target")
            tx.connection.prepareStatement("UPDATE audit_log SET created_at=? WHERE action='target'").use {
                it.setString(1,auditAt(java.time.Instant.now().minusSeconds(172800)));it.executeUpdate()
            }
        }
        assertEquals(0,processWebhooks(db))
        db.scopedTx(null) { tx ->
            assertEquals(0,V1Store(tx,"tenant","brand").get("webhook",id).data["attempts"]!!.jsonPrimitive.int)
        }
    }

    @Test fun `subscriptions beyond first batch are processed`() = Database.memory().use { db ->
        var lastId = ""
        db.scopedTx(null) { tx ->
            val store = V1Store(tx, "tenant", "brand")
            repeat(501) { index ->
                val row = store.create("webhook", obj("active" to true, "events" to listOf(if (index == 500) "target" else "other"),
                    "url" to "https://127.0.0.1/hook", "sealedSecret" to Secrets.seal("test-secret"),
                    "lastSequence" to 0, "attempts" to 0, "leaseUntil" to null, "nextAttemptAt" to null))
                if (index == 500) lastId = row.id
            }
            appendAudit(V1Context(tx, "testWebhook", "tenant", "brand", UUID.randomUUID().toString()), "target")
        }
        assertEquals(0, processWebhooks(db))
        db.scopedTx(null) { tx ->
            val last = V1Store(tx, "tenant", "brand").get("webhook", lastId)
            assertEquals(1, last.data["attempts"]!!.jsonPrimitive.int)
        }
    }
}
