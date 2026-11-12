package com.guanglu.caliper

import com.guanglu.caliper.core.usecase.CsvCodec
import org.junit.Assert.assertEquals
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

    @Test fun export_round_trip_preserves_all_sixteen_columns() {
        val row = CsvCodec.COLUMNS.associateWith { "x-$it" } + mapOf(
            "DATE" to "2026-11-12", "VALUE_MM" to "155.15", "CHANNEL" to "CH7"
        )
        val roundTrip = CsvCodec.parse(CsvCodec.export(listOf(row)))
        assertNull(roundTrip.fatal)
        assertEquals(1, roundTrip.rows.size)
        assertEquals("155.15", roundTrip.rows[0]["VALUE_MM"])
        assertEquals(16, roundTrip.headers.size)
    }
}
