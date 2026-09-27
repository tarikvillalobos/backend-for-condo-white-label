package com.community.api.v1.community

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.time.LocalTime
import java.time.ZoneId

private fun V1Context.cameraAccess(row: Record): Pair<Boolean, Boolean> {
    if (membership == null) return row.data.flag("liveAllowed") to row.data.flag("recordingsAllowed")
    val grant = store.list("camera_grant", locationId, filters = mapOf("cameraId" to row.id, "membershipId" to membershipId!!))
        .firstOrNull { it.data.text("expiresAt")?.let { value -> timestamp(value).isAfter(now) } ?: true }
    if (grant != null) return (row.data.flag("liveAllowed") && grant.data.flag("allowLive")) to (row.data.flag("recordingsAllowed") && grant.data.flag("allowRecordings"))
    val policies = row.data.array("policies")
    if (policies.isEmpty()) return row.data.flag("liveAllowed") to row.data.flag("recordingsAllowed")
    val timezone = store.find("condominium", location())?.data?.text("timeZone") ?: "UTC"
    val local = now.atZone(ZoneId.of(timezone)).toLocalTime()
    val applicable = policies.map { it.jsonObject }.filter { policy ->
        val role = policy.text("role")
        val window = policy["timeWindow"] as? JsonObject
