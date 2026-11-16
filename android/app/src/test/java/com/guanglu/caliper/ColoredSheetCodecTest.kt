package com.guanglu.caliper

import com.guanglu.caliper.core.usecase.ColoredSheetCodec
import com.guanglu.caliper.core.usecase.SheetCell
import com.guanglu.caliper.core.usecase.SheetRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * 彩色表格 OOXML 契约：中文表头、超差红色样式与 zip 部件完整性。
 *
 * @author AI-Generated
 */
class ColoredSheetCodecTest {

    private val headers = listOf("日期", "时间", "设备名称", "测量值", "公差上限", "公差下限", "通道名称")

    private fun row(value: String, highlighted: Boolean): SheetRow = SheetRow(
        listOf(
            SheetCell("2026-11-16"),
            SheetCell("09:00:00"),
            SheetCell("GL-BLE#0187"),
            SheetCell(value, numeric = true, highlighted = highlighted),
            SheetCell("156.0000", numeric = true),
            SheetCell("154.0000", numeric = true),
            SheetCell("通道3")
        )
    )

    private fun parts(bytes: ByteArray): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                result[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return result
    }

    @Test fun workbook_contains_all_six_parts_and_chinese_headers() {
        val entries = parts(ColoredSheetCodec.build(headers, listOf(row("157.0000", true))))
        assertEquals(6, entries.size)
        assertTrue(entries.containsKey("[Content_Types].xml"))
        assertTrue(entries.containsKey("xl/styles.xml"))
        val sheet = entries.getValue("xl/worksheets/sheet1.xml")
        headers.forEach { header -> assertTrue("缺少中文表头 $header", sheet.contains(header)) }
        assertTrue(sheet.contains("通道3"))
    }

    @Test fun over_limit_value_cell_uses_red_style_and_normal_cell_does_not() {
        val sheet = ColoredSheetCodec.sheetXml(headers, listOf(row("157.0000", true), row("155.0000", false)))
        assertTrue("超差测量值未标红", sheet.contains("<c r=\"D2\" s=\"1\""))
        assertTrue("正常测量值不应标红", sheet.contains("<c r=\"D3\" t=\"n\">"))
        assertFalse("正常行出现红色样式", sheet.contains("<c r=\"D3\" s=\"1\""))
        assertFalse("公差列被误标红", sheet.contains("<c r=\"E2\" s=\"1\""))
    }

    @Test fun styles_define_a_red_font() {
        val styles = parts(ColoredSheetCodec.build(headers, emptyList())).getValue("xl/styles.xml")
        assertTrue(styles.contains("FFFF0000"))
        assertTrue(styles.contains("applyFont=\"1\""))
        assertTrue(styles.contains("fontId=\"1\""))
    }

    @Test fun numeric_cells_use_value_elements_and_text_cells_use_inline_strings() {
        val sheet = ColoredSheetCodec.sheetXml(headers, listOf(row("155.0000", false)))
        assertTrue(sheet.contains("<v>155.0000</v>"))
        assertTrue(sheet.contains("<t>GL-BLE#0187</t>"))
    }

    @Test fun column_labels_cover_the_seven_export_columns() {
        assertEquals(listOf("A", "B", "C", "D", "E", "F", "G"), (0..6).map { ColoredSheetCodec.columnLabel(it) })
        assertEquals("AA", ColoredSheetCodec.columnLabel(26))
    }

    @Test fun xml_special_characters_are_escaped() {
        val escaped = ColoredSheetCodec.xmlEscape("GL & <测试> \"A\" 'B'")
        assertEquals("GL &amp; &lt;测试&gt; &quot;A&quot; &apos;B&apos;", escaped)
        val sheet = ColoredSheetCodec.sheetXml(headers, listOf(SheetRow(listOf(SheetCell("GL & <测试>")))))
        assertFalse("设备名称含未转义的尖括号", sheet.contains("<t>GL & <测试></t>"))
        assertTrue(sheet.contains("&lt;测试&gt;"))
    }
}
