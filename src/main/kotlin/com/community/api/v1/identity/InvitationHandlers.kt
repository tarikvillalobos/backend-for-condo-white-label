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
