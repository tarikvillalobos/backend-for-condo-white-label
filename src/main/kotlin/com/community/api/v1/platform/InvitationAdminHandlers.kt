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
