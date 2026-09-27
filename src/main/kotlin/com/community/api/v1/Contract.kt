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
        schema["const"]?.let { if (value != it) errors += "$field has an invalid constant" }
        val types = when (val type = schema["type"]) {
            is JsonPrimitive -> listOf(type.content)
            is JsonArray -> type.map { it.jsonPrimitive.content }
            else -> emptyList()
        }
        if (types.isNotEmpty() && types.none { matchesType(it, value) }) return errors + "$field has an invalid type"
        if (value is JsonObject) {
            val properties = schema["properties"]?.jsonObject.orEmpty()
            schema["required"]?.jsonArray?.forEach { if (it.jsonPrimitive.content !in value) errors += "$field.${it.jsonPrimitive.content} is required" }
            value.forEach { (key, child) ->
                if (key in properties) errors += errors(properties.getValue(key).jsonObject, child, "$field.$key")
                else if (schema["additionalProperties"] == JsonPrimitive(false)) errors += "$field.$key is not supported"
                else (schema["additionalProperties"] as? JsonObject)?.let { errors += errors(it, child, "$field.$key") }
            }
            if (value.size < schema.int("minProperties", 0)) errors += "$field requires more properties"
        }
        if (value is JsonArray) {
            if (value.size < schema.int("minItems", 0) || value.size > schema.int("maxItems", Int.MAX_VALUE)) errors += "$field has an invalid number of items"
            if (schema["uniqueItems"] == JsonPrimitive(true) && value.distinct().size != value.size) errors += "$field must contain unique items"
            (schema["items"] as? JsonObject)?.let { child -> value.forEachIndexed { index, item -> errors += errors(child, item, "$field[$index]") } }
        }
        if (value is JsonPrimitive && value.isString) {
            val text = value.content
            if (text.length < schema.int("minLength", 0) || text.length > schema.int("maxLength", Int.MAX_VALUE)) errors += "$field has an invalid length"
            schema.string("pattern")?.let { if (!Regex(it).containsMatchIn(text)) errors += "$field has an invalid format" }
            val valid = when (schema.string("format")) {
                "date-time" -> runCatching { Instant.parse(text) }.isSuccess
                "date" -> runCatching { LocalDate.parse(text) }.isSuccess
                "uuid" -> runCatching { UUID.fromString(text) }.isSuccess && text.length == 36
                "email" -> Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(text)
                else -> true
            }
            if (!valid) errors += "$field has an invalid format"
        }
        if (value is JsonPrimitive && !value.isString) value.doubleOrNull?.let { number ->
            schema["minimum"]?.jsonPrimitive?.doubleOrNull?.let { if (number < it) errors += "$field is too small" }
            schema["maximum"]?.jsonPrimitive?.doubleOrNull?.let { if (number > it) errors += "$field is too large" }
        }
        return errors
