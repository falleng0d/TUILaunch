package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.resume.AgentCliKind
import com.github.atm1020.tuilaunch.resume.AgentCommand
import com.github.atm1020.tuilaunch.resume.ShellWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCommandTest {

    @Test
    fun splitRespectsQuotesAndBackslashEscapes() {
        val tokens = ShellWords.split("""env FOO='a b' claude -c "x\"y" z\ w""")

        assertEquals(listOf("env", "FOO=a b", "claude", "-c", "x\"y", "z w"), tokens)
    }

    @Test
    fun splitCollapsesRunsOfWhitespace() {
        assertEquals(listOf("claude", "--resume", "id"), ShellWords.split("  claude   --resume\tid \n"))
        assertEquals(emptyList<String>(), ShellWords.split("   "))
    }

    @Test
    fun splitKeepsAnEmptyQuotedToken() {
        assertEquals(listOf("claude", ""), ShellWords.split("claude ''"))
    }

    @Test
    fun quoteLeavesSafeValuesAlone() {
        assertEquals("--session-id", ShellWords.quote("--session-id"))
        assertEquals("/tmp/a.b/c_d:e=f@g,h-i", ShellWords.quote("/tmp/a.b/c_d:e=f@g,h-i"))
    }

    @Test
    fun quoteWrapsValuesThatNeedIt() {
        assertEquals("''", ShellWords.quote(""))
        assertEquals("'a b'", ShellWords.quote("a b"))
        assertEquals("'{\"a\":1}'", ShellWords.quote("""{"a":1}"""))
        assertEquals("""'it'\''s'""", ShellWords.quote("it's"))
    }

    @Test
    fun quotedValuesSurviveARoundTripThroughSplit() {
        val values = listOf("a b", "it's", """{"a":1}""", "", "plain", """cat > "/tmp/x y.json"""")

        assertEquals(values, ShellWords.split(ShellWords.join(values)))
    }

    @Test
    fun leadingAssignmentsAreSkipped() {
        val parsed = AgentCommand.parse("FOO=1 BAR='a b' claude --model opus")

        assertEquals(AgentCliKind.CLAUDE, parsed.kind)
        assertFalse(parsed.wrappedByHeadroom)
    }

    @Test
    fun aLeadingEnvAndItsAssignmentsAreSkipped() {
        val parsed = AgentCommand.parse("FOO=1 env BAR=2 headroom wrap codex --no-serena")

        assertEquals(AgentCliKind.CODEX, parsed.kind)
        assertTrue(parsed.wrappedByHeadroom)
    }

    @Test
    fun headroomWrapIsDetectedByTheProgramBasename() {
        val parsed = AgentCommand.parse("/Users/x/.local/bin/headroom wrap opencode --no-serena")

        assertTrue(parsed.wrappedByHeadroom)
        assertEquals(AgentCliKind.OPENCODE, parsed.kind)
    }

    @Test
    fun aBareHeadroomWithoutWrapIsNotAWrapper() {
        val parsed = AgentCommand.parse("headroom doctor")

        assertFalse(parsed.wrappedByHeadroom)
        assertNull(parsed.kind)
    }

    @Test
    fun theCliKindIsTakenFromTheProgramBasename() {
        assertEquals(AgentCliKind.OMP, AgentCommand.parse("/opt/bin/omp").kind)
        assertEquals(AgentCliKind.CODEX, AgentCommand.parse("codex").kind)
    }

    @Test
    fun unrelatedProgramsHaveNoCliKind() {
        assertNull(AgentCommand.parse("lazygit").kind)
        assertNull(AgentCommand.parse("cursor-agent").kind)
        assertNull(AgentCommand.parse("headroom wrap cursor").kind)
        assertNull(AgentCommand.parse("").kind)
    }

    @Test
    fun aPlainCommandDoesNotSelectASession() {
        for (command in listOf("claude", "codex --model gpt-5", "opencode", "headroom wrap omp -p 8080")) {
            assertFalse(command, AgentCommand.parse(command).userSelectsASession)
            assertTrue(command, AgentCommand.parse(command).isManageable)
        }
    }

    @Test
    fun claudeSessionFlagsAreDetected() {
        for (flag in listOf("--resume", "-r", "--continue", "-c", "--session-id", "--fork-session")) {
            assertTrue(flag, AgentCommand.parse("claude $flag").userSelectsASession)
        }
        assertTrue(AgentCommand.parse("headroom wrap claude --no-serena -- --resume abc").userSelectsASession)
    }

    @Test
    fun codexSessionSubcommandsAndFlagsAreDetected() {
        for (argument in listOf("resume", "fork", "exec", "--continue")) {
            assertTrue(argument, AgentCommand.parse("codex $argument").userSelectsASession)
        }
        assertTrue(AgentCommand.parse("headroom wrap codex -- resume abc").userSelectsASession)
    }

    @Test
    fun openCodeSessionFlagsAreDetected() {
        for (flag in listOf("--session", "-s", "--continue", "-c", "--fork")) {
            assertTrue(flag, AgentCommand.parse("opencode $flag").userSelectsASession)
        }
    }

    @Test
    fun ompSessionFlagsAreDetected() {
        for (flag in listOf("--resume", "-r", "--session", "--continue", "-c", "--fork", "--session-dir")) {
            assertTrue(flag, AgentCommand.parse("omp $flag").userSelectsASession)
        }
    }

    @Test
    fun theFlagEqualsValueSpellingSelectsASession() {
        assertTrue(AgentCommand.parse("claude --session-id=abc").userSelectsASession)
        assertTrue(AgentCommand.parse("omp --resume=/tmp/a.jsonl").userSelectsASession)
        assertFalse(AgentCommand.parse("claude --model=--resume").userSelectsASession)
    }

    @Test
    fun aSessionFlagOfAnotherCliIsIgnored() {
        assertFalse(AgentCommand.parse("codex --session-id abc").userSelectsASession)
        assertFalse(AgentCommand.parse("claude --session abc").userSelectsASession)
    }

    @Test
    fun codexConfigOverridesDoNotCountAsASessionSelection() {
        assertFalse(AgentCommand.parse("codex -c model=gpt-5").userSelectsASession)
        assertFalse(AgentCommand.parse("headroom wrap codex --no-serena").userSelectsASession)
    }

    @Test
    fun withArgumentsAppendsToAnUnwrappedCommand() {
        val parsed = AgentCommand.parse("claude --model opus")

        assertEquals("claude --model opus --session-id u1", parsed.withArguments(listOf("--session-id", "u1")))
    }

    @Test
    fun withArgumentsInsertsAPassThroughForAWrappedCommand() {
        val parsed = AgentCommand.parse("headroom wrap opencode --no-serena")

        assertEquals(
            "headroom wrap opencode --no-serena -- --port 45123 --hostname 127.0.0.1",
            parsed.withArguments(listOf("--port", "45123", "--hostname", "127.0.0.1")),
        )
    }

    @Test
    fun withArgumentsDoesNotAddASecondPassThrough() {
        val parsed = AgentCommand.parse("headroom wrap claude --no-serena -- --model opus")

        assertEquals(
            "headroom wrap claude --no-serena -- --model opus --session-id u1",
            parsed.withArguments(listOf("--session-id", "u1")),
        )
    }

    @Test
    fun withArgumentsKeepsTheOriginalStringByteIdentical() {
        val command = """FOO='a b' headroom wrap  claude   --append-system-prompt "be brief" """
        val parsed = AgentCommand.parse(command)

        assertEquals(command + "-- --resume u1", parsed.withArguments(listOf("--resume", "u1")))
        assertEquals(command, parsed.withArguments(emptyList()))
    }

    @Test
    fun withArgumentsQuotesValuesThatNeedIt() {
        val parsed = AgentCommand.parse("codex")

        assertEquals(
            "codex -c 'hooks.SessionStart=[{a=1}]'",
            parsed.withArguments(listOf("-c", "hooks.SessionStart=[{a=1}]")),
        )
    }

    @Test
    fun aCommandThatAlreadySelectsASessionIsNotManageable() {
        val parsed = AgentCommand.parse("claude --continue")

        assertTrue(parsed.userSelectsASession)
        assertFalse(parsed.isManageable)
        assertEquals("claude --continue", parsed.withArguments(emptyList()))
    }

    @Test
    fun anUnmanagedProgramIsNotManageable() {
        assertFalse(AgentCommand.parse("lazygit").isManageable)
    }

    @Test
    fun aCommandThatChainsAnotherOneIsNotManageable() {
        for (command in listOf(
            "claude ; echo done",
            "codex | tee log",
            "opencode && echo done",
            "omp &",
            "claude || echo failed",
            "codex > /tmp/out",
            "omp >> /tmp/out",
            "claude < /tmp/in",
            "headroom wrap claude --no-serena ; echo done",
        )) {
            val parsed = AgentCommand.parse(command)

            assertTrue(command, parsed.chainsOtherCommands)
            assertFalse(command, parsed.isManageable)
        }
    }

    @Test
    fun aCommandSpanningMoreThanOneLineIsNotManageable() {
        val parsed = AgentCommand.parse("claude --model opus\necho done")

        assertTrue(parsed.chainsOtherCommands)
        assertFalse(parsed.isManageable)
    }

    @Test
    fun aPlainCommandChainsNothing() {
        for (command in listOf("claude --model opus", "headroom wrap codex --no-serena", "omp -p 8080")) {
            assertFalse(command, AgentCommand.parse(command).chainsOtherCommands)
        }
    }
}
