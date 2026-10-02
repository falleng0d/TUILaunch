package com.github.atm1020.tuilaunch.terminal

import com.jediterm.terminal.model.TerminalLine
import com.jediterm.terminal.model.TerminalTextBuffer

const val MAX_SCROLLBACK_ROWS = 500

private const val DOUBLE_WIDTH_SECOND_CELL = '\uE000'
private const val EMPTY_CELL = '\u0000'

data class TerminalRow(val text: String, val isWrapped: Boolean = false)

data class TerminalScreen(val rows: List<TerminalRow>, val columns: Int, val usesAlternateScreen: Boolean)

fun readTerminalScreen(buffer: TerminalTextBuffer, maxScrollbackRows: Int = MAX_SCROLLBACK_ROWS): TerminalScreen {
    buffer.lock()
    try {
        val usesAlternateScreen = buffer.isUsingAlternateBuffer
        val scrollbackRows = if (usesAlternateScreen) 0 else minOf(buffer.historyLinesCount, maxScrollbackRows)
        val rows = (-scrollbackRows until buffer.screenLinesCount).map { rowOf(buffer.getLine(it)) }
        return TerminalScreen(rows, buffer.width, usesAlternateScreen)
    } finally {
        buffer.unlock()
    }
}

private fun rowOf(line: TerminalLine): TerminalRow {
    val cells = buildString {
        line.entries.forEach { entry -> append(entry.text) }
    }
    val text = cells.filter { it != DOUBLE_WIDTH_SECOND_CELL }.replace(EMPTY_CELL, ' ')
    val wrapped = line.isWrapped
    return TerminalRow(if (wrapped) text else text.trimEnd(), wrapped)
}
