package com.github.atm1020.tuilaunch.copilot

import java.util.concurrent.atomic.AtomicLong

const val MAX_HISTORY_CONTEXT_CHARS = 48_000

private const val BLOCK_SEPARATOR = "\n\n---\n\n"
private const val PROMPT_BOX_LANGUAGE_ID = "markdown"

class VirtualPromptText(val text: String, val draftStart: Int) {

    fun positionOf(draftOffset: Int): LspPosition {
        val offset = offsetOf(draftOffset)
        var line = 0
        var lineStart = 0
        for (index in 0 until offset) {
            if (text[index] != '\n') continue
            line++
            lineStart = index + 1
        }
        return LspPosition(line, offset - lineStart)
    }

    fun ghostTextFor(item: InlineCompletionItem, cursorDraftOffset: Int): String? {
        val cursor = positionOf(cursorDraftOffset)
        if (item.range.start.line != cursor.line) return null
        if (item.range.start.character > cursor.character) return null
        if (isAfter(item.range.end, cursor)) return null
        val cursorOffset = offsetOf(cursorDraftOffset)
        val lineStart = cursorOffset - cursor.character
        val alreadyTyped = text.substring(lineStart + item.range.start.character, cursorOffset)
        if (!item.insertText.startsWith(alreadyTyped)) return null
        return item.insertText.substring(alreadyTyped.length).takeIf { it.isNotEmpty() }
    }

    private fun offsetOf(draftOffset: Int): Int = (draftStart + draftOffset).coerceIn(0, text.length)

    private fun isAfter(position: LspPosition, other: LspPosition): Boolean =
        position.line > other.line || (position.line == other.line && position.character > other.character)
}

class PromptBoxCopilotDocument(sequence: Long = nextSequence()) {

    val uri: String = "tuilaunch://prompt-box/$sequence.md"
    val languageId: String = PROMPT_BOX_LANGUAGE_ID

    var version: Int = 0
        private set

    private var openedAgainst: CopilotCompletionServer? = null
    private var textAlreadySent: String? = null

    fun snapshot(historyBlocks: List<String>, draft: String, includeHistory: Boolean): VirtualPromptText {
        val context = if (includeHistory) newestBlocksWithinTheCap(historyBlocks) else emptyList()
        if (context.isEmpty()) return VirtualPromptText(draft, 0)
        val history = context.joinToString(BLOCK_SEPARATOR) + BLOCK_SEPARATOR
        return VirtualPromptText(history + draft, history.length)
    }

    suspend fun sync(server: CopilotCompletionServer, text: String) {
        if (openedAgainst !== server) {
            version++
            openedAgainst = server
            textAlreadySent = text
            server.didOpen(uri, languageId, version, text)
            return
        }
        if (textAlreadySent == text) return
        version++
        textAlreadySent = text
        server.didChange(uri, version, text)
    }

    fun close(server: CopilotCompletionServer) {
        if (openedAgainst !== server) return
        openedAgainst = null
        textAlreadySent = null
        server.didClose(uri)
    }

    private fun newestBlocksWithinTheCap(blocks: List<String>): List<String> {
        var kept = 0
        var length = 0
        for (index in blocks.indices.reversed()) {
            val separator = if (kept == 0) 0 else BLOCK_SEPARATOR.length
            val grown = length + separator + blocks[index].length
            if (grown > MAX_HISTORY_CONTEXT_CHARS) break
            length = grown
            kept++
        }
        return blocks.subList(blocks.size - kept, blocks.size)
    }

    companion object {
        private val sequences = AtomicLong()

        fun nextSequence(): Long = sequences.incrementAndGet()
    }
}
