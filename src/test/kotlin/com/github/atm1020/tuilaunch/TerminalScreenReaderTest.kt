package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.terminal.TerminalRow
import com.github.atm1020.tuilaunch.terminal.readTerminalScreen
import com.jediterm.terminal.model.CharBuffer
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalTextBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalScreenReaderTest {

    private val buffer = TerminalTextBuffer(10, 3, StyleState())

    private fun write(row: Int, text: String, column: Int = 0) {
        buffer.writeString(column, row + 1, CharBuffer(text))
    }

    private fun texts(maxScrollbackRows: Int = 500): List<String> =
        readTerminalScreen(buffer, maxScrollbackRows).rows.map { it.text }

    @Test
    fun `the rows of the screen are read with their trailing blanks trimmed`() {
        write(0, "first")
        write(1, "second    ")

        val screen = readTerminalScreen(buffer)

        assertEquals(listOf("first", "second"), screen.rows.map { it.text })
        assertEquals(10, screen.columns)
        assertFalse(screen.usesAlternateScreen)
    }

    @Test
    fun `cells the program skipped over read as spaces instead of ending the row`() {
        write(0, "abc", column = 3)
        write(0, "z", column = 8)

        assertEquals("   abc  z", texts().first())
    }

    @Test
    fun `the second cell of a double width character is dropped`() {
        write(0, "a\uE000b")

        assertEquals("ab", texts().first())
    }

    @Test
    fun `a row the terminal wrapped keeps its trailing space and says so`() {
        write(0, "one two   ")
        buffer.setLineWrapped(0, true)
        write(1, "three")

        val rows = readTerminalScreen(buffer).rows

        assertEquals(TerminalRow("one two   ", isWrapped = true), rows[0])
        assertEquals(TerminalRow("three", isWrapped = false), rows[1])
    }

    @Test
    fun `the scrollback comes before the screen on the normal screen`() {
        write(0, "old one")
        write(1, "old two")
        buffer.moveScreenLinesToHistory()
        write(0, "new")

        assertEquals(listOf("old one", "old two", "new"), texts().filter { it.isNotEmpty() })
    }

    @Test
    fun `only the newest scrollback rows within the limit are read`() {
        write(0, "old one")
        write(1, "old two")
        write(2, "old three")
        buffer.moveScreenLinesToHistory()
        write(0, "new")

        val rows = texts(maxScrollbackRows = 1)

        assertEquals(listOf("old three", "new"), rows.filter { it.isNotEmpty() })
    }

    @Test
    fun `the alternate screen is read without the scrollback of the normal screen`() {
        write(0, "old one")
        buffer.moveScreenLinesToHistory()
        buffer.useAlternateBuffer(true)
        write(0, "full")

        val screen = readTerminalScreen(buffer)

        assertTrue(screen.usesAlternateScreen)
        assertEquals(listOf("full"), screen.rows.map { it.text }.filter { it.isNotEmpty() })
    }
}
