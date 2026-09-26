package com.community.api.platform

import com.community.api.core.*
import com.community.api.identity.createAccount
import com.community.api.identity.revokeAccountCredentials
import java.util.UUID

fun bootstrap(env: Map<String, String> = System.getenv()) {
    val name = env["BOOTSTRAP_CLIENT_NAME"]?.validText("BOOTSTRAP_CLIENT_NAME")
        ?: error("Set BOOTSTRAP_CLIENT_NAME")
    val email = env["BOOTSTRAP_EMAIL"] ?: error("Set BOOTSTRAP_EMAIL")
    val password = env["BOOTSTRAP_PASSWORD"] ?: error("Set BOOTSTRAP_PASSWORD")
    val id = env["BOOTSTRAP_CLIENT_ID"] ?: UUID.randomUUID().toString()
    require(runCatching { UUID.fromString(id) }.isSuccess) { "BOOTSTRAP_CLIENT_ID must be a UUID" }
    Database.fromEnvironment(env).use { db ->
        val userId = db.tx { tx ->
            if (tx.get("client", id) != null) conflict("Client already exists; bootstrap never overwrites accounts")
            tx.create("client", id, data = body(ClientSettings(name)), id = id)
            val account = tx.createAccount(id, email, password, "Client administrator")
            tx.create("membership", id, ownerId = account.id, data = body(Membership(account.id, role = "client_admin")))
