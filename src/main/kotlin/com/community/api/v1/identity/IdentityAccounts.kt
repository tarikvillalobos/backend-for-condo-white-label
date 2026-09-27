package com.community.api.v1.identity

import com.community.api.core.*
import com.community.api.identity.*
import com.community.api.v1.*
import kotlinx.serialization.json.*

fun indexIdentityAccount(tx: Tx, account: Record, cpf: String? = null, phone: String? = null) {
    val email = account.decode<Account>().email.takeIf { it.isNotBlank() }
    for ((type, value) in listOf("email" to email, "cpf" to cpf, "phone" to phone)) {
        if (value == null) continue
        val id = identityIndexId(account.tenantId, type, value)
        val existing = tx.get("v1_identifier", id, account.tenantId)
        if (existing != null && existing.ownerId != account.id) {
            identityFailure(409, "IDENTIFIER_ALREADY_USED", "O identificador já pertence a outra conta")
        }
        if (existing == null) tx.create("v1_identifier", account.tenantId, ownerId = account.id,
            data = obj("type" to type), id = id)
    }
}
