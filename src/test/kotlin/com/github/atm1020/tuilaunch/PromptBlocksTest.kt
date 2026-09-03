package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.prompt.canChangeBlockStructure
import com.github.atm1020.tuilaunch.prompt.isLastPromptBlock
import com.github.atm1020.tuilaunch.prompt.parsePromptBlocks
import com.github.atm1020.tuilaunch.prompt.promptBlockContainsLine
import com.github.atm1020.tuilaunch.prompt.promptBlockTextAt
import com.github.atm1020.tuilaunch.prompt.promptBlockTextContainingLine
import com.github.atm1020.tuilaunch.prompt.promptMarkerLines
import com.github.atm1020.tuilaunch.prompt.promptSeparatorEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBlocksTest {

    private val exampleFile = """
        ---

        Help me think a little bit about this pipeline work. ...

        ---

        Can we do the same refactoring for the pubmed assets? Does it make sense over there?

        ---

        Evaluate how this could work, make me also a diagram similar to
        `openalex_asset_graph` in overview. Put in new plan rag/02-source_streaming.md

        see also @plans/rag/initial/OVERVIEW.md

        ---
    """.trimIndent() + "\n"

    @Test
    fun theExampleFileYieldsThreeBlocksMarkedOnTheirOpeningLines() {
        val blocks = parsePromptBlocks(exampleFile)

        assertEquals(3, blocks.size)
        assertEquals(listOf(2, 6, 10), blocks.map { it.markerLine })
        assertEquals("Help me think a little bit about this pipeline work. ...", blocks[0].text)
        assertEquals(
            "Can we do the same refactoring for the pubmed assets? Does it make sense over there?",
            blocks[1].text,
        )
    }

    @Test
    fun theLastExampleBlockKeepsBothParagraphsAndTheBlankLineBetweenThem() {
        val lastBlock = parsePromptBlocks(exampleFile).last()

        assertEquals(
            "Evaluate how this could work, make me also a diagram similar to\n" +
                "`openalex_asset_graph` in overview. Put in new plan rag/02-source_streaming.md\n" +
                "\n" +
                "see also @plans/rag/initial/OVERVIEW.md",
            lastBlock.text,
        )
    }

    @Test
    fun adjacentDelimitersYieldNoBlock() {
        assertEquals(emptyList<Int>(), parsePromptBlocks("---\n---\n").map { it.markerLine })
    }

    @Test
    fun aBodyOfOnlyBlankLinesYieldsNoBlock() {
        assertEquals(emptyList<Int>(), parsePromptBlocks("---\n\n   \n\t\n---\n").map { it.markerLine })
    }

    @Test
    fun theUnclosedPromptAfterTheFinalDelimiterIsItsOwnBlock() {
        val blocks = parsePromptBlocks("---\nfinished prompt\n---\nstill being typed\n")

        assertEquals(listOf(1, 3), blocks.map { it.markerLine })
        assertEquals(listOf("finished prompt", "still being typed"), blocks.map { it.text })
    }

    @Test
    fun theTextBeforeTheFirstDelimiterIsItsOwnBlock() {
        val blocks = parsePromptBlocks("preamble\n---\nfinished prompt\n---\n")

        assertEquals(listOf(0, 2), blocks.map { it.markerLine })
        assertEquals(listOf("preamble", "finished prompt"), blocks.map { it.text })
    }

    @Test
    fun aFileEndingInADelimiterHasNoTrailingBlock() {
        val blocks = parsePromptBlocks("---\nonly prompt\n---\n")

        assertEquals(1, blocks.size)
        assertEquals(1, blocks.single().markerLine)
        assertEquals("only prompt", blocks.single().text)
    }

    @Test
    fun interiorBlankLinesAndIndentationArePreserved() {
        val blocks = parsePromptBlocks("---\nfirst\n\n    indented second\n---\n")

        assertEquals("first\n\n    indented second", blocks.single().text)
    }

    @Test
    fun aDelimiterInsideABacktickFenceDoesNotSplitTheBlock() {
        val text = "---\nbefore\n```\n---\n```\nafter\n---\n"

        val blocks = parsePromptBlocks(text)

        assertEquals(1, blocks.size)
        assertEquals(1, blocks.single().markerLine)
        assertEquals("before\n```\n---\n```\nafter", blocks.single().text)
    }

    @Test
    fun aDelimiterInsideATildeFenceDoesNotSplitTheBlock() {
        val blocks = parsePromptBlocks("---\nbefore\n~~~\n---\n~~~\nafter\n---\n")

        assertEquals(1, blocks.size)
        assertEquals("before\n~~~\n---\n~~~\nafter", blocks.single().text)
    }

    @Test
    fun anUnclosedFenceDoesNotSuppressTheDelimitersBelowIt() {
        val blocks = parsePromptBlocks("---\nfirst\n```\n---\nsecond\n---\n")

        assertEquals(listOf(1, 4), blocks.map { it.markerLine })
        assertEquals("first\n```", blocks[0].text)
        assertEquals("second", blocks[1].text)
    }

    @Test
    fun anInnerFenceDoesNotCloseALongerOuterFence() {
        val blocks = parsePromptBlocks("---\nbefore\n````md\n```bash\n---\n```\n````\nafter\n---\n")

        assertEquals(1, blocks.size)
        assertEquals(1, blocks.single().markerLine)
        assertEquals("before\n````md\n```bash\n---\n```\n````\nafter", blocks.single().text)
    }

    @Test
    fun anInlineCodeSpanIsNotACodeFence() {
        val blocks = parsePromptBlocks("---\n`openalex_asset_graph` in overview\n---\nsecond\n---\n")

        assertEquals(listOf(1, 3), blocks.map { it.markerLine })
    }

    @Test
    fun aDelimiterPaddedWithWhitespaceStillDelimits() {
        val blocks = parsePromptBlocks("  ---  \nfinished prompt\n\t---\t\n")

        assertEquals(1, blocks.size)
        assertEquals(1, blocks.single().markerLine)
        assertEquals("finished prompt", blocks.single().text)
    }

    @Test
    fun otherHorizontalRuleSpellingsAreNotDelimiters() {
        val blocks = parsePromptBlocks("---\na\n----\nb\n***\nc\n- - -\nd\n---\n")

        assertEquals(1, blocks.size)
        assertEquals("a\n----\nb\n***\nc\n- - -\nd", blocks.single().text)
    }

    @Test
    fun carriageReturnsNeverReachThePayload() {
        val blocks = parsePromptBlocks("---\r\nfirst\r\nsecond\r\n---\r\n")

        assertEquals(1, blocks.size)
        assertEquals(1, blocks.single().markerLine)
        assertFalse(blocks.single().text.contains('\r'))
        assertEquals("first\nsecond", blocks.single().text)
    }

    @Test
    fun aFileWithNoDelimiterIsASinglePromptMarkedOnItsFirstLine() {
        val blocks = parsePromptBlocks("Implement abc\n\nthe way it works is this")

        assertEquals(1, blocks.size)
        assertEquals(0, blocks.single().markerLine)
        assertEquals("Implement abc\n\nthe way it works is this", blocks.single().text)
    }

    @Test
    fun anEmptyFileYieldsNoBlocks() {
        assertTrue(parsePromptBlocks("").isEmpty())
        assertTrue(parsePromptBlocks("\n   \n\t\n").isEmpty())
    }

    private val corpus = listOf(
        exampleFile,
        "",
        "\n   \n\t\n",
        "---\n---\n",
        "---\n\n   \n\t\n---\n",
        "---\nfinished prompt\n---\nstill being typed\n",
        "preamble\n---\nfinished prompt\n---\n",
        "---\nonly prompt\n---\n",
        "---\nfirst\n\n    indented second\n---\n",
        "---\nbefore\n```\n---\n```\nafter\n---\n",
        "---\nbefore\n~~~\n---\n~~~\nafter\n---\n",
        "---\nfirst\n```\n---\nsecond\n---\n",
        "---\nbefore\n````md\n```bash\n---\n```\n````\nafter\n---\n",
        "---\n`openalex_asset_graph` in overview\n---\nsecond\n---\n",
        "  ---  \nfinished prompt\n\t---\t\n",
        "---\na\n----\nb\n***\nc\n- - -\nd\n---\n",
        "---\r\nfirst\r\nsecond\r\n---\r\n",
        "Implement abc\n\nthe way it works is this",
        "---\r first\r---\rsecond\r",
        "```kotlin\n---\n```kotlin\n---\n```kotlin\n---\n",
        "no trailing newline",
    )

    @Test
    fun markerLineOnlyParsingAgreesWithTheFullParseOnEveryCase() {
        corpus.forEach { text ->
            assertEquals(text, parsePromptBlocks(text).map { it.markerLine }, promptMarkerLines(text))
        }
    }

    @Test
    fun singleBlockTextLookupAgreesWithTheFullParseOnEveryCase() {
        corpus.forEach { text ->
            parsePromptBlocks(text).forEach { block ->
                assertEquals(text, block.text, promptBlockTextAt(text, block.markerLine))
            }
        }
    }

    @Test
    fun aBlockTextLookupOnALineWithNoBlockYieldsNothing() {
        assertNull(promptBlockTextAt("---\nonly prompt\n---\n", 0))
        assertNull(promptBlockTextAt("---\nonly prompt\n---\n", 99))
    }

    @Test
    fun manyOpenersThatNeverCloseLeaveEveryDelimiterInPlace() {
        val text = (0 until 2_000).joinToString("") { "```kotlin\n---\n" }

        val blocks = parsePromptBlocks(text)

        assertEquals(2_000, blocks.size)
        assertTrue(blocks.all { it.text == "```kotlin" })
        assertEquals(listOf(0, 2, 4), blocks.take(3).map { it.markerLine })
    }

    @Test
    fun delimitersAfterManyClosedFencesAreStillFound() {
        val text = (0 until 2_000).joinToString("") { "```\ncode\n```\n---\n" }

        val blocks = parsePromptBlocks(text)

        assertEquals(2_000, blocks.size)
        assertTrue(blocks.all { it.text == "```\ncode\n```" })
    }

    @Test
    fun aLoneCarriageReturnAlsoEndsALine() {
        val blocks = parsePromptBlocks("---\rfirst\rsecond\r---\r")

        assertEquals(1, blocks.size)
        assertEquals(1, blocks.single().markerLine)
        assertEquals("first\nsecond", blocks.single().text)
    }

    @Test
    fun onlyBlankDelimiterAndFenceLinesCanChangeBlockStructure() {
        assertTrue(canChangeBlockStructure(""))
        assertTrue(canChangeBlockStructure("   \t "))
        assertTrue(canChangeBlockStructure("  ---  "))
        assertTrue(canChangeBlockStructure("```"))
        assertTrue(canChangeBlockStructure("```kotlin"))
        assertTrue(canChangeBlockStructure("~~~~"))

        assertFalse(canChangeBlockStructure("plain prose"))
        assertFalse(canChangeBlockStructure("a well-known -- dash heavy line"))
        assertFalse(canChangeBlockStructure("`inline` code and ``double`` spans"))
        assertFalse(canChangeBlockStructure("----"))
        assertFalse(canChangeBlockStructure("- - -"))
    }

    private fun withAppendedSeparator(text: String): String {
        val edit = promptSeparatorEdit(text) ?: return text
        return text.substring(0, edit.from) + edit.text + text.substring(edit.to)
    }

    @Test
    fun aPromptWithNoTrailingNewlineGetsABlankLineADividerAndAnEmptySlot() {
        assertEquals("Fix the bug\n\n---\n\n", withAppendedSeparator("Fix the bug"))
    }

    @Test
    fun aSingleTrailingNewlineIsNotCountedTowardsTheBlankLine() {
        assertEquals("Fix the bug\n\n---\n\n", withAppendedSeparator("Fix the bug\n"))
    }

    @Test
    fun trailingBlankLinesAreCollapsedIntoTheOneBlankLineBeforeTheDivider() {
        assertEquals("Fix the bug\n\n---\n\n", withAppendedSeparator("Fix the bug\n\n\n   \n"))
        assertEquals("Fix the bug\n\n---\n\n", withAppendedSeparator("Fix the bug\n \n\t\n"))
    }

    @Test
    fun aFileAlreadyEndingInADividerOnlyGetsTheBlankLineAfterIt() {
        assertEquals("first prompt\n---\n\n", withAppendedSeparator("first prompt\n---\n"))
    }

    @Test
    fun appendingTwiceLeavesASingleDivider() {
        val once = withAppendedSeparator("---\n\nfirst prompt\n")

        assertEquals("---\n\nfirst prompt\n\n---\n\n", once)
        assertEquals(once, withAppendedSeparator(once))
    }

    @Test
    fun aFileAlreadyEndingInAnEmptySlotNeedsNoEdit() {
        assertNull(promptSeparatorEdit("first prompt\n\n---\n\n"))
        assertNull(promptSeparatorEdit("---\n\nfirst prompt\n\n---\n\n"))
    }

    @Test
    fun aFileWithNoPromptAtAllNeedsNoEdit() {
        assertNull(promptSeparatorEdit(""))
        assertNull(promptSeparatorEdit("\n\n"))
        assertNull(promptSeparatorEdit("   \n\t\n   "))
    }

    @Test
    fun aDividerAfterAnUnclosedFenceIsStillADivider() {
        val fenceLeftOpen = "first prompt\n```\n---\n"

        assertEquals(listOf(0), promptMarkerLines(fenceLeftOpen))
        assertEquals("first prompt\n```\n---\n\n", withAppendedSeparator(fenceLeftOpen))
    }

    @Test
    fun aClosedFenceAtTheEndOfTheFileGetsAWholeNewSlot() {
        val closedFence = "first prompt\n```\n---\n```\n"

        assertEquals("first prompt\n```\n---\n```\n\n---\n\n", withAppendedSeparator(closedFence))
    }

    @Test
    fun theCaretOffsetLandsAtTheEndOfTheAppendedFile() {
        listOf("Fix the bug", "Fix the bug\n", "Fix the bug\n\n\n", "first prompt\n---\n").forEach { text ->
            val edit = promptSeparatorEdit(text)!!

            assertEquals(withAppendedSeparator(text).length, edit.caretOffset)
        }
    }

    @Test
    fun appendingASeparatorMarksNoNewPrompt() {
        listOf("---\n\nfirst prompt\n", exampleFile, "Fix the bug").forEach { text ->
            assertEquals(promptMarkerLines(text), promptMarkerLines(withAppendedSeparator(text)))
        }
    }

    @Test
    fun everyLineOfABlockResolvesToThatWholeBlock() {
        val twoBlocks = "---\n\nfirst prompt\nmore of it\nlast line\n\n---\n\nsecond prompt\n"
        val firstBlock = "first prompt\nmore of it\nlast line"

        assertEquals(firstBlock, promptBlockTextContainingLine(twoBlocks, 2))
        assertEquals(firstBlock, promptBlockTextContainingLine(twoBlocks, 3))
        assertEquals(firstBlock, promptBlockTextContainingLine(twoBlocks, 4))
        assertEquals("second prompt", promptBlockTextContainingLine(twoBlocks, 8))
    }

    @Test
    fun aDividerLineBelongsToNoBlock() {
        val twoBlocks = "---\n\nfirst prompt\n\n---\n\nsecond prompt\n"

        assertNull(promptBlockTextContainingLine(twoBlocks, 0))
        assertNull(promptBlockTextContainingLine(twoBlocks, 4))
    }

    @Test
    fun aBlankLineBetweenTwoBlocksBelongsToNoBlock() {
        val twoBlocks = "first prompt\n\n---\n\nsecond prompt\n"

        assertEquals("first prompt", promptBlockTextContainingLine(twoBlocks, 0))
        assertNull(promptBlockTextContainingLine(twoBlocks, 1))
        assertNull(promptBlockTextContainingLine(twoBlocks, 3))
        assertEquals("second prompt", promptBlockTextContainingLine(twoBlocks, 4))
    }

    @Test
    fun theEmptySlotAtTheEndOfTheFileBelongsToNoBlock() {
        val appended = withAppendedSeparator("---\n\nfirst prompt\n")

        assertEquals("---\n\nfirst prompt\n\n---\n\n", appended)
        assertNull(promptBlockTextContainingLine(appended, 5))
        assertNull(promptBlockTextContainingLine(appended, 6))
    }

    @Test
    fun aFileWithNoDividerAtAllIsOneBlockOnEveryLine() {
        val singleBlock = "Fix the bug\nand add a test\n"

        assertEquals("Fix the bug\nand add a test", promptBlockTextContainingLine(singleBlock, 0))
        assertEquals("Fix the bug\nand add a test", promptBlockTextContainingLine(singleBlock, 1))
    }

    @Test
    fun aDividerInsideAFenceResolvesToTheWholeEnclosingBlock() {
        val fencedDivider = "---\n\nfix this:\n```\n---\n```\n\n---\n\nsecond\n"
        val fencedBlock = "fix this:\n```\n---\n```"

        assertEquals(fencedBlock, promptBlockTextContainingLine(fencedDivider, 2))
        assertEquals(fencedBlock, promptBlockTextContainingLine(fencedDivider, 4))
        assertEquals(fencedBlock, promptBlockTextContainingLine(fencedDivider, 5))
        assertEquals("second", promptBlockTextContainingLine(fencedDivider, 9))
    }

    @Test
    fun anEmptyFileHasNoBlockOnAnyLine() {
        assertNull(promptBlockTextContainingLine("", 0))
        assertNull(promptBlockTextContainingLine("\n\n", 1))
    }

    @Test
    fun everyBlockAGutterIconMarksIsAlsoFoundFromItsFirstLine() {
        promptMarkerLines(exampleFile).forEach { markerLine ->
            assertEquals(
                promptBlockTextAt(exampleFile, markerLine),
                promptBlockTextContainingLine(exampleFile, markerLine),
            )
        }
    }

    @Test
    fun theCheapBlockCheckAgreesWithTheBlockTextLookupOnEveryLine() {
        listOf(
            exampleFile,
            "---\n\nfirst prompt\n\n---\n\nsecond prompt\nspanning two lines\n\n---\n\n",
            "Fix the bug\n",
            "",
        ).forEach { text ->
            everyLineOf(text).forEach { line ->
                assertEquals(
                    promptBlockTextContainingLine(text, line) != null,
                    promptBlockContainsLine(text, line),
                )
            }
        }
    }

    @Test
    fun onlyTheLastBlockOfAMultiBlockFileEndsTheFile() {
        val twoBlocks = "---\n\nfirst prompt\n\n---\n\nsecond prompt\n"

        assertFalse(isLastPromptBlock(twoBlocks, 2))
        assertTrue(isLastPromptBlock(twoBlocks, 6))
    }

    @Test
    fun everyLineOfTheLastBlockEndsTheFileAndNoLineOfAnEarlierOneDoes() {
        val twoBlocks = "---\n\nfirst prompt\nmore of it\n\n---\n\nsecond prompt\nspanning two lines\n"

        assertFalse(isLastPromptBlock(twoBlocks, 2))
        assertFalse(isLastPromptBlock(twoBlocks, 3))
        assertTrue(isLastPromptBlock(twoBlocks, 7))
        assertTrue(isLastPromptBlock(twoBlocks, 8))
    }

    @Test
    fun theOnlyBlockOfASingleBlockFileEndsTheFile() {
        val singleBlock = "Fix the bug\nand add a test\n"

        assertTrue(isLastPromptBlock(singleBlock, 0))
        assertTrue(isLastPromptBlock(singleBlock, 1))
    }

    @Test
    fun noLineOutsideAnyBlockEndsTheFile() {
        val appended = withAppendedSeparator("---\n\nfirst prompt\n")

        assertEquals("---\n\nfirst prompt\n\n---\n\n", appended)
        assertTrue(isLastPromptBlock(appended, 2))
        assertFalse(isLastPromptBlock(appended, 4))
        assertFalse(isLastPromptBlock(appended, 6))
        assertFalse(isLastPromptBlock("", 0))
        assertFalse(isLastPromptBlock("\n\n", 1))
    }

    @Test
    fun theLastBlockIsTheOnlyOneAnAppendedSeparatorFollows() {
        val twoBlocks = "---\n\nfirst prompt\n\n---\n\nsecond prompt\n"
        val appended = withAppendedSeparator(twoBlocks)

        assertEquals("---\n\nfirst prompt\n\n---\n\nsecond prompt\n\n---\n\n", appended)
        assertFalse(isLastPromptBlock(appended, 2))
        assertTrue(isLastPromptBlock(appended, 6))
    }

    private fun everyLineOf(text: String): IntRange = 0..text.count { it == '\n' }
}
