package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.resume.AgentCommand
import com.github.atm1020.tuilaunch.resume.CodexSessionStrategy
import com.github.atm1020.tuilaunch.resume.RememberedSession
import com.github.atm1020.tuilaunch.resume.TabIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class CodexSessionStrategyTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val tabUuid = "6a1f0c22-9b74-4a0e-8d3f-2b5c7e91a004"
    private val sessionId = "019a4f3c-7b21-7cd0-9e55-3f1b2a6d8c47"

    private val tab = TabIdentity(
        tabUuid = tabUuid,
        projectPath = "/Users/falleng0d/Projects/TUILaunch",
        projectHash = "TUILaunch.b2c3d4",
    )

    @Test
    fun theStateFileLivesUnderACodexSubdirectoryNamedAfterTheTab() {
        val stateDirectory = stateDirectory()
        val strategy = CodexSessionStrategy(stateDirectory)

        assertEquals(stateDirectory.resolve("codex").resolve("$tabUuid.json"), strategy.stateFile(tab))
    }

    @Test
    fun launchArgumentsInstallTheSessionStartHookAndBypassTheTrustPrompt() {
        val strategy = CodexSessionStrategy(stateDirectory())
        val stateFile = strategy.stateFile(tab)

        assertEquals(
            listOf("--dangerously-bypass-hook-trust", "-c", expectedToml(stateFile)),
            strategy.launchArguments(tab),
        )
    }

    @Test
    fun theHookCommandDoubleQuotesTheStateFilePath() {
        val strategy = CodexSessionStrategy(stateDirectory())
        val stateFile = strategy.stateFile(tab)
        val commandField = "command=\"cat > \\\"" + stateFile + "\\\"\""

        val toml = strategy.launchArguments(tab).last()

        assertTrue(toml, toml.contains(commandField))
        assertTrue(toml, toml.contains("async=true"))
        assertTrue(toml, toml.contains("timeout=5"))
    }

    @Test
    fun launchArgumentsCreateTheDirectoryTheHookWritesInto() {
        val strategy = CodexSessionStrategy(stateDirectory())

        strategy.launchArguments(tab)

        assertTrue(Files.isDirectory(strategy.stateFile(tab).parent))
    }

    @Test
    fun restoreResumesTheRecordedSessionAndKeepsTheHook() {
        val strategy = CodexSessionStrategy(stateDirectory())
        writeState(strategy.stateFile(tab), """{"session_id":"$sessionId","source":"startup"}""")

        assertEquals(
            listOf("resume", sessionId, "--dangerously-bypass-hook-trust", "-c", expectedToml(strategy.stateFile(tab))),
            strategy.restoreArguments(tab, RememberedSession()),
        )
    }

    @Test
    fun theWrappedRestoreCommandMatchesTheVerifiedPassThroughForm() {
        val strategy = CodexSessionStrategy(stateDirectory())
        val stateFile = strategy.stateFile(tab)
        writeState(stateFile, """{"session_id":"$sessionId"}""")
        val command = "headroom wrap codex --no-serena --no-tokensave --dangerously-bypass-approvals-and-sandbox"

        assertEquals(
            "$command -- resume $sessionId --dangerously-bypass-hook-trust -c '${expectedToml(stateFile)}'",
            AgentCommand.parse(command).withArguments(strategy.restoreArguments(tab, RememberedSession())),
        )
    }

    @Test
    fun restoreFallsBackToTheLaunchArgumentsWithoutAUsableStateFile() {
        val strategy = CodexSessionStrategy(stateDirectory())
        val launch = strategy.launchArguments(tab)

        assertEquals(launch, strategy.restoreArguments(tab, RememberedSession()))

        writeState(strategy.stateFile(tab), """{"cwd":"/Users/falleng0d/Projects/TUILaunch"}""")
        assertEquals(launch, strategy.restoreArguments(tab, RememberedSession()))

        writeState(strategy.stateFile(tab), "{not json")
        assertEquals(launch, strategy.restoreArguments(tab, RememberedSession()))
    }

    @Test
    fun readSessionIdAcceptsOnlyANonBlankStringField() {
        val strategy = CodexSessionStrategy(stateDirectory())
        val stateFile = strategy.stateFile(tab)

        assertNull(strategy.readSessionId(stateFile))

        writeState(stateFile, """{"session_id":"$sessionId","source":"resume"}""")
        assertEquals(sessionId, strategy.readSessionId(stateFile))

        writeState(stateFile, """{"session_id":42}""")
        assertNull(strategy.readSessionId(stateFile))

        writeState(stateFile, """{"session_id":"  "}""")
        assertNull(strategy.readSessionId(stateFile))

        writeState(stateFile, """["$sessionId"]""")
        assertNull(strategy.readSessionId(stateFile))

        writeState(stateFile, "")
        assertNull(strategy.readSessionId(stateFile))
    }

    @Test
    fun cleanUpDeletesTheStateFileAndToleratesAMissingOne() {
        val strategy = CodexSessionStrategy(stateDirectory())
        writeState(strategy.stateFile(tab), """{"session_id":"$sessionId"}""")

        strategy.cleanUp(tab)
        strategy.cleanUp(tab)

        assertFalse(Files.exists(strategy.stateFile(tab)))
    }

    @Test
    fun aStateFilePathThatWouldBreakTheHookIsRefused() {
        for (name in listOf("say\"hi\"", "back\\slash")) {
            val strategy = CodexSessionStrategy(temporaryFolder.root.toPath().resolve(name))

            assertFalse(name, strategy.canManage(tab))
            assertEquals(emptyList<String>(), strategy.launchArguments(tab))
            assertEquals(emptyList<String>(), strategy.restoreArguments(tab, RememberedSession(sessionId)))
            assertEquals("codex", AgentCommand.parse("codex").withArguments(strategy.launchArguments(tab)))
        }
    }

    @Test
    fun aPlainStateFilePathIsManageable() {
        assertTrue(CodexSessionStrategy(stateDirectory()).canManage(tab))
    }

    private fun expectedToml(stateFile: Path): String =
        """hooks.SessionStart=[{hooks=[{type="command",command="cat > \"$stateFile\"",async=true,timeout=5}]}]"""

    private fun stateDirectory(): Path = temporaryFolder.root.toPath().resolve("agent-sessions")

    private fun writeState(stateFile: Path, content: String) {
        Files.createDirectories(stateFile.parent)
        Files.writeString(stateFile, content)
    }
}
