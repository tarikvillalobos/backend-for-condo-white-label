package com.community.api.v1.community

import com.community.api.core.Record
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.util.UUID

private fun V1Context.organizationCondos(): List<Record> {
    val org = id("organizationId")
    store.get("organization", org)
    return store.list("organization_condominium", filters = mapOf("organizationId" to org, "status" to "active"))
        .mapNotNull { it.locationId }.distinct().map { store.get("condominium", it) }
        .filter { query["condominiumId"]?.let { requested -> it.id == requested } ?: true }
}
internal fun V1Context.atCondominium(id: String, operation: String = operationId) = V1Context(tx, operation, tenantId, brandId, requestId,
    input, path + ("condominiumId" to id), query, headers, principal, id, null, now)
internal fun V1Context.organizationDashboard(): V1Response {
    val rows = organizationCondos().map { condo -> obj("condominiumId" to condo.id, "name" to condo.data["name"],
        "dashboard" to atCondominium(condo.id).adminDashboard()) }
