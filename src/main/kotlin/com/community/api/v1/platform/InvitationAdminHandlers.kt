package com.community.api.v1.platform

import com.community.api.core.Record
import com.community.api.identity.MailConfig
import com.community.api.v1.*
import com.community.api.v1.identity.checkedCpf
import com.community.api.v1.identity.findIdentity
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID

internal fun invitationAdminHandlers(): Map<String, V1Handler> = mapOf(
    "createInvitation" to V1Handler { c -> V1Response(c.createPlatformInvitation(c.input), 201) },
    "adminListInvitations" to V1Handler { c -> V1Response(c.page("invitation", filters = c.query.filterKeys { it == "status" },
        transform = { c.invitationView(it) })) },
    "revokeInvitation" to V1Handler { c ->
        val record = c.store.get("invitation", c.pathId("invitationId"), c.condominiumId())
        if (record.data.string("status") != "pending") c.fail(409, "INVITATION_NOT_PENDING", "O convite não pode ser revogado")
        V1Response(c.invitationView(c.store.update(record, record.data.plusFields("status" to "revoked"))))
    },
    "adminResendInvitation" to V1Handler { c ->
        val member = c.store.get("membership", c.pathId("membershipId"), c.condominiumId())
        if (member.data.string("status") != "pending") c.fail(409, "MEMBERSHIP_NOT_PENDING", "O vínculo não está pendente")
        c.store.list("invitation", c.condominiumId(), filters = mapOf("membershipId" to member.id, "status" to "pending"))
            .forEach { c.store.update(it, it.data.plusFields("status" to "revoked")) }
        val user = c.tx.get("account", member.ownerId!!, c.tenantId)!!
        V1Response(c.createPlatformInvitation(obj("nodeId" to member.data["nodeId"], "role" to member.data["role"],
            "name" to user.data["name"], "email" to user.data["email"], "expiresInDays" to 7,
            "deliver" to c.input.arr("deliver")), membershipId = member.id, userId = user.id))
    },
)

internal fun V1Context.invitationView(record: Record, code: String? = null): JsonObject = project("Invitation",
    record.document().plusFields("code" to code, "node" to nodeRef(this, record.data.string("nodeId")!!),
        "status" to if (record.data.string("status") == "pending" && Instant.parse(record.data.string("expiresAt")).isBefore(now)) "expired"
            else record.data.string("status"), "acceptedAt" to record.data["acceptedAt"])).let { if (code == null) JsonObject(it - "code") else it }

internal fun V1Context.createPlatformInvitation(data: JsonObject, membershipId: String? = null,
    userId: String? = null, assignmentId: String? = null): JsonObject {
    val condoId = condominiumId()
