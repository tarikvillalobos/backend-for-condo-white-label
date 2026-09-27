package com.community.api.v1.identity

import com.community.api.core.*
import com.community.api.identity.*
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID

suspend fun processIdentityDataRequests(db: Database): Int {
    val pending = db.query { tx ->
        tx.connection.prepareStatement("SELECT * FROM app_records WHERE kind = 'v1_data_request' " +
            "AND payload LIKE '%\"status\":\"received\"%' AND payload LIKE '%\"kind\":\"deletion\"%' " +
            "ORDER BY created_at LIMIT 100").use { statement ->
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.v1Record()) } }
        }
    }
    var completed = 0
    for (raw in pending) {
        val executeAfter = raw.data.string("executeAfter")?.let(Instant::parse) ?: continue
