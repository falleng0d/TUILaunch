package com.github.atm1020.tuilaunch.prompt

internal data class PromptBlock(val markerLine: Int, val text: String)

private const val BLOCK_DELIMITER = "---"
private const val FENCE_MARKERS = "`~"
private const val MINIMUM_FENCE_RUN = 3
private const val ESTIMATED_CHARACTERS_PER_LINE = 32

private data class FenceRun(val marker: Char, val length: Int)

private data class BlockLines(val firstContentLine: Int, val lastContentLine: Int)

internal fun parsePromptBlocks(text: String): List<PromptBlock> {
    val lines = LineOffsets(text)
    return blockLinesIn(lines).map {
        PromptBlock(it.firstContentLine, lines.joinedContent(it.firstContentLine, it.lastContentLine))
    }
}

internal fun promptMarkerLines(text: CharSequence): List<Int> =
    blockLinesIn(LineOffsets(text)).map { it.firstContentLine }

internal fun promptBlockTextAt(text: CharSequence, markerLine: Int): String? {
    val lines = LineOffsets(text)
    val block = blockLinesIn(lines).firstOrNull { it.firstContentLine == markerLine } ?: return null
    return lines.joinedContent(block.firstContentLine, block.lastContentLine)
}

internal fun canChangeBlockStructure(line: CharSequence): Boolean =
    isBlank(line, 0, line.length) ||
        isDelimiter(line, 0, line.length) ||
        fenceOpenerAt(line, 0, line.length) != null

private fun blockLinesIn(lines: LineOffsets): List<BlockLines> {
    val blocks = mutableListOf<BlockLines>()
    var segmentStart = 0
    lines.forEachDelimiterLine { delimiterLine ->
        blockOf(lines, segmentStart, delimiterLine - 1)?.let(blocks::add)
        segmentStart = delimiterLine + 1
    }
    blockOf(lines, segmentStart, lines.lineCount - 1)?.let(blocks::add)
    return blocks
}

private fun blockOf(lines: LineOffsets, fromLine: Int, toLine: Int): BlockLines? {
    var firstContentLine = fromLine
    while (firstContentLine <= toLine && lines.isBlankLine(firstContentLine)) firstContentLine++
    if (firstContentLine > toLine) return null
    var lastContentLine = toLine
    while (lines.isBlankLine(lastContentLine)) lastContentLine--
    return BlockLines(firstContentLine, lastContentLine)
}

private class ShortestUnclosedFenceRuns {

    private val shortestByMarker = HashMap<Char, Int>(2)

    fun alreadyKnownUnclosed(opener: FenceRun): Boolean {
        val shortest = shortestByMarker[opener.marker] ?: return false
        return opener.length >= shortest
    }

    fun remember(opener: FenceRun) {
        val shortest = shortestByMarker[opener.marker]
        if (shortest == null || opener.length < shortest) shortestByMarker[opener.marker] = opener.length
    }
}

private class LineOffsets(private val text: CharSequence) {

    private var starts = IntArray(maxOf(16, text.length / ESTIMATED_CHARACTERS_PER_LINE + 1))

    var lineCount = 1
        private set

    init {
        indexLineStarts()
    }

    private fun indexLineStarts() {
        var index = 0
        while (index < text.length) {
            val character = text[index]
            index++
            if (character != '\n' && character != '\r') continue
            if (character == '\r' && index < text.length && text[index] == '\n') index++
            if (lineCount == starts.size) starts = starts.copyOf(starts.size * 2)
            starts[lineCount++] = index
        }
    }

    private fun startOf(line: Int): Int = starts[line]

    private fun contentEndOf(line: Int): Int {
        if (line == lineCount - 1) return text.length
        val lineStart = starts[line]
        var end = starts[line + 1]
        if (end > lineStart && text[end - 1] == '\n') end--
        if (end > lineStart && text[end - 1] == '\r') end--
        return end
    }

    fun isBlankLine(line: Int): Boolean = isBlank(text, startOf(line), contentEndOf(line))

    fun forEachDelimiterLine(action: (Int) -> Unit) {
        val unclosedRuns = ShortestUnclosedFenceRuns()
        var line = 0
        while (line < lineCount) {
            val closingLine = openerAt(line)?.let { closingLineFor(it, line, unclosedRuns) }
            if (closingLine != null) {
                line = closingLine + 1
                continue
            }
            if (isDelimiterLine(line)) action(line)
            line++
        }
    }

    private fun isDelimiterLine(line: Int): Boolean = isDelimiter(text, startOf(line), contentEndOf(line))

    private fun openerAt(line: Int): FenceRun? = fenceOpenerAt(text, startOf(line), contentEndOf(line))

    private fun closingLineFor(
        opener: FenceRun,
        openingLine: Int,
        unclosedRuns: ShortestUnclosedFenceRuns,
    ): Int? {
        if (unclosedRuns.alreadyKnownUnclosed(opener)) return null
        var line = openingLine + 1
        while (line < lineCount) {
            if (closesFence(text, startOf(line), contentEndOf(line), opener)) return line
            line++
        }
        unclosedRuns.remember(opener)
        return null
    }

    fun joinedContent(firstLine: Int, lastLine: Int): String {
        if (firstLine == lastLine) {
            return text.subSequence(startOf(firstLine), contentEndOf(firstLine)).toString()
        }
        val joined = StringBuilder(contentEndOf(lastLine) - startOf(firstLine))
        for (line in firstLine..lastLine) {
            if (line > firstLine) joined.append('\n')
            joined.append(text, startOf(line), contentEndOf(line))
        }
        return joined.toString()
    }
}

private fun firstContentIndex(text: CharSequence, from: Int, to: Int): Int {
    var index = from
    while (index < to && text[index].isWhitespace()) index++
    return index
}

private fun contentEndIndex(text: CharSequence, from: Int, to: Int): Int {
    var index = to
    while (index > from && text[index - 1].isWhitespace()) index--
    return index
}

private fun isBlank(text: CharSequence, from: Int, to: Int): Boolean =
    firstContentIndex(text, from, to) == to

private fun isDelimiter(text: CharSequence, from: Int, to: Int): Boolean {
    val contentStart = firstContentIndex(text, from, to)
    val contentEnd = contentEndIndex(text, contentStart, to)
    if (contentEnd - contentStart != BLOCK_DELIMITER.length) return false
    return BLOCK_DELIMITER.indices.all { text[contentStart + it] == BLOCK_DELIMITER[it] }
}

private fun fenceOpenerAt(text: CharSequence, from: Int, to: Int): FenceRun? {
    val contentStart = firstContentIndex(text, from, to)
    val contentEnd = contentEndIndex(text, contentStart, to)
    if (contentStart == contentEnd) return null
    val marker = text[contentStart]
    if (marker !in FENCE_MARKERS) return null
    var runLength = 0
    while (contentStart + runLength < contentEnd && text[contentStart + runLength] == marker) runLength++
    return if (runLength >= MINIMUM_FENCE_RUN) FenceRun(marker, runLength) else null
}

private fun closesFence(text: CharSequence, from: Int, to: Int, opener: FenceRun): Boolean {
    val contentStart = firstContentIndex(text, from, to)
    val contentEnd = contentEndIndex(text, contentStart, to)
    if (contentEnd - contentStart < opener.length) return false
    return (contentStart until contentEnd).all { text[it] == opener.marker }
}
