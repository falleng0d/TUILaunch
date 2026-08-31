package com.github.atm1020.tuilaunch.prompt

internal data class PromptBlock(val markerLine: Int, val text: String)

private const val BLOCK_DELIMITER = "---"
private const val FENCE_MARKERS = "`~"
private const val MINIMUM_FENCE_RUN = 3

private data class FenceRun(val marker: Char, val length: Int)

internal fun parsePromptBlocks(text: String): List<PromptBlock> {
    val lines = withoutCarriageReturns(text).split("\n")
    val lineBeforeTheFile = -1
    val lineAfterTheFile = lines.size
    val segmentBounds = listOf(lineBeforeTheFile) + delimiterLinesOutsideCodeFences(lines) + lineAfterTheFile
    return segmentBounds
        .zipWithNext()
        .mapNotNull { (boundBefore, boundAfter) -> blockBetween(lines, boundBefore, boundAfter) }
}

private fun withoutCarriageReturns(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

private fun delimiterLinesOutsideCodeFences(lines: List<String>): List<Int> {
    val fencedRanges = matchedFenceRanges(lines)
    return lines.indices.filter { index ->
        lines[index].trim() == BLOCK_DELIMITER && fencedRanges.none { index in it }
    }
}

private fun matchedFenceRanges(lines: List<String>): List<IntRange> {
    val fencedRanges = mutableListOf<IntRange>()
    var index = 0
    while (index < lines.size) {
        val closingLine = fenceOpenerAt(lines[index])?.let { closingLineFor(lines, it, index) }
        if (closingLine == null) {
            index++
        } else {
            fencedRanges.add(index..closingLine)
            index = closingLine + 1
        }
    }
    return fencedRanges
}

private fun fenceOpenerAt(line: String): FenceRun? {
    val trimmed = line.trim()
    val marker = trimmed.firstOrNull() ?: return null
    if (marker !in FENCE_MARKERS) return null
    val runLength = trimmed.takeWhile { it == marker }.length
    return if (runLength >= MINIMUM_FENCE_RUN) FenceRun(marker, runLength) else null
}

private fun closingLineFor(lines: List<String>, opener: FenceRun, openingLine: Int): Int? =
    (openingLine + 1 until lines.size).firstOrNull { closesFence(lines[it], opener) }

private fun closesFence(line: String, opener: FenceRun): Boolean {
    val trimmed = line.trim()
    return trimmed.length >= opener.length && trimmed.all { it == opener.marker }
}

private fun blockBetween(lines: List<String>, boundBefore: Int, boundAfter: Int): PromptBlock? {
    val segmentLines = (boundBefore + 1) until boundAfter
    val firstContentLine = segmentLines.firstOrNull { lines[it].isNotBlank() } ?: return null
    val lastContentLine = segmentLines.last { lines[it].isNotBlank() }
    return PromptBlock(
        markerLine = firstContentLine,
        text = lines.subList(firstContentLine, lastContentLine + 1).joinToString("\n"),
    )
}
