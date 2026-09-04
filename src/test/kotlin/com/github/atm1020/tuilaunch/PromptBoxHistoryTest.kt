package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.prompt.PromptBoxHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBoxHistoryTest {

    private val history = PromptBoxHistory(listOf("oldest", "middle", "newest"))

    @Test
    fun `the first up shows the newest prompt and starts browsing`() {
        assertEquals("newest", history.previous("a draft"))
        assertTrue(history.isBrowsing)
    }

    @Test
    fun `up walks back through the older prompts`() {
        history.previous("a draft")

        assertEquals("middle", history.previous("newest"))
        assertEquals("oldest", history.previous("middle"))
    }

    @Test
    fun `up at the oldest prompt stays there`() {
        history.previous("a draft")
        history.previous("newest")
        history.previous("middle")

        assertNull(history.previous("oldest"))
        assertTrue(history.isBrowsing)
    }

    @Test
    fun `down walks back towards the newest prompt`() {
        history.previous("a draft")
        history.previous("newest")

        assertEquals("newest", history.next("middle"))
        assertTrue(history.isBrowsing)
    }

    @Test
    fun `down past the newest prompt brings the draft back and stops browsing`() {
        history.previous("a draft")

        assertEquals("a draft", history.next("newest"))
        assertFalse(history.isBrowsing)
    }

    @Test
    fun `down does nothing while the draft is showing`() {
        assertNull(history.next("a draft"))
        assertFalse(history.isBrowsing)
    }

    @Test
    fun `an edited prompt becomes the draft and the prompt itself stays as recorded`() {
        history.previous("a draft")
        history.previous("newest, edited")

        assertEquals("newest", history.next("middle"))
        assertEquals("newest, edited", history.next("newest"))
        assertFalse(history.isBrowsing)
    }

    @Test
    fun `up does nothing when no prompt has been recorded yet`() {
        val nothingRecorded = PromptBoxHistory(emptyList())

        assertNull(nothingRecorded.previous("a draft"))
        assertFalse(nothingRecorded.isBrowsing)
    }
}
