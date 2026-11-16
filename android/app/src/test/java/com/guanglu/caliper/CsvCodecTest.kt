package com.guanglu.caliper

import com.guanglu.caliper.core.usecase.CsvCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvCodecTest {

    @Test fun utf8_bom_is_stripped_before_header_match() {
        val result = CsvCodec.parse(
            "\uFEFFDATE,TIME,VALUE_MM,CHANNEL\r\n2026-11-12,09:00:00,155.15,CH7\r\n"
        )
        assertTrue(result.bomRemoved)
        assertNull(result.fatal)
        assertEquals(1, result.rows.size)
        assertEquals("CH7", result.rows[0]["CHANNEL"])
    }

    @Test fun chinese_aliases_map_to_stable_keys() {
        val result = CsvCodec.parse(
            "日期,时间,实测值,通道\r\n2026-11-12,09:00:00,155.15,CH3\r\n"
        )
        assertNull(result.fatal)
        assertEquals("155.15", result.rows[0]["VALUE_MM"])
        assertEquals("CH3", result.rows[0]["CHANNEL"])
    }

    @Test fun missing_date_column_is_fatal() {
        val result = CsvCodec.parse("TIME,VALUE_MM\r\n09:00:00,155.15\r\n")
        assertTrue(result.fatal?.contains("DATE") == true)
        assertTrue(result.rows.isEmpty())
    }

    @Test fun unrecognised_columns_are_skipped_and_reported() {
        val result = CsvCodec.parse(
            "DATE,TIME,VALUE_MM,自定义列\r\n2026-11-12,09:00:00,155.15,x\r\n"
        )
        assertEquals(listOf("自定义列"), result.skippedColumns)
        assertEquals(1, result.rows.size)
    }

    @Test fun bad_value_row_is_counted_not_fatal() {
        val result = CsvCodec.parse(
            "DATE,VALUE_MM\r\n2026-11-12,155.15\r\n2026-11-12,不是数字\r\n"
        )
        assertEquals(1, result.parseErrors)
        assertEquals(1, result.rows.size)
    }

    @Test fun quoted_comma_is_one_cell() {
        val result = CsvCodec.parse(
            "DATE,VALUE_MM,NOTE\r\n2026-11-12,155.15,\"含,逗号\"\r\n"
        )
        assertEquals("含,逗号", result.rows[0]["NOTE"])
    }

    @Test fun historical_channels_keep_their_value_and_are_flagged_legacy() {
        assertEquals(7 to false, CsvCodec.channelOf("CH7"))
        assertEquals(3 to false, CsvCodec.channelOf("3"))
        assertEquals(10 to true, CsvCodec.channelOf("CH10"))
        assertEquals(0 to true, CsvCodec.channelOf(""))
    }

    @Test fun export_uses_only_chinese_headers_and_round_trips() {
        val row = mapOf(
            "DATE" to "2026-11-16", "TIME" to "09:00:00", "DEVICE_MAC" to "GL-BLE#0187",
            "VALUE_MM" to "155.1500", "UPPER_TOL" to "156.0000", "LOWER_TOL" to "154.0000",
            "CHANNEL" to "通道3"
        )
        val text = CsvCodec.export(listOf(row))
        val first = text.lines().first().trim()
        assertEquals("日期,时间,设备名称,测量值,公差上限,公差下限,通道名称", first)
        assertFalse("表头仍有英文标记", first.contains("DATE") || first.contains("BATCH"))
        assertFalse("表头仍含批次号", first.contains("批次"))
        val roundTrip = CsvCodec.parse(text)
        assertNull(roundTrip.fatal)
        assertEquals(7, roundTrip.headers.size)
        assertEquals(1, roundTrip.rows.size)
        assertEquals("155.1500", roundTrip.rows[0]["VALUE_MM"])
        assertEquals("156.0000", roundTrip.rows[0]["UPPER_TOL"])
        assertEquals("154.0000", roundTrip.rows[0]["LOWER_TOL"])
        assertEquals("GL-BLE#0187", roundTrip.rows[0]["DEVICE_MAC"])
        assertEquals(3 to false, CsvCodec.channelOf(roundTrip.rows[0]["CHANNEL"].orEmpty()))
        assertTrue("跳过的列不应为空", roundTrip.skippedColumns.isEmpty())
    }

    @Test fun chinese_channel_name_and_legacy_channel_are_both_parsed() {
        assertEquals(1 to false, CsvCodec.channelOf("通道1"))
        assertEquals(7 to false, CsvCodec.channelOf("通道 7"))
        assertEquals(7 to false, CsvCodec.channelOf("CH7"))
        assertEquals(10 to true, CsvCodec.channelOf("CH10"))
    }

    @Test fun chinese_headers_map_to_stable_internal_keys() {
        assertEquals("UPPER_TOL", CsvCodec.canonical("公差上限"))
        assertEquals("LOWER_TOL", CsvCodec.canonical("公差下限"))
        assertEquals("DEVICE_MAC", CsvCodec.canonical("设备名称"))
        assertEquals("VALUE_MM", CsvCodec.canonical("测量出的值"))
        assertEquals("CHANNEL", CsvCodec.canonical("通道名称"))
        assertEquals("BATCH", CsvCodec.canonical("批次"))
    }
}
