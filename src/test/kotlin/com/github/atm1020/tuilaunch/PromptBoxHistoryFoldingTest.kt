package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.prompt.PromptBox
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilder
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.lang.folding.LanguageFolding
import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase

private const val FOLD_PLACEHOLDER = "..."
private const val HEADING_MARKER = "#"
private const val MINIMUM_FENCE_RUN = 3

private class HeadingAndFenceFoldingBuilder : FoldingBuilder, DumbAware {

    override fun buildFoldRegions(node: ASTNode, document: Document): Array<FoldingDescriptor> {
        val regions = mutableListOf<FoldingDescriptor>()
        var line = 0
        while (line < document.lineCount) {
            val fenceClosingLine = closingFenceLineFor(document, line)
            if (fenceClosingLine != null) {
                regions.add(descriptorOver(node, document, line, fenceClosingLine))
                line = fenceClosingLine + 1
                continue
            }
            if (lineTextOf(document, line).trim().startsWith(HEADING_MARKER)) {
                val headingEnd = headingEndLineFrom(document, line)
                regions.add(descriptorOver(node, document, line, headingEnd))
                line = headingEnd + 1
                continue
            }
            line++
        }
        return regions.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String = FOLD_PLACEHOLDER

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false

    private fun descriptorOver(node: ASTNode, document: Document, firstLine: Int, lastLine: Int) =
        FoldingDescriptor(
            node,
            TextRange(document.getLineStartOffset(firstLine), document.getLineEndOffset(lastLine)),
        )

    private fun closingFenceLineFor(document: Document, openingLine: Int): Int? {
        val marker = fenceRunOf(lineTextOf(document, openingLine)) ?: return null
        var line = openingLine + 1
        while (line < document.lineCount) {
            val closing = fenceRunOf(lineTextOf(document, line))
            if (closing != null && closing.first == marker.first && closing.second >= marker.second) return line
            line++
        }
        return null
    }

    private fun fenceRunOf(line: String): Pair<Char, Int>? {
        val content = line.trim()
        val marker = content.firstOrNull() ?: return null
        if (marker != '`' && marker != '~') return null
        val run = content.takeWhile { it == marker }.length
        return if (run >= MINIMUM_FENCE_RUN) marker to run else null
    }

    private fun headingEndLineFrom(document: Document, headingLine: Int): Int {
        var line = headingLine
        while (line + 1 < document.lineCount) {
            val next = lineTextOf(document, line + 1)
            if (next.isBlank() || fenceRunOf(next) != null) break
            line++
        }
        return line
    }
}

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

    override fun setUp() {
        super.setUp()
        LanguageFolding.INSTANCE.addExplicitExtension(
            PlainTextLanguage.INSTANCE,
            HeadingAndFenceFoldingBuilder(),
            testRootDisposable,
        )
    }

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

        assertEmpty(collapsedRegionFirstLines(box))
    }

    fun testTheBoxShowsTheFoldingOutlineWithoutTheRestOfTheGutter() {
        val settings = boxOver("first prompt\n").editor.settings

        assertTrue(settings.isFoldingOutlineShown)
        assertFalse(settings.isLineNumbersShown)
        assertFalse(settings.isLineMarkerAreaShown)
        assertFalse(settings.areGutterIconsShown())
    }
}
