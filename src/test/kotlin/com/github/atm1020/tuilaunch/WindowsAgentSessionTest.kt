package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.resume.AgentCliKind
import com.github.atm1020.tuilaunch.resume.AgentCommand
import com.github.atm1020.tuilaunch.resume.AgentSessionEnvironment
import com.github.atm1020.tuilaunch.resume.AgentSessionStrategies
import com.github.atm1020.tuilaunch.resume.HookShell
import com.github.atm1020.tuilaunch.resume.TabIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The shell on Windows is PowerShell or cmd, neither of which takes a `VAR=value command` prefix, so the
 * environment reaches the agent through the terminal process instead. These cases pin what each agent gets.
 */
class WindowsAgentSessionTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val tab = TabIdentity(
        tabUuid = "6f1d0a2e-0000-4000-8000-000000000001",
        projectPath = "C:\\Projects\\app",
        projectHash = "abc123",
    )

    private fun windowsEnvironment() = AgentSessionEnvironment(
        homeDirectory = folder.newFolder("home").toPath(),
        stateDirectory = folder.newFolder("state").toPath(),
        bundledDirectory = folder.newFolder("bundled").toPath(),
        theShellIsPosix = false,
        hookShell = HookShell.POWERSHELL,
    )

    private fun restoreOn(kind: AgentCliKind): Pair<List<String>, Map<String, String>> {
        val strategy = AgentSessionStrategies.forKind(kind, windowsEnvironment())
        strategy.prepareLaunch(tab)
        return strategy.restoreArguments(tab) to strategy.restoreEnvironment(tab)
    }

    @Test
    fun `claude still restores because it drives the session id itself`() {
        val (arguments, environment) = restoreOn(AgentCliKind.CLAUDE)
        assertEquals(listOf("--session-id", tab.tabUuid), arguments)
        assertTrue(environment.isEmpty())
    }

    @Test
    fun `codex asks for a powershell hook and names its state file in the environment`() {
        val (arguments, environment) = restoreOn(AgentCliKind.CODEX)
        assertEquals(codexHookArguments(HookShell.POWERSHELL), arguments)
        assertEquals(setOf("TUILAUNCH_CODEX_STATE"), environment.keys)
    }

    @Test
    fun `opencode names its tracker and state file in the environment`() {
        val (_, environment) = restoreOn(AgentCliKind.OPENCODE)
        assertEquals(setOf("OPENCODE_TUI_CONFIG", "TUILAUNCH_OPENCODE_STATE"), environment.keys)
    }

    @Test
    fun `a headroom wrapped claude is still recognised as claude`() {
        val parsed = AgentCommand.parse("headroom wrap claude")
        assertEquals(AgentCliKind.CLAUDE, parsed.kind)
        assertTrue(parsed.isManageable)
        assertEquals("headroom wrap claude -- --resume x", parsed.withArguments(listOf("--resume", "x")))
    }

    @Test
    fun `a windows shim is recognised through its directory and its extension`() {
        assertEquals(AgentCliKind.CODEX, AgentCommand.parse("codex.cmd").kind)
        assertEquals(AgentCliKind.CODEX, AgentCommand.parse("'C:\\tools\\bin\\codex.exe'").kind)
        assertEquals(AgentCliKind.OPENCODE, AgentCommand.parse("\"C:\\tools\\bin\\opencode.cmd\"").kind)
    }

    /**
     * The tokenizer follows POSIX rules, so an unquoted Windows path loses its separators and the agent
     * behind it goes unrecognised. Quote the path in the launcher configuration.
     */
    @Test
    fun `an unquoted windows path hides the agent`() {
        assertEquals(null, AgentCommand.parse("C:\\tools\\bin\\codex").kind)
    }
}
