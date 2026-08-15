package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.services.uniqueSessionTitle
import org.junit.Assert.assertEquals
import org.junit.Test

class UniqueSessionTitleTest {

    @Test
    fun firstInstanceGetsTheBareName() {
        assertEquals("claude", uniqueSessionTitle("claude", emptySet()))
    }

    @Test
    fun secondInstanceGetsSuffixOne() {
        assertEquals("claude 1", uniqueSessionTitle("claude", setOf("claude")))
    }

    @Test
    fun thirdInstanceGetsSuffixTwo() {
        assertEquals("claude 2", uniqueSessionTitle("claude", setOf("claude", "claude 1")))
    }

    @Test
    fun freedGapIsReused() {
        assertEquals("claude 1", uniqueSessionTitle("claude", setOf("claude", "claude 2")))
    }

    @Test
    fun collidesWithARenamedTitle() {
        assertEquals("claude 2", uniqueSessionTitle("claude", setOf("claude", "claude 1")))
    }

    @Test
    fun baseNameEndingInADigitIsNotConfusedWithASuffix() {
        assertEquals("claude9", uniqueSessionTitle("claude9", emptySet()))
        assertEquals("claude9 1", uniqueSessionTitle("claude9", setOf("claude9")))
        assertEquals("claude9 2", uniqueSessionTitle("claude9", setOf("claude9", "claude9 1")))
    }
}
