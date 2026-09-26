package com.community.api.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

val json = Json { encodeDefaults = true }

inline fun <reified T> body(value: T): JsonObject = json.encodeToJsonElement(value).jsonObject
inline fun <reified T> Record.decode(): T = json.decodeFromJsonElement(data)

@Serializable
data class Record(
    val id: String,
    val kind: String,
    val tenantId: String,
    val locationId: String?,
    val ownerId: String?,
    val data: JsonObject,
    val createdAt: String,
    val updatedAt: String,
    val version: Int,
)

data class Actor(val userId: String, val tenantId: String, val sessionId: String)

data class Context(val actor: Actor, val locationId: String?, val permissions: Set<String>) {
    val tenantId: String get() = actor.tenantId
    val userId: String get() = actor.userId
    fun can(permission: String): Boolean = "*" in permissions || permission in permissions
}

@Serializable
data class Page<T>(val items: List<T>, val total: Int, val offset: Int, val limit: Int)

class ApiException(val status: Int, val code: String, override val message: String) : RuntimeException(message)

