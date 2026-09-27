package com.community.api.v1.identity

import com.community.api.core.*
import com.community.api.identity.*
import com.community.api.v1.*
import kotlinx.serialization.json.*
import java.time.Instant

internal fun invitationHandlers(): Map<String, V1Handler> = mapOf(
    "previewInvitation" to V1Handler { it.previewIdentityInvitation() },
    "acceptInvitation" to V1Handler { it.acceptIdentityInvitation() },
    "linkInvitation" to V1Handler { it.linkIdentityInvitation() },
)

private fun V1Context.identityInvitation(): Record {
    val code = identityPath("code")
    val id = code.take(36)
    val invitation = store.find("invitation", id)
    if (invitation == null || !sameSecret(invitation.data.string("codeHash").orEmpty(), hash(code))) {
        fail(404, "INVITATION_NOT_FOUND", "Convite não encontrado")
    }
    if (invitation.data.string("status") != "pending") fail(404, "INVITATION_USED", "Convite indisponível")
    if (!Instant.parse(invitation.data.string("expiresAt")).isAfter(now)) {
        fail(404, "INVITATION_EXPIRED", "Convite expirado")
    }
    return invitation
}

private fun V1Context.previewIdentityInvitation(): V1Response {
    val invitation = identityInvitation()
    val condo = store.get("condominium", invitation.locationId!!)
    val node = store.get("node", invitation.data.string("nodeId")!!, invitation.locationId)
    val name = invitation.data.string("name")
    return V1Response(obj("purpose" to (invitation.data.string("purpose") ?: "first_access"),
        "condominiumName" to condo.data["name"], "unitLabel" to node.data["label"],
        "nodePath" to (node.data["nodePath"] ?: JsonArray(emptyList())), "blockLabel" to null,
        "role" to invitation.data["role"], "expiresAt" to invitation.data["expiresAt"],
        "requiresCpf" to (invitation.data.string("cpf") != null || invitation.data.string("cpfHash") != null),
        "maskedName" to name?.let { it.take(1) + "***" }))
}
