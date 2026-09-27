package com.community.api.v1.identity

import com.community.api.core.ApiException
import java.util.Locale

internal fun identityFailure(status: Int, code: String, detail: String): Nothing =
    throw ApiException(status, code, detail)

internal fun checkedCpf(value: String): String {
    if (!value.matches(Regex("[0-9]{11}")) || value.toSet().size == 1) {
        identityFailure(422, "INVALID_CPF", "Informe um CPF válido com onze dígitos")
    }
    for (length in 9..10) {
        val sum = (0 until length).sumOf { value[it].digitToInt() * (length + 1 - it) }
        val digit = (11 - sum % 11).let { if (it >= 10) 0 else it }
        if (digit != value[length].digitToInt()) {
            identityFailure(422, "INVALID_CPF", "Os dígitos verificadores do CPF são inválidos")
        }
    }
    return value
}

internal fun checkedEmail(value: String): String = value.trim().lowercase(Locale.ROOT).also {
    if (it.length !in 3..254 || !it.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) {
        identityFailure(422, "INVALID_CONTACT", "Informe um e-mail válido")
    }
}

internal fun checkedPhone(value: String): String = value.also {
    if (!it.matches(Regex("\\+55[1-9][0-9]9[0-9]{8}"))) {
        identityFailure(422, "INVALID_CONTACT", "Informe um celular brasileiro no formato E.164")
    }
}

internal fun checkedContact(value: String, channel: String): String = when (channel) {
    "email" -> checkedEmail(value)
    "sms" -> checkedPhone(value)
    else -> identityFailure(422, "INVALID_CHANNEL", "Canal de autenticação inválido")
}

