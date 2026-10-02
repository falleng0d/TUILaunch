package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.resume.AgentCliKind
import com.github.atm1020.tuilaunch.terminal.TerminalRow
import com.github.atm1020.tuilaunch.terminal.TerminalScreen
import com.github.atm1020.tuilaunch.terminal.cleanTerminalScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private const val CAPTURE_COLUMNS = 100
private val FIXTURES = File("src/test/testData/terminal-screens")

class TerminalScreenCleanerTest {

    private fun kindOf(fixture: String): AgentCliKind =
        AgentCliKind.valueOf(fixture.substringBefore('-').uppercase())

    private fun cleaned(fixture: String): String {
        val kind = kindOf(fixture)
        val rows = File(FIXTURES, fixture).readLines().map { TerminalRow(it) }
        return cleanTerminalScreen(TerminalScreen(rows, CAPTURE_COLUMNS, kind != AgentCliKind.OMP), kind)
    }

    private fun linesOf(fixture: String): List<String> = cleaned(fixture).lines()

    private fun screenOf(vararg rows: String, columns: Int = 40) =
        TerminalScreen(rows.map { TerminalRow(it) }, columns, usesAlternateScreen = true)

    private fun assertHasLine(fixture: String, line: String) {
        assertTrue("$fixture has no line \"$line\"", line in linesOf(fixture))
    }

    private fun assertLacks(fixture: String, vararg texts: String) {
        val text = cleaned(fixture)
        texts.forEach { assertFalse("$fixture still shows \"$it\"", text.contains(it)) }
    }

    @Test
    fun `every fixture is left without drawing characters, icons and stacked blank lines`() {
        val fixtures = FIXTURES.list()!!.sorted()

        assertEquals(8, fixtures.size)
        fixtures.forEach { fixture ->
            val text = cleaned(fixture)
            val drawing = text.codePoints().toArray().filter {
                it in 0x2500..0x259F || it in 0x2800..0x28FF || it in 0xE000..0xF8FF || it >= 0xF0000
            }
            assertEquals("$fixture keeps drawing characters", emptyList<Int>(), drawing)
            assertFalse("$fixture stacks blank lines", text.contains("\n\n\n"))
            assertEquals("$fixture is not trimmed", text.trim(), text)
            assertTrue("$fixture keeps trailing spaces", text.lines().none { it.endsWith(' ') })
        }
    }

    @Test
    fun `claude loses its input box, its status rows and its turn ends`() {
        assertLacks("claude-1.txt", "Opus 5.5", "auto mode on", "Crunched for", "❯", "✻")
        assertLacks("claude-2.txt", "show me what's inside caps", "Cogitated for", "⏺")
    }

    @Test
    fun `codex loses its input row, its status rows, its turn ends and its tool hints`() {
        assertLacks("codex-1.txt", "Ask Codex", "GPT-6.1-Sol high", "for shortcuts", "Worked for")
        assertLacks("codex-2.txt", "Ask Codex", "Worked for", "Show details", "›")
    }

    @Test
    fun `opencode loses its input box, its path row and its turn ends`() {
        assertLacks("opencode-1.txt", "ctrl+p commands", "Build · GPT-6.1 Sol Fast", "/private/tmp")
        assertLacks("opencode-2.txt", "ctrl+p commands", "Build · GPT-6.1 Sol Fast")
    }

    @Test
    fun `omp loses its input row, its status row, its timing rows and its startup notices`() {
        assertLacks(
            "omp-1.txt",
            "8.3%/272K",
            "2026-10-02 16:47:10",
            "Welcome back!",
            "Tip:",
            "What's New",
            "Update Available",
            "omp update",
        )
        assertLacks("omp-2.txt", "8.8%/272K", "16:49:27", "16:49:40")
    }

    @Test
    fun `a paragraph the TUI wrapped at the right edge becomes one line`() {
        assertHasLine(
            "claude-2.txt",
            "The folder holds only a .git directory and a caps directory, both owned by someone and last " +
                "changed on Oct 2.",
        )
        assertHasLine(
            "omp-1.txt",
            "Without using any tools, explain in two long paragraphs how a terminal emulator renders text, then " +
                "give a bulleted list of three facts and a short Kotlin code block of five lines.",
        )
        assertHasLine(
            "opencode-2.txt",
            "The folder contains a .git directory and a caps directory, with no files listed at the top level.",
        )
        assertTrue(
            cleaned("opencode-1.txt").contains(
                "so Unicode handling requires more than assigning one cell to every code point. The renderer",
            ),
        )
        assertTrue(cleaned("opencode-1.txt").contains("target changed regions, though resizing"))
        assertTrue(cleaned("codex-1.txt").contains("The grid still constrains placement"))
    }

    @Test
    fun `bullet items stay apart while each joins its own continuation`() {
        assertHasLine(
            "claude-1.txt",
            "- The VT100 escape sequences from DEC in 1978 still form the basis of most control codes that " +
                "emulators support today.",
        )
        assertHasLine(
            "claude-1.txt",
            "- The TERM environment variable tells programs which terminfo entry to use, and that entry " +
                "describes which features the emulator supports.",
        )
        assertHasLine(
            "codex-2.txt",
            "• Programs normally send text and control sequences, while the terminal emulator chooses and " +
                "renders the glyphs.",
        )
        assertHasLine(
            "opencode-2.txt",
            "- A terminal’s character cells are layout units, so character count and display width can differ.",
        )
    }

    @Test
    fun `code rows and tool output rows stay on their own lines`() {
        assertHasLine("claude-2.txt", "fun put(row: Int, col: Int, text: String) =")
        assertHasLine("claude-2.txt", "    text.forEachIndexed { i, c -> grid[row][col + i] = Cell(c) }")
        assertHasLine("codex-1.txt", "fun main() {")
        assertHasLine("codex-1.txt", "    val esc = \"\\u001B\"")
        assertHasLine("omp-1.txt", "```kotlin")
        assertHasLine("omp-1.txt", "    val text = \"terminal\"")
        assertHasLine("opencode-2.txt", "drwxr-xr-x@   4 someone  wheel   128 Oct  2 16:46 .")
        assertHasLine("opencode-2.txt", "drwxr-xr-x@  12 someone  wheel   384 Oct  2 16:48 caps")
    }

    @Test
    fun `a prompt summary cut off with an ellipsis is not joined to the reply below it`() {
        val lines = linesOf("codex-2.txt")

        assertEquals(
            "Without using any tools, explain in two long paragraphs how a terminal emulator renders text, th…",
            lines.first(),
        )
        assertTrue(lines[1].startsWith("Frequently used glyphs can be cached"))
    }

    @Test
    fun `the closing fence of a code block that scrolled away is dropped`() {
        val lines = linesOf("omp-2.txt")

        assertEquals(
            "Run ls -la in this folder with your shell tool, then tell me in one sentence what you saw.",
            lines.first(),
        )
        assertFalse(lines.contains("```"))
    }

    @Test
    fun `the newest claude turn reads as plain paragraphs`() {
        assertEquals(
            listOf(
                "Run ls -la in this folder with your shell tool, then tell me in one sentence what you saw.",
                "Listed 1 directory",
                "The folder holds only a .git directory and a caps directory, both owned by someone and last " +
                    "changed on Oct 2.",
            ),
            cleaned("claude-2.txt").split("\n\n").takeLast(3),
        )
    }

    @Test
    fun `rows the terminal itself wrapped are joined without a space`() {
        val screen = TerminalScreen(
            listOf(TerminalRow("a long wo", isWrapped = true), TerminalRow("rd ends here")),
            columns = 9,
            usesAlternateScreen = true,
        )

        assertEquals("a long word ends here", cleanTerminalScreen(screen, null))
    }

    @Test
    fun `a short row is never joined to the next one`() {
        val screen = screenOf("first line", "second line")

        assertEquals("first line\nsecond line", cleanTerminalScreen(screen, null))
    }

    @Test
    fun `the input box is found above the empty rows a short screen leaves at the bottom`() {
        val rule = "─".repeat(40)
        val rows = listOf("⏺ Done.", "", rule, "❯ next prompt", rule, "  status") + List(20) { "" }

        assertEquals("Done.", cleanTerminalScreen(screenOf(*rows.toTypedArray()), AgentCliKind.CLAUDE))
    }

    @Test
    fun `an unknown program keeps its bottom rows`() {
        val screen = screenOf("output", "", "› a prompt of some shell")

        assertEquals("output\n\na prompt of some shell", cleanTerminalScreen(screen, null))
    }

    @Test
    fun `a code row made only of punctuation is kept`() {
        val screen = screenOf("fun main() {", "}")

        assertEquals("fun main() {\n}", cleanTerminalScreen(screen, null))
    }
}
