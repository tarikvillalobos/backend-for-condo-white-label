package com.community.api.v1.deliveries

import com.community.api.core.Database
import com.community.api.v1.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ParcelDeadlineWorkerTest {
    @Test fun `near deadline creates one notification and one audit event`() = Database.memory().use { db ->
        val now = Instant.parse("2030-01-01T12:00:00Z")
        db.scopedTx(null) { tx ->
            val store = V1Store(tx,"tenant","brand")
            store.create("membership",obj("status" to "active"),"condo","user",id="member")
            store.create("parcel",obj("status" to "waiting", "deadline" to now.plusSeconds(3600),
                "membershipId" to "member", "carrier" to "Postal"),"condo")
            store.create("parcel",obj("status" to "waiting", "deadline" to now.plusSeconds(172800),
                "membershipId" to "member", "carrier" to "Postal"),"condo")
        }
        assertEquals(1,processParcelDeadlines(db,now))
        assertEquals(0,processParcelDeadlines(db,now.plusSeconds(10)))
        db.scopedTx(null) { tx ->
            val store = V1Store(tx,"tenant","brand")
            assertEquals(1,store.list("notification","condo").size)
            val events = tx.connection.prepareStatement("SELECT COUNT(*) FROM audit_log WHERE action='parcel.deadline_near'")
                .use { it.executeQuery().use { rows -> rows.next(); rows.getInt(1) } }
            assertEquals(1,events)
        }
    }
}
