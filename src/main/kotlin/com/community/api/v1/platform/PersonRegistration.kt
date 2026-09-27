package com.community.api.v1.platform

import com.community.api.core.*
import com.community.api.identity.*
import com.community.api.v1.*
import com.community.api.v1.identity.*
import kotlinx.serialization.json.*

internal data class RegisteredPerson(val account: Record, val created: Boolean)

internal fun V1Context.registerPerson(person: JsonObject): RegisteredPerson {
    val name = normalizedName(person.string("name") ?: fail(422, "NAME_REQUIRED", "Informe o nome"))
    val email = person.string("email")?.let(::checkedEmail)
    val cpf = person.string("cpf")?.let(::checkedCpf)
    val phone = person.string("phone")?.let(::checkedPhone)
    val identifiers = listOf("email" to email, "cpf" to cpf, "phone" to phone).filter { it.second != null }
    if (identifiers.isEmpty()) fail(422, "IDENTIFIER_REQUIRED", "Informe CPF, e-mail ou telefone")
    val matches = identifiers.mapNotNull { (type, value) -> findIdentity(type, value!!) }.distinctBy { it.id }
    if (matches.size > 1) fail(409, "IDENTITY_CONFLICT", "Os identificadores pertencem a contas diferentes")
    val account = matches.singleOrNull() ?: tx.create("account", tenantId, data = body(Account(email.orEmpty(), name, "", false)))
    if (matches.isNotEmpty()) {
        val currentEmail = account.decode<Account>().email
        if (email != null && currentEmail.isNotEmpty() && email != currentEmail) {
            fail(409, "IDENTITY_CONFLICT", "O e-mail não corresponde à conta existente")
        }
    }
    indexIdentityAccount(tx, account, cpf, phone)
    val profile = profileData(account)
    saveProfile(account, profile.with("phone" to (phone ?: profile.string("phone")),
        "cpfHash" to (cpf?.let(::hash) ?: profile.string("cpfHash"))))
    return RegisteredPerson(account, matches.isEmpty())
}

internal fun V1Context.membershipAdminView(record: Record): JsonObject {
    val account = tx.get("account", record.ownerId!!, tenantId)!!
    val user = account.decode<Account>()
    val phone = profileData(account).string("phone")
    val node = record.data.string("nodeId")!!
    return project("MembershipAdmin", record.document().plusFields("user" to obj("id" to account.id, "name" to user.name,
        "maskedEmail" to user.email.takeIf { it.isNotBlank() }?.let { maskedContact(it, "email") },
        "maskedPhone" to phone?.let { maskedContact(it, "sms") }), "node" to nodeRef(this, node),
        "nodePath" to nodePathView(this, node), "canManageNode" to record.data.bool("canManageNode"),
        "permissions" to record.data.arr("permissions"), "startedAt" to (record.data.string("startedAt") ?: record.createdAt),
        "endedAt" to record.data["endedAt"], "lastSeenAt" to store.list("session", ownerId = account.id)
            .mapNotNull { it.data.string("lastSeenAt") }.maxOrNull()))
}

internal fun V1Context.checkAddressableNode(id: String): Record {
    val node = store.get("node", id, condominiumId())
    val type = store.get("node_type", node.data.string("typeId")!!, condominiumId())
    if (!node.data.bool("active", true) || !type.data.bool("addressable")) {
        fail(422, "NODE_NOT_ADDRESSABLE", "O nó precisa estar ativo e permitir vínculos")
    }
    return node
}
