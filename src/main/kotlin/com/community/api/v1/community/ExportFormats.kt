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
    val output = ByteArrayOutputStream()
    val columns = rows.firstOrNull()?.keys?.toList() ?: listOf("id")
    ZipOutputStream(output).use { zip ->
        fun entry(name: String, text: String) {
            zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(StandardCharsets.UTF_8)); zip.closeEntry()
        }
        entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
        entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
        entry("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Dados" sheetId="1" r:id="rId1"/></sheets></workbook>""")
        entry("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
        zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
        fun write(value: String) { zip.write(value.toByteArray(StandardCharsets.UTF_8)) }
        write("""<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        fun row(values: List<String>, index: Int) {
            write("<row r=\"$index\">")
            values.forEach { value -> write("<c t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value)}</t></is></c>") }
            write("</row>")
        }
        row(columns, 1)
        rows.forEachIndexed { index, data -> row(columns.map { data[it].orEmpty() }, index + 2) }
