package com.community.api.v1

import com.community.api.core.ApiException
import kotlinx.serialization.json.*
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class ContractOperation(val method: String, val path: String, val definition: JsonObject) {
    val id: String = definition.string("operationId")!!
}

object Contract {
    val yaml: String by lazy {
        checkNotNull(javaClass.getResourceAsStream("/contract/openapi.yaml")) { "Missing bundled OpenAPI" }
            .bufferedReader().use { it.readText() }
    }
    val document: JsonObject by lazy {
        val options = LoaderOptions().apply { isAllowDuplicateKeys = false; codePointLimit = 4_000_000 }
        element(Yaml(SafeConstructor(options)).load<Any>(yaml)).jsonObject
    }
    val schemas: JsonObject get() = document["components"]!!.jsonObject["schemas"]!!.jsonObject
    val operations: List<ContractOperation> by lazy {
        document["paths"]!!.jsonObject.flatMap { (path, item) ->
            item.jsonObject.mapNotNull { (method, operation) ->
                if (method in setOf("get", "post", "put", "patch", "delete")) {
                    val inherited = item.jsonObject["parameters"]?.jsonArray.orEmpty()
                    val local = operation.jsonObject["parameters"]?.jsonArray.orEmpty()
                    ContractOperation(method, path, JsonObject(operation.jsonObject + ("parameters" to JsonArray(inherited + local))))
                } else null
            }
        }
    }
    fun resolve(schema: JsonObject): JsonObject {
        val reference = schema.string("\$ref") ?: return schema
        require(reference.startsWith("#/")) { "Only local contract references are supported" }
        val target = reference.removePrefix("#/").split('/').fold(document as JsonElement) { current, key ->
            current.jsonObject[key.replace("~1", "/").replace("~0", "~")] ?: error("Unresolved contract reference")
        }.jsonObject
        return JsonObject(resolve(target) + (schema - "\$ref"))
    }
    fun validate(schema: JsonObject, value: JsonElement, field: String = "body") {
        val errors = errors(schema, value, field)
        if (errors.isNotEmpty()) throw ApiException(422, "VALIDATION_ERROR", errors.take(5).joinToString("; "))
    }
    fun errors(source: JsonObject, value: JsonElement, field: String = "body"): List<String> {
        val schema = resolve(source)
        val errors = mutableListOf<String>()
        schema["allOf"]?.jsonArray?.forEach { errors += errors(it.jsonObject, value, field) }
        schema["anyOf"]?.jsonArray?.let { choices ->
            if (choices.none { errors(it.jsonObject, value, field).isEmpty() }) errors += "$field has an invalid value"
        }
        schema["oneOf"]?.jsonArray?.let { choices ->
            if (choices.count { errors(it.jsonObject, value, field).isEmpty() } != 1) errors += "$field must match exactly one permitted form"
        }
        schema["not"]?.jsonObject?.let { if (errors(it, value, field).isEmpty()) errors += "$field has a forbidden combination" }
        schema["enum"]?.jsonArray?.let { if (value !in it) errors += "$field is not an allowed value" }
