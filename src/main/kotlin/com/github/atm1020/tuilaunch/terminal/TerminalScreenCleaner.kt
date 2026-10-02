package com.github.atm1020.tuilaunch.terminal

import com.github.atm1020.tuilaunch.resume.AgentCliKind

private const val FOOTER_SEARCH_ROWS = 12
private const val WRAPPED_RUN_MIN_WIDTH_SHARE = 0.85
private const val WRAP_WIDTH_SLACK = 2
private const val RULE = '─'
private const val TRUNCATION_MARK = '…'
private const val CODE_FENCE = "```"
private const val CODEX_MESSAGE_MARKER = '•'
private const val OMP_INPUT_MARKER = '▎'
private const val OPENCODE_INPUT_FRAME = '┃'
private const val OPENCODE_INPUT_BOTTOM = '╹'

private val LEADING_MARKERS = setOf('⏺', '●', '⎿', '✻', '✶', '✳', '✢', '✽', '▣', '❯', '›', OMP_INPUT_MARKER)
private val LIST_ITEM_START = Regex("""^([-*•]|\d+[.)])\s+""")
private val BLOCK_START = Regex("""^(([-*•]|\d+[.)])\s|#{1,6}\s|\$ |$CODE_FENCE)""")

private val CLAUDE_STATUS_ROWS = listOf(
    Regex("""^\s*[✻✶✳✢✽·]\s+\p{L}+ for \d.*$"""),
    Regex("""^\s*[✻✶✳✢✽·]\s+\p{L}+….*$"""),
)
private val CODEX_STATUS_ROWS = listOf(
    Regex("""^[\s─]*Worked for \d.*$"""),
    Regex("""^\s*•\s+Working\b.*\besc to interrupt\b.*$"""),
    Regex("""^\s*[+-] (Show|Hide) details\s*$"""),
)
private val OPENCODE_STATUS_ROWS = listOf(
    Regex("""^\s*▣\s.*$"""),
)
private val OMP_STATUS_ROWS = listOf(
    Regex("""^\s*\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\s{2,}\S.*$"""),
    Regex("""^ Tip: .*$"""),
)
private val OMP_WELCOME_BOX_TOP = Regex("""^╭─+ omp v\S+ ─.*$""")
private val OMP_NOTICE_TITLES = setOf("What's New", "Update Available")

fun cleanTerminalScreen(screen: TerminalScreen, kind: AgentCliKind?): String {
    val rows = joinSoftWrappedRows(screen.rows)
    val footerStart = footerStartOf(rows, kind, screen.columns) ?: rows.size
    val withoutChrome = withoutChromeRows(rows.take(footerStart), kind, screen.columns)
    val prose = withoutChrome.map { proseOf(it, kind) }
    val paragraphs = unwrappedRuns(prose, screen.columns)
    return paragraphs.joinToString("\n\n") { it.joinToString("\n") }
}

private fun joinSoftWrappedRows(rows: List<TerminalRow>): List<String> {
    val joined = mutableListOf<String>()
    val current = StringBuilder()
    for (row in rows) {
        current.append(row.text)
        if (row.isWrapped) continue
        joined.add(current.toString().trimEnd())
        current.clear()
    }
    if (current.isNotEmpty()) joined.add(current.toString().trimEnd())
    return joined.dropLastWhile { it.isEmpty() }
}

private fun footerStartOf(rows: List<String>, kind: AgentCliKind?, columns: Int): Int? {
    val bottom = (rows.size - FOOTER_SEARCH_ROWS).coerceAtLeast(0) until rows.size
    return when (kind) {
        AgentCliKind.CLAUDE -> claudeInputBoxStart(rows, bottom, columns)
        AgentCliKind.CODEX -> bottom.lastOrNull { rows[it].startsWith('›') }
        AgentCliKind.OPENCODE -> openCodeInputBoxStart(rows, bottom)
        AgentCliKind.OMP -> ompInputStart(rows, bottom)
        null -> null
    }
}

private fun claudeInputBoxStart(rows: List<String>, bottom: IntRange, columns: Int): Int? {
    val rules = bottom.filter { isRule(rows[it], columns) }
    return rules.getOrNull(rules.size - 2) ?: rules.lastOrNull()
}

private fun openCodeInputBoxStart(rows: List<String>, bottom: IntRange): Int? {
    var start = bottom.lastOrNull { rows[it].trimStart().startsWith(OPENCODE_INPUT_BOTTOM) } ?: return null
    while (start > 0 && rows[start - 1].trimStart().startsWith(OPENCODE_INPUT_FRAME)) start--
    return start
}

private fun ompInputStart(rows: List<String>, bottom: IntRange): Int? {
    var start = bottom.lastOrNull { rows[it].startsWith(OMP_INPUT_MARKER) } ?: return null
    while (start > 0 && rows[start - 1].startsWith(OMP_INPUT_MARKER)) start--
    return start
}

private fun isRule(row: String, columns: Int): Boolean =
    row.isNotEmpty() && row.all { it == RULE } && row.length >= columns / 2

private fun withoutChromeRows(rows: List<String>, kind: AgentCliKind?, columns: Int): List<String> {
    val statusRows = when (kind) {
        AgentCliKind.CLAUDE -> CLAUDE_STATUS_ROWS
        AgentCliKind.CODEX -> CODEX_STATUS_ROWS
        AgentCliKind.OPENCODE -> OPENCODE_STATUS_ROWS
        AgentCliKind.OMP -> OMP_STATUS_ROWS
        null -> emptyList()
    }
    val startupRows = if (kind == AgentCliKind.OMP) ompStartupRows(rows, columns) else emptySet()
    return rows.filterIndexed { index, row ->
        index !in startupRows && statusRows.none { it.matches(row) }
    }
}

private fun ompStartupRows(rows: List<String>, columns: Int): Set<Int> {
    val dropped = mutableSetOf<Int>()
    var index = 0
    while (index < rows.size) {
        val end = when {
            OMP_WELCOME_BOX_TOP.matches(rows[index]) -> nextRowAfter(rows, index) { it.startsWith('╰') }
            isRule(rows[index], columns) && rows.getOrNull(index + 1)?.trim() in OMP_NOTICE_TITLES ->
                nextRowAfter(rows, index) { isRule(it, columns) }
            else -> null
        }
        if (end == null) {
            index++
            continue
        }
        dropped.addAll(index..end)
        index = end + 1
    }
    return dropped
}

private fun nextRowAfter(rows: List<String>, start: Int, matches: (String) -> Boolean): Int? =
    (start + 1 until rows.size).firstOrNull { matches(rows[it]) }

private fun proseOf(row: String, kind: AgentCliKind?): String {
    val withoutDrawing = withoutDrawingCharacters(row)
    val withoutMarker = withoutLeadingMarker(withoutDrawing, kind)
    val lostCharacters = withoutMarker != row
    val nothingLeft = withoutMarker.isBlank() || (lostCharacters && withoutMarker.none { it.isLetterOrDigit() })
    return if (nothingLeft) "" else withoutMarker.trimEnd()
}

private fun withoutDrawingCharacters(row: String): String {
    val cleaned = StringBuilder(row.length)
    var index = 0
    while (index < row.length) {
        val codePoint = row.codePointAt(index)
        if (isNeverProse(codePoint)) cleaned.append(' ') else cleaned.appendCodePoint(codePoint)
        index += Character.charCount(codePoint)
    }
    return cleaned.toString()
}

private fun isNeverProse(codePoint: Int): Boolean =
    codePoint in 0x2500..0x259F ||
        codePoint in 0x2800..0x28FF ||
        codePoint in 0xE000..0xF8FF ||
        codePoint in 0xFE00..0xFE0F ||
        codePoint in 0xE0100..0xE01EF ||
        codePoint >= 0xF0000

private fun withoutLeadingMarker(row: String, kind: AgentCliKind?): String {
    val markerIndex = row.indexOfFirst { it != ' ' }
    if (markerIndex < 0) return row
    val marker = row[markerIndex]
    val isCodexMessageMarker = kind == AgentCliKind.CODEX && markerIndex == 0 && marker == CODEX_MESSAGE_MARKER
    if (marker !in LEADING_MARKERS && !isCodexMessageMarker) return row
    val standsAlone = markerIndex + 1 == row.length || row[markerIndex + 1] == ' '
    if (!standsAlone) return row
    return row.substring(0, markerIndex) + " " + row.substring(markerIndex + 1)
}

private fun unwrappedRuns(rows: List<String>, columns: Int): List<List<String>> {
    val proseRows = withoutUnmatchedLeadingFence(rows)
    val fenced = insideAFence(proseRows)
    val runs = mutableListOf<List<ScreenLine>>()
    var run = mutableListOf<ScreenLine>()
    for ((index, row) in proseRows.withIndex()) {
        if (row.isNotEmpty()) {
            run.add(ScreenLine(row, fenced[index]))
            continue
        }
        if (run.isNotEmpty()) runs.add(run)
        run = mutableListOf()
    }
    if (run.isNotEmpty()) runs.add(run)
    return runs.map { withoutCommonIndent(unwrapped(it, columns)) }
}

private class ScreenLine(val text: String, val isCode: Boolean)

private fun withoutUnmatchedLeadingFence(rows: List<String>): List<String> {
    val fences = rows.indices.filter { startsAFence(rows[it]) }
    val firstText = rows.indexOfFirst { it.isNotEmpty() }
    val closesABlockThatScrolledAway = fences.size % 2 == 1 && fences.first() == firstText &&
        rows[firstText].trim() == CODE_FENCE
    if (!closesABlockThatScrolledAway) return rows
    return rows.drop(firstText + 1)
}

private fun insideAFence(rows: List<String>): List<Boolean> {
    var open = false
    return rows.map { row ->
        val fence = startsAFence(row)
        val isCode = open || fence
        if (fence) open = !open
        isCode
    }
}

private fun unwrapped(run: List<ScreenLine>, columns: Int): List<String> {
    val wrapWidth = run.maxOf { it.text.length }
    if (wrapWidth < columns * WRAPPED_RUN_MIN_WIDTH_SHARE) return run.map { it.text }
    val lines = mutableListOf<String>()
    var line = run.first().text
    var lastRow = run.first()
    for (row in run.drop(1)) {
        if (!lastRow.isCode && !row.isCode && theTuiWrapped(lastRow.text, row.text, wrapWidth)) {
            line = line + " " + row.text.trimStart()
        } else {
            lines.add(line)
            line = row.text
        }
        lastRow = row
    }
    lines.add(line)
    return lines
}

private fun theTuiWrapped(row: String, next: String, wrapWidth: Int): Boolean {
    if (row.endsWith(TRUNCATION_MARK)) return false
    val nextText = next.trimStart()
    if (BLOCK_START.containsMatchIn(nextText)) return false
    if (indentOf(next) < textStartOf(row)) return false
    val firstWord = nextText.substringBefore(' ')
    return row.length + 1 + firstWord.length > wrapWidth - WRAP_WIDTH_SLACK
}

private fun startsAFence(row: String): Boolean = row.trimStart().startsWith(CODE_FENCE)

private fun indentOf(row: String): Int = row.length - row.trimStart().length

private fun textStartOf(row: String): Int {
    val indent = indentOf(row)
    val listMarker = LIST_ITEM_START.find(row.substring(indent))?.value?.length ?: 0
    return indent + listMarker
}

private fun withoutCommonIndent(lines: List<String>): List<String> {
    val commonIndent = lines.minOf { indentOf(it) }
    return lines.map { it.substring(commonIndent) }
}
