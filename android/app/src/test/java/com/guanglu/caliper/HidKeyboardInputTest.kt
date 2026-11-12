package com.guanglu.caliper

import com.guanglu.caliper.core.usecase.HidKeyboardInput
import com.guanglu.caliper.core.usecase.Terminator
import org.junit.Assert.assertEquals
import org.junit.Test

class HidKeyboardInputTest {

    @Test fun cr_terminator_splits_lines() {
        assertEquals(
            listOf("155.15", "155.08", "154.96"),
            HidKeyboardInput.split("155.15\r\n155.08\n154.96", Terminator.CR)
        )
    }

    @Test fun tab_terminator_splits_columns() {
        assertEquals(
            listOf("155.15", "155.08", "154.96"),
            HidKeyboardInput.split("155.15\t155.08\t154.96\t", Terminator.TAB)
        )
    }

    @Test fun blank_tokens_are_ignored() {
        assertEquals(
            emptyList<String>(),
            HidKeyboardInput.split("\r\n \n\t", Terminator.CR)
        )
    }
}
