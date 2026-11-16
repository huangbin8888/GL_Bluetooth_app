package com.guanglu.caliper.core.usecase

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 彩色表格的一个单元格；numeric 写数值单元格，highlighted 使用红色字体。 */
data class SheetCell(val text: String, val numeric: Boolean = false, val highlighted: Boolean = false)

/** 彩色表格的一行。 */
data class SheetRow(val cells: List<SheetCell>)

/**
 * 生成最小可用的 xlsx 工作簿，超差测量值使用红色字体。
 *
 * @author AI-Generated
 */
object ColoredSheetCodec {

    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    private const val RED_STYLE_INDEX = 1
    private const val XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    private const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"

    private val CONTENT_TYPES = """
        $XML_HEAD
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
        <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
        <Default Extension="xml" ContentType="application/xml"/>
        <Override PartName="/xl/workbook.xml"
          ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
        <Override PartName="/xl/worksheets/sheet1.xml"
          ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
        <Override PartName="/xl/styles.xml"
          ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
        </Types>
    """.trimIndent()

    private val ROOT_RELS = """
        $XML_HEAD
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1"
          Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
          Target="xl/workbook.xml"/>
        </Relationships>
    """.trimIndent()

    private val WORKBOOK = """
        $XML_HEAD
        <workbook xmlns="$MAIN_NS"
          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
        <sheets><sheet name="测量数据" sheetId="1" r:id="rId1"/></sheets>
        </workbook>
    """.trimIndent()

    private val WORKBOOK_RELS = """
        $XML_HEAD
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1"
          Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"
          Target="worksheets/sheet1.xml"/>
        <Relationship Id="rId2"
          Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles"
          Target="styles.xml"/>
        </Relationships>
    """.trimIndent()

    private val STYLES = """
        $XML_HEAD
        <styleSheet xmlns="$MAIN_NS">
        <fonts count="2">
        <font><sz val="11"/><name val="Calibri"/></font>
        <font><color rgb="FFFF0000"/><sz val="11"/><name val="Calibri"/></font>
        </fonts>
        <fills count="2"><fill><patternFill patternType="none"/></fill>
        <fill><patternFill patternType="gray125"/></fill></fills>
        <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
        <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
        <cellXfs count="2">
        <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
        <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
        </cellXfs>
        </styleSheet>
    """.trimIndent()

    /** 打包 6 个 OOXML 部件，返回完整 xlsx 字节。 */
    fun build(headers: List<String>, rows: List<SheetRow>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            write(zip, "[Content_Types].xml", CONTENT_TYPES)
            write(zip, "_rels/.rels", ROOT_RELS)
            write(zip, "xl/workbook.xml", WORKBOOK)
            write(zip, "xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            write(zip, "xl/styles.xml", STYLES)
            write(zip, "xl/worksheets/sheet1.xml", sheetXml(headers, rows))
        }
        return out.toByteArray()
    }

    /** 生成工作表 XML，供 JVM 单测校验红色样式与中文表头。 */
    internal fun sheetXml(headers: List<String>, rows: List<SheetRow>): String {
        val sb = StringBuilder(XML_HEAD)
        sb.append("<worksheet xmlns=\"").append(MAIN_NS).append("\"><sheetData>")
        sb.append(rowXml(1, headers.map { SheetCell(it) }))
        rows.forEachIndexed { index, row -> sb.append(rowXml(index + 2, row.cells)) }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun rowXml(rowNo: Int, cells: List<SheetCell>): String {
        val sb = StringBuilder("<row r=\"").append(rowNo).append("\">")
        cells.forEachIndexed { index, cell ->
            val ref = columnLabel(index) + rowNo
            val style = if (cell.highlighted) " s=\"$RED_STYLE_INDEX\"" else ""
            sb.append("<c r=\"").append(ref).append("\"").append(style)
            if (cell.numeric && cell.text.toDoubleOrNull() != null) {
                sb.append(" t=\"n\"><v>").append(cell.text).append("</v></c>")
            } else {
                sb.append(" t=\"inlineStr\"><is><t>").append(xmlEscape(cell.text))
                    .append("</t></is></c>")
            }
        }
        sb.append("</row>")
        return sb.toString()
    }

    /** 0 基列号转 A–Z 列标。 */
    internal fun columnLabel(index: Int): String {
        var value = index
        val sb = StringBuilder()
        do {
            sb.insert(0, ('A'.code + value % 26).toChar())
            value = value / 26 - 1
        } while (value >= 0)
        return sb.toString()
    }

    /** XML 文本转义。 */
    internal fun xmlEscape(text: String): String = buildString {
        text.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }

    private fun write(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
