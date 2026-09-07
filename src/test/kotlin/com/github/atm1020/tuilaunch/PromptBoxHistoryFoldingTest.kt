package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.prompt.PromptBox
import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase

private const val MARKDOWN_FILE_TYPE_NAME = "Markdown"

private fun lineTextOf(document: Document, line: Int): String =
    document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))

class PromptBoxHistoryFoldingTest : BasePlatformTestCase() {

    private val boxDisposables = mutableListOf<Disposable>()

    private val entryWithAHeadingAndAFence = """
        # Heading
        body text

        ```kotlin
        val x = 1
        ```
        after the fence
    """.trimIndent()

    override fun tearDown() {
        try {
            boxDisposables.forEach { Disposer.dispose(it) }
        } finally {
            super.tearDown()
        }
    }

    private fun boxOver(promptFileText: String): PromptBox {
        myFixture.configureByText("PROMPT.md", promptFileText)
        val promptDocument = myFixture.editor.document
        val disposable = Disposer.newDisposable("PromptBoxHistoryFoldingTest")
        boxDisposables.add(disposable)
        val box = PromptBox(project, disposable, existingPromptDocument = { promptDocument })
        box.installEditor()
        return box
    }

    private fun regionFirstLines(box: PromptBox): List<String> =
        box.editor.foldingModel.allFoldRegions
            .sortedBy { it.startOffset }
            .map { lineTextOf(box.editor.document, box.editor.document.getLineNumber(it.startOffset)) }

    private fun collapsedRegionFirstLines(box: PromptBox): List<String> =
        box.editor.foldingModel.allFoldRegions
            .filter { !it.isExpanded }
            .sortedBy { it.startOffset }
            .map { lineTextOf(box.editor.document, box.editor.document.getLineNumber(it.startOffset)) }

    private fun buildTheFoldRegions(box: PromptBox) {
        CodeFoldingManager.getInstance(project).updateFoldRegions(box.editor)
    }

    private fun expandEveryRegion(box: PromptBox) {
        val foldingModel = box.editor.foldingModel
        foldingModel.runBatchFoldingOperation {
            foldingModel.allFoldRegions.forEach { it.isExpanded = true }
        }
    }

    private fun appendInTheBox(box: PromptBox, text: String) {
        WriteCommandAction.writeCommandAction(project).run<RuntimeException> {
            box.editor.document.insertString(box.editor.document.textLength, text)
        }
    }

    fun testTheBoxEditorTakesTheMarkdownFileTypeSoTheMarkdownFoldingApplies() {
        val box = boxOver("first prompt\n")

        assertEquals(MARKDOWN_FILE_TYPE_NAME, box.editor.virtualFile?.fileType?.name)
    }

    fun testUpCollapsesTheCodeBlocksOfTheEntryAndNothingElse() {
        val box = boxOver("$entryWithAHeadingAndAFence\n")

        box.historyPrevious()

        assertEquals(entryWithAHeadingAndAFence, box.text)
        assertEquals(listOf("# Heading", "```kotlin"), regionFirstLines(box))
        assertEquals(listOf("```kotlin"), collapsedRegionFirstLines(box))
        assertEquals(0, box.editor.caretModel.offset)
    }

    fun testEveryFencedBlockOfTheEntryArrivesCollapsed() {
        val entry = """
            first block

            ```
            plain
            ```

              ~~~~
              tilde
              ~~~~
        """.trimIndent()
        val box = boxOver("$entry\n")

        box.historyPrevious()

        assertEquals(listOf("```", "  ~~~~"), collapsedRegionFirstLines(box))
    }

    fun testAFencedBlockInsideAListItemArrivesCollapsed() {
        val entry = """
            steps:

            - first step

              ```kotlin
              val x = 1
              ```

            - second step
        """.trimIndent()
        val box = boxOver("$entry\n")

        box.historyPrevious()

        assertEquals(listOf("- first step", "  ```kotlin"), regionFirstLines(box))
        assertEquals(listOf("  ```kotlin"), collapsedRegionFirstLines(box))
    }

    fun testAFencedBlockInsideABlockQuoteArrivesCollapsed() {
        val entry = """
            quoted:

            > intro
            >
            > ```kotlin
            > val x = 1
            > ```

            tail
        """.trimIndent()
        val box = boxOver("$entry\n")

        box.historyPrevious()

        assertEquals(listOf("> intro", "> ```kotlin"), regionFirstLines(box))
        assertEquals(listOf("> ```kotlin"), collapsedRegionFirstLines(box))
    }

    fun testAnUnclosedFenceLeavesTheRestOfTheEntryVisible() {
        val entry = """
            intro

            ```kotlin
            val x = 1
            still code
            more prose
        """.trimIndent()
        val box = boxOver("$entry\n")

        box.historyPrevious()

        assertEquals(listOf("```kotlin"), regionFirstLines(box))
        assertEmpty(collapsedRegionFirstLines(box))
    }

    fun testDownRecomputesTheFoldingForTheEntryItShows() {
        val box = boxOver("first prompt\n\n---\n\n$entryWithAHeadingAndAFence\n")

        box.historyPrevious()

        assertEquals(listOf("```kotlin"), collapsedRegionFirstLines(box))

        box.historyPrevious()

        assertEquals("first prompt", box.text)
        assertEmpty(regionFirstLines(box))

        box.historyNext()

        assertEquals(entryWithAHeadingAndAFence, box.text)
        assertEquals(listOf("```kotlin"), collapsedRegionFirstLines(box))
        assertEquals(box.text.length, box.editor.caretModel.offset)
    }

    fun testAFencedBlockEndingAtTheEndOfTheEntryCollapsesInBothDirections() {
        val entry = "intro\n\n```\ncode\n```"
        val box = boxOver("first prompt\n\n---\n\n$entry\n")

        box.historyPrevious()

        assertEquals(entry, box.text)
        assertEquals(listOf("```"), collapsedRegionFirstLines(box))
        assertEquals(0, box.editor.caretModel.offset)

        box.historyPrevious()
        box.historyNext()

        assertEquals(entry, box.text)
        assertEquals(listOf("```"), collapsedRegionFirstLines(box))
        assertEquals(box.text.length, box.editor.caretModel.offset)
    }

    fun testDownBackIntoTheDraftLeavesTheFencesTheUserTypedExpanded() {
        val draft = "my own prompt\n\n```kotlin\nval mine = 2\n```\ntail"
        val box = boxOver("$entryWithAHeadingAndAFence\n")
        box.text = draft

        box.historyPrevious()

        assertEquals(entryWithAHeadingAndAFence, box.text)
        assertEquals(listOf("```kotlin"), collapsedRegionFirstLines(box))

        box.historyNext()

        assertEquals(draft, box.text)
        assertEmpty(collapsedRegionFirstLines(box))

        buildTheFoldRegions(box)

        assertEquals(listOf("```kotlin"), regionFirstLines(box))
        assertEmpty(collapsedRegionFirstLines(box))
    }

    fun testTypingAfterAHistoryEntryLeavesTheFoldingAsTheUserLeftIt() {
        val box = boxOver("$entryWithAHeadingAndAFence\n")
        box.historyPrevious()
        expandEveryRegion(box)

        appendInTheBox(box, "\nand one more line")

        assertEmpty(collapsedRegionFirstLines(box))
    }

    fun testTypingAFencedBlockIntoTheBoxNeverFoldsIt() {
        val box = boxOver("first prompt\n")

        box.text = entryWithAHeadingAndAFence
        buildTheFoldRegions(box)

        assertEquals(listOf("# Heading", "```kotlin"), regionFirstLines(box))
        assertEmpty(collapsedRegionFirstLines(box))
    }
}
