package com.community.api.v1.community

import com.community.api.core.*
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID
import kotlin.test.assertTrue

internal class CommunityFixture : AutoCloseable {
    val db = Database.memory()
    val tenant = UUID.randomUUID().toString()
    val brand = UUID.randomUUID().toString()
    val condo = UUID.randomUUID().toString()
    val user = UUID.randomUUID().toString()
    val otherUser = UUID.randomUUID().toString()
    val unit = UUID.randomUUID().toString()
    val otherUnit = UUID.randomUUID().toString()
    val member = UUID.randomUUID().toString()
    val otherMember = UUID.randomUUID().toString()
    init {
        db.tx { tx ->
            val store = V1Store(tx, tenant, brand)
            tx.create("account", tenant, data = obj("name" to "Ana", "active" to true), id = user)
            tx.create("account", tenant, data = obj("name" to "Bruno", "active" to true), id = otherUser)
            store.create("condominium", obj("name" to "Condomínio", "timeZone" to "America/Sao_Paulo"), condo, id = condo)
            store.create("node", obj("label" to "101", "type" to "unit", "parentId" to null), condo, id = unit)
            store.create("node", obj("label" to "102", "type" to "unit", "parentId" to null), condo, id = otherUnit)
            store.create("membership", obj("userId" to user, "nodeId" to unit, "status" to "active", "role" to "resident"), condo, user, member)
            store.create("membership", obj("userId" to otherUser, "nodeId" to otherUnit, "status" to "active", "role" to "resident"), condo, otherUser, otherMember)
        }
    }
    fun run(operation: String, input: JsonObject = obj(), ids: Map<String, String> = emptyMap(),
        staff: Boolean = false, other: Boolean = false, query: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap(), device: String? = null,
    ): V1Response = db.tx { tx ->
        val selectedUser = if (other) otherUser else user
        val selectedMember = if (other) otherMember else member
        val membership = if (staff || device != null) null else V1Store(tx, tenant, brand).get("membership", selectedMember, condo)
        val path = ids + if (membership == null) mapOf("condominiumId" to condo) else mapOf("membershipId" to selectedMember)
        val principal = if (device == null) V1Principal(Actor(selectedUser, tenant, "session"), staff = staff,
