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
