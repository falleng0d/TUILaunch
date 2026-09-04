package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.copilot.InlineCompletionItem
import com.github.atm1020.tuilaunch.copilot.LspPosition
import com.github.atm1020.tuilaunch.copilot.LspRange
import com.github.atm1020.tuilaunch.copilot.MAX_HISTORY_CONTEXT_CHARS
import com.github.atm1020.tuilaunch.copilot.PromptBoxCopilotDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBoxCopilotDocumentTest {

    private val separator = "\n\n---\n\n"

    private fun newDocument() = PromptBoxCopilotDocument(sequence = 7)

    private fun itemReplacing(
        insertText: String,
        startCharacter: Int,
        endCharacter: Int,
        line: Int = 0,
    ) = InlineCompletionItem(
        insertText = insertText,
        range = LspRange(LspPosition(line, startCharacter), LspPosition(line, endCharacter)),
        acceptCommand = "github.copilot.didAcceptCompletionItem",
        acceptArguments = listOf("uuid"),
    )

    @Test
    fun `the uri names the box and the language is markdown`() {
        val document = newDocument()

        assertEquals("tuilaunch://prompt-box/7.md", document.uri)
        assertEquals("markdown", document.languageId)
    }

    @Test
    fun `history blocks are joined with separators and the draft comes last`() {
        val snapshot = newDocument().snapshot(listOf("first", "second"), "third", includeHistory = true)

        assertEquals("first${separator}second${separator}third", snapshot.text)
        assertEquals("first${separator}second$separator".length, snapshot.draftStart)
    }

    @Test
    fun `an empty history leaves the draft alone`() {
        val snapshot = newDocument().snapshot(emptyList(), "only the draft", includeHistory = true)

        assertEquals("only the draft", snapshot.text)
        assertEquals(0, snapshot.draftStart)
    }

    @Test
    fun `history turned off leaves the draft alone`() {
        val snapshot = newDocument().snapshot(listOf("first", "second"), "draft", includeHistory = false)

        assertEquals("draft", snapshot.text)
        assertEquals(0, snapshot.draftStart)
    }

    @Test
    fun `the cap drops whole oldest blocks and keeps the newest ones`() {
        val tooBigToFit = "x".repeat(MAX_HISTORY_CONTEXT_CHARS)

        val snapshot = newDocument().snapshot(
            listOf(tooBigToFit, "second", "third"),
            "draft",
            includeHistory = true,
        )

        assertEquals("second${separator}third${separator}draft", snapshot.text)
    }

    @Test
    fun `a single block longer than the cap is dropped whole`() {
        val block = "x".repeat(MAX_HISTORY_CONTEXT_CHARS + 1)

        val snapshot = newDocument().snapshot(listOf(block), "draft", includeHistory = true)

        assertEquals("draft", snapshot.text)
    }

    @Test
    fun `the cap counts the separators between the blocks it keeps`() {
        val block = "x".repeat(MAX_HISTORY_CONTEXT_CHARS / 2)

        val snapshot = newDocument().snapshot(listOf(block, block), "draft", includeHistory = true)

        assertEquals("${block}${separator}draft", snapshot.text)
    }

    @Test
    fun `a draft offset maps to a position on the line the history ends on`() {
        val snapshot = newDocument().snapshot(listOf("first"), "second", includeHistory = true)

        assertEquals(LspPosition(4, 0), snapshot.positionOf(0))
        assertEquals(LspPosition(4, 6), snapshot.positionOf(6))
    }

    @Test
    fun `a draft offset maps across the lines of the draft itself`() {
        val snapshot = newDocument().snapshot(emptyList(), "one\ntwo\nthree", includeHistory = true)

        assertEquals(LspPosition(0, 3), snapshot.positionOf(3))
        assertEquals(LspPosition(1, 0), snapshot.positionOf(4))
        assertEquals(LspPosition(2, 5), snapshot.positionOf(13))
    }

    @Test
    fun `positions count astral characters as the two utf-16 units they occupy`() {
        val snapshot = newDocument().snapshot(emptyList(), "😀 ok", includeHistory = true)

        assertEquals(5, snapshot.text.length)
        assertEquals(LspPosition(0, 2), snapshot.positionOf(2))
        assertEquals(LspPosition(0, 5), snapshot.positionOf(5))
    }

    @Test
    fun `ghost text is the insert text minus what is already on the line`() {
        val snapshot = newDocument().snapshot(listOf("older"), "Fix the ", includeHistory = true)

        val ghostText = snapshot.ghostTextFor(itemReplacing("Fix the bug", 0, 8, line = 4), 8)

        assertEquals("bug", ghostText)
    }

    @Test
    fun `an item whose insert text does not start with the typed prefix is skipped`() {
        val snapshot = newDocument().snapshot(emptyList(), "Fix the ", includeHistory = true)

        assertNull(snapshot.ghostTextFor(itemReplacing("Repair the bug", 0, 8), 8))
    }

    @Test
    fun `an item that adds nothing to what is typed is skipped`() {
        val snapshot = newDocument().snapshot(emptyList(), "Fix the bug", includeHistory = true)

        assertNull(snapshot.ghostTextFor(itemReplacing("Fix the bug", 0, 11), 11))
    }

    @Test
    fun `an item whose range ends past the cursor is skipped`() {
        val snapshot = newDocument().snapshot(emptyList(), "Fix the bug", includeHistory = true)

        assertNull(snapshot.ghostTextFor(itemReplacing("Fix the bug now", 0, 11), 8))
    }

    @Test
    fun `an item that starts on another line is skipped`() {
        val snapshot = newDocument().snapshot(emptyList(), "one\ntwo", includeHistory = true)

        assertNull(snapshot.ghostTextFor(itemReplacing("two three", 0, 3, line = 0), 7))
    }

    @Test
    fun `an item that starts after the cursor is skipped`() {
        val snapshot = newDocument().snapshot(emptyList(), "Fix", includeHistory = true)

        assertNull(snapshot.ghostTextFor(itemReplacing("Fix the bug", 2, 2), 1))
    }

    @Test
    fun `the first sync opens the document and a changed text is a change`() = runBlocking {
        val document = newDocument()
        val server = FakeCopilotCompletionServer()

        document.sync(server, "one")
        document.sync(server, "two")

        assertEquals(
            listOf(
                CopilotServerCall.Opened(document.uri, "markdown", 1, "one"),
                CopilotServerCall.Changed(document.uri, 2, "two"),
            ),
            server.calls,
        )
    }

    @Test
    fun `the version only grows when the text changed`() = runBlocking {
        val document = newDocument()
        val server = FakeCopilotCompletionServer()

        document.sync(server, "same")
        val versionAfterOpening = document.version
        document.sync(server, "same")

        assertEquals(1, versionAfterOpening)
        assertEquals(1, document.version)
        assertEquals(1, server.calls.size)
    }

    @Test
    fun `closing tells the server once`() = runBlocking {
        val document = newDocument()
        val server = FakeCopilotCompletionServer()
        document.sync(server, "one")

        document.close(server)
        document.close(server)

        assertEquals(listOf(CopilotServerCall.Closed(document.uri)), server.calls.drop(1))
    }

    @Test
    fun `a document that was never opened is not closed`() {
        val server = FakeCopilotCompletionServer()

        newDocument().close(server)

        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun `a restarted server is opened again with a version that keeps growing`() = runBlocking {
        val document = newDocument()
        val first = FakeCopilotCompletionServer()
        val second = FakeCopilotCompletionServer()
        document.sync(first, "one")

        document.sync(second, "one")

        assertEquals(listOf(CopilotServerCall.Opened(document.uri, "markdown", 2, "one")), second.calls)
    }
}
