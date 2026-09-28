package com.community.api.v1

import com.community.api.core.Database
import java.util.UUID
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlin.test.Test
import kotlin.test.assertEquals

class WebhookWorkerTest {
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
