package com.github.atm1020.tuilaunch.prompt

private const val REFERENCE_MARKER = '@'
private const val LINE_MARKER = "#L"

internal data class LineRange(val start: Int, val end: Int)

internal fun fileReference(relativePath: String, lines: LineRange?): String = buildString {
    append(REFERENCE_MARKER)
    append(relativePath)
    if (lines != null) {
        append(LINE_MARKER)
        append(lines.start)
        if (lines.end != lines.start) {
            append('-')
            append(lines.end)
        }
    }
}

internal fun pathRelativeToRoot(root: String, filePath: String): String? {
    val normalizedRoot = normalizeSeparators(root).trimEnd('/')
    val normalizedFile = normalizeSeparators(filePath)
    if (normalizedRoot.isEmpty()) return null
    if (!normalizedFile.startsWith("$normalizedRoot/")) return null
    return normalizedFile.removePrefix("$normalizedRoot/").ifEmpty { null }
}

private fun normalizeSeparators(path: String): String = path.replace('\\', '/')

/**
 * A selection that stops at the very start of a line covers no text on it, which is what dragging
 * down past the last line of interest produces.
 */
internal fun selectedLineRange(startLine: Int, endLine: Int, endsAtLineStart: Boolean): LineRange {
    val lastLine = if (endsAtLineStart && endLine > startLine) endLine - 1 else endLine
    return LineRange(startLine + 1, lastLine + 1)
}

internal fun referenceInsertion(existing: CharSequence, offset: Int, reference: String): String {
    val needsLeadingSpace = offset > 0 && !existing[offset - 1].isWhitespace()
    val needsTrailingSpace = offset >= existing.length || !existing[offset].isWhitespace()
    return buildString {
        if (needsLeadingSpace) append(' ')
        append(reference)
        if (needsTrailingSpace) append(' ')
    }
}
