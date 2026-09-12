package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.resume.AgentCliKind
import com.github.atm1020.tuilaunch.resume.AgentCommand
import com.github.atm1020.tuilaunch.resume.HookShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HookShellTest {

    @Test
    fun `a posix shell appends the hook input with cat`() {
        assertEquals(
            """{ cat; echo; } >> \"${'$'}TUILAUNCH_CODEX_STATE\"""",
            HookShell.POSIX.appendStdinTo("TUILAUNCH_CODEX_STATE"),
        )
    }

    @Test
    fun `powershell appends the hook input without a quote of its own`() {
        val command = HookShell.POWERSHELL.appendStdinTo("TUILAUNCH_CODEX_STATE")

        assertEquals(
            "[IO.File]::AppendAllText(\$env:TUILAUNCH_CODEX_STATE, " +
                "[Console]::In.ReadToEnd() + [Environment]::NewLine)",
            command,
        )
        assertEquals("the command has to survive a TOML string", -1, command.indexOf('"'))
    }

    @Test
    fun `an agent wrapper takes the session arguments of the cli it is named after`() {
        assertEquals(AgentCliKind.CLAUDE, AgentCommand.parse("claude-agent").kind)
        assertEquals(AgentCliKind.CODEX, AgentCommand.parse("codex-agent.cmd").kind)
        assertEquals(AgentCliKind.OPENCODE, AgentCommand.parse("'C:\\tools\\bin\\opencode-agent.cmd'").kind)
    }

    @Test
    fun `a wrapper appends the session arguments where the wrapper forwards them`() {
        assertEquals(
            "claude-agent --resume 6f1d0a2e",
            AgentCommand.parse("claude-agent").withArguments(listOf("--resume", "6f1d0a2e")),
        )
    }

    @Test
    fun `anything else named after a cli is left alone`() {
        assertNull(AgentCommand.parse("claude-trace").kind)
        assertNull(AgentCommand.parse("codexify").kind)
    }
}
