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
    val nodeId = data.string("nodeId") ?: rootNode().id
    store.get("node", nodeId, condoId)
    if (assignmentId == null) checkAddressableNode(nodeId)
    val cpf = data.string("cpf")?.let(::checkedCpf)
    val id = UUID.randomUUID().toString()
    val code = "${id}_${Secrets.token()}"
    val recipient = userId?.let { tx.get("account", it, tenantId) }
        ?: data.string("email")?.let { findIdentity("email", it) } ?: cpf?.let { findIdentity("cpf", it) }
    val existingActive = recipient?.data?.bool("active") == true
    val expiresDays = data["expiresInDays"]?.jsonPrimitive?.intOrNull ?: 7
    val record = store.create("invitation", JsonObject(data - "deliver").plusFields("brandId" to brandId,
        "nodeId" to nodeId, "codeHash" to hash(code), "cpfHash" to cpf?.let(::hash),
        "userId" to recipient?.id, "membershipId" to membershipId, "assignmentId" to assignmentId,
        "purpose" to if (existingActive) "link_membership" else "first_access", "status" to "pending",
        "expiresAt" to now.plusSeconds(expiresDays.toLong() * 86400).toString(), "acceptedAt" to null), condoId, userId, id)
    val channels = data.arr("deliver").map { it.jsonPrimitive.content }
    if (channels.any { it != "email" }) fail(501, "CHANNEL_UNAVAILABLE", "O provedor do canal solicitado não está configurado")
    if ("email" in channels) {
        if (MailConfig.fromEnvironment() == null) fail(503, "CHANNEL_UNAVAILABLE", "E-mail não está configurado")
        val email = data.string("email") ?: recipient?.data?.string("email") ?: fail(422, "EMAIL_REQUIRED", "Informe o e-mail para envio")
        enqueueMail(email, "Community: convite de acesso", "Seu código de convite é:\n$code\nExpira em ${record.data.string("expiresAt")}")
    }
    return invitationView(record, code)
}
