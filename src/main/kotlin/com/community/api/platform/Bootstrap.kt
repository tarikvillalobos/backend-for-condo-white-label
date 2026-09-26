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
            tx.audit(Context(Actor(account.id, id, "bootstrap"), null, setOf("*")), "client.bootstrapped", id)
            account.id
        }
        println("Client created: $id")
        println("Administrator created: $userId")
    }
}

fun changeClientState(env: Map<String, String> = System.getenv()) {
    val id = env["CLIENT_ID"] ?: error("Set CLIENT_ID")
    val active = env["CLIENT_ACTIVE"]?.toBooleanStrictOrNull() ?: error("Set CLIENT_ACTIVE to true or false")
    Database.fromEnvironment(env).use { db ->
        db.tx { tx ->
            val record = tx.requireRecord("client", id, id)
            tx.update(record, body(record.decode<ClientSettings>().copy(active = active)))
            if (!active) tx.list("account", id).forEach { tx.revokeAccountCredentials(id, it.id) }
            tx.audit(Context(Actor("operator", id, "cli"), null, setOf("*")), "client.state_changed", id)
        }
    }
    println("Client $id active=$active")
