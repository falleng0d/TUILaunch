package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.prompt.canChangeBlockStructure
import com.github.atm1020.tuilaunch.prompt.parsePromptBlocks
import com.github.atm1020.tuilaunch.prompt.promptBlockTextAt
import com.github.atm1020.tuilaunch.prompt.promptMarkerLines
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
}
