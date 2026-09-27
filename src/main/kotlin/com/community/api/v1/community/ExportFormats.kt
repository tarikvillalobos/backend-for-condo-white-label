package com.community.api.v1.community

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun csv(rows: List<Map<String, String>>): ByteArray {
    val columns = rows.firstOrNull()?.keys?.toList() ?: listOf("id")
    fun cell(value: String): String {
        val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
    return buildString {
        append('\uFEFF')
        append(columns.joinToString(",", transform = ::cell)).append("\r\n")
        rows.forEach { row -> append(columns.joinToString(",") { cell(row[it].orEmpty()) }).append("\r\n") }
    }.toByteArray(StandardCharsets.UTF_8)
}
internal fun xlsx(rows: List<Map<String, String>>): ByteArray {
