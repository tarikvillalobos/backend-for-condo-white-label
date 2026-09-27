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
