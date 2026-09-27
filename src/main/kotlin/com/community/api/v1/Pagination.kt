package com.community.api.v1

import com.community.api.core.Record
import com.community.api.core.json
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.Base64
import java.util.UUID

private data class Snapshot(val id: String, val at: Long, val expires: Long, val lastCreated: String = "", val lastId: String = "")

fun V1Context.page(
    kind: String,
    locationId: String? = this.locationId,
    ownerId: String? = null,
    filters: Map<String, String> = emptyMap(),
    predicate: (Record) -> Boolean = { true },
    transform: (Record) -> JsonElement,
): JsonObject {
    val limit = query["limit"]?.toIntOrNull() ?: 20
