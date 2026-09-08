package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.resume.AgentCliKind
import com.github.atm1020.tuilaunch.resume.AgentCommand
import com.github.atm1020.tuilaunch.resume.AgentSessionEnvironment
import com.github.atm1020.tuilaunch.resume.AgentSessionStrategies
import com.github.atm1020.tuilaunch.resume.ClaudeProjectPath
import com.github.atm1020.tuilaunch.resume.ClaudeSessionStrategy
import com.github.atm1020.tuilaunch.resume.ShellWords
import com.github.atm1020.tuilaunch.resume.TabIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class ClaudeSessionStrategyTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val tabUuid = "0f9d3b1e-1c3a-4f2b-9a1d-7c5e6b8a0d41"
    private val reportedSessionId = "5c2a7f88-40b6-4d19-b3e7-9a1c0d6f4e22"

    private val tab = TabIdentity(
        tabUuid = tabUuid,
        projectPath = "/Users/falleng0d/Projects/TUILaunch",
        projectHash = "TUILaunch.b2c3d4",
    )

    @Test
    fun escapeReplacesEveryNonAlphanumericCharacterWithADash() {
        assertEquals(
            "-Users-falleng0d-Projects-TUILaunch",
            ClaudeProjectPath.escape("/Users/falleng0d/Projects/TUILaunch"),
        )
        assertEquals("-Users-falleng0d--ai", ClaudeProjectPath.escape("/Users/falleng0d/.ai"))
    }

    @Test
    fun escapeTruncatesLongPathsAndAppendsABase36Hash() {
        val segments = (0..19).joinToString("/") { "segment%02d".format(it) }
        val longPath = "/Users/falleng0d/Projects/$segments/deep"

        val escaped = ClaudeProjectPath.escape(longPath)

        assertEquals(230, longPath.length)
        assertEquals(
            "-Users-falleng0d-Projects-segment00-segment01-segment02-segment03-segment04-segment05-segment06-" +
                "segment07-segment08-segment09-segment10-segment11-segment12-segment13-segment14-segment15-" +
                "segment16-segm-4qtgj1",
            escaped,
        )
        assertEquals(207, escaped.length)
    }

    @Test
    fun theStateFileLivesUnderAClaudeSubdirectoryNamedAfterTheTab() {
        val strategy = newStrategy()

        assertEquals(stateDirectory().resolve("claude").resolve("$tabUuid.json"), strategy.stateFile(tab))
    }

    @Test
    fun launchInstallsTheSessionStartHookAndPinsTheTabUuidAsTheSessionId() {
        val strategy = newStrategy()

        assertEquals(
            listOf("--settings", expectedSettings(strategy.stateFile(tab)), "--session-id", tabUuid),
            strategy.launchArguments(tab),
        )
    }

    @Test
    fun theLaunchCommandQuotesTheHookJsonForTheShell() {
        val strategy = newStrategy()
        val settings = expectedSettings(strategy.stateFile(tab))

        assertEquals(
            "claude --settings '$settings' --session-id $tabUuid",
            AgentCommand.parse("claude").withArguments(strategy.launchArguments(tab)),
        )
    }

    @Test
    fun theWrappedLaunchCommandPassesTheHookJsonThroughHeadroom() {
        val strategy = newStrategy()
        val settings = expectedSettings(strategy.stateFile(tab))

        assertEquals(
            "headroom wrap claude --no-serena --no-tokensave -- --settings '$settings' --session-id $tabUuid",
            AgentCommand.parse("headroom wrap claude --no-serena --no-tokensave")
                .withArguments(strategy.launchArguments(tab)),
        )
    }

    @Test
    fun theHookJsonSurvivesTheShellWordsRoundTrip() {
        val strategy = newStrategy()
        val arguments = strategy.launchArguments(tab)

        val command = AgentCommand.parse("headroom wrap claude").withArguments(arguments)

        assertEquals(listOf("headroom", "wrap", "claude", "--") + arguments, ShellWords.split(command))
    }

    @Test
    fun restoreResumesTheSessionTheHookReported() {
        val strategy = newStrategy()
        val transcript = temporaryFolder.newFile("reported.jsonl").toPath()
        writeState(strategy.stateFile(tab), reportedState(transcript.toString()))

        assertEquals(
            listOf("--settings", expectedSettings(strategy.stateFile(tab)), "--resume", reportedSessionId),
            strategy.restoreArguments(tab),
        )
    }

    @Test
    fun aReportedSessionWithoutATranscriptPathIsStillResumed() {
        val strategy = newStrategy()
        writeState(strategy.stateFile(tab), """{"session_id":"$reportedSessionId","source":"resume"}""")

        assertEquals(
            listOf("--settings", expectedSettings(strategy.stateFile(tab)), "--resume", reportedSessionId),
            strategy.restoreArguments(tab),
        )
    }

    @Test
    fun aReportedSessionWhoseTranscriptIsGoneFallsBackToTheTabsOwnTranscript() {
        val strategy = newStrategy()
        val missing = temporaryFolder.root.toPath().resolve("gone.jsonl")
        writeState(strategy.stateFile(tab), reportedState(missing.toString()))
        writeTranscript(strategy.transcriptFile(tab))

        assertEquals(
            listOf("--settings", expectedSettings(strategy.stateFile(tab)), "--resume", tabUuid),
            strategy.restoreArguments(tab),
        )
    }

    @Test
    fun aTornStateFileFallsBackToTheTabsOwnTranscript() {
        val strategy = newStrategy()
        writeTranscript(strategy.transcriptFile(tab))

        for (content in listOf("", "{not json", """{"session_id":"  "}""", """["$reportedSessionId"]""")) {
            writeState(strategy.stateFile(tab), content)

            assertEquals(
                content,
                listOf("--settings", expectedSettings(strategy.stateFile(tab)), "--resume", tabUuid),
                strategy.restoreArguments(tab),
            )
        }
    }

    @Test
    fun restoreResumesTheTabUuidWhenOnlyTheTranscriptExists() {
        val strategy = newStrategy()
        writeTranscript(strategy.transcriptFile(tab))

        assertEquals(
            listOf("--settings", expectedSettings(strategy.stateFile(tab)), "--resume", tabUuid),
            strategy.restoreArguments(tab),
        )
    }

    @Test
    fun restoreFallsBackToTheLaunchArgumentsWithoutAnyState() {
        val strategy = newStrategy()

        assertEquals(strategy.launchArguments(tab), strategy.restoreArguments(tab))
    }

    @Test
    fun aTranscriptOfAnotherProjectDoesNotCount() {
        val strategy = newStrategy()
        writeTranscript(strategy.transcriptFile(tab.copy(projectPath = "/Users/falleng0d/Projects/Other")))

        assertEquals(strategy.launchArguments(tab), strategy.restoreArguments(tab))
    }

    @Test
    fun aResumeIsNeverCombinedWithASessionId() {
        val strategy = newStrategy()
        val transcript = temporaryFolder.newFile("combined.jsonl").toPath()

        val everyForm = mutableListOf(strategy.launchArguments(tab))
        everyForm.add(strategy.restoreArguments(tab))
        writeTranscript(strategy.transcriptFile(tab))
        everyForm.add(strategy.restoreArguments(tab))
        writeState(strategy.stateFile(tab), reportedState(transcript.toString()))
        everyForm.add(strategy.restoreArguments(tab))

        for (arguments in everyForm) {
            assertFalse(
                arguments.toString(),
                arguments.contains("--resume") && arguments.contains("--session-id"),
            )
        }
    }

    @Test
    fun aUserOwnedSettingsFlagLeavesTheHookOutOfTheLaunch() {
        val strategy = newStrategy(hookAllowed = false)

        assertEquals(listOf("--session-id", tabUuid), strategy.launchArguments(tab))
    }

    @Test
    fun aUserOwnedSettingsFlagFallsBackToTheSessionOfTheTabItself() {
        val strategy = newStrategy(hookAllowed = false)
        val transcript = temporaryFolder.newFile("owned.jsonl").toPath()
        writeState(strategy.stateFile(tab), reportedState(transcript.toString()))

        assertEquals(listOf("--session-id", tabUuid), strategy.restoreArguments(tab))

        writeTranscript(strategy.transcriptFile(tab))
        assertEquals(listOf("--resume", tabUuid), strategy.restoreArguments(tab))
    }

    @Test
    fun aUserOwnedSettingsFlagKeepsTheStateDirectoryOutOfTheWay() {
        val strategy = newStrategy(hookAllowed = false)

        strategy.prepareLaunch(tab)
        runBlocking { strategy.cleanUp(tab) }

        assertFalse(Files.exists(stateDirectory()))
    }

    @Test
    fun aWindowsShellGetsNoInlineSettingsDocument() {
        val environment = AgentSessionEnvironment(
            homeDirectory = temporaryFolder.root.toPath().resolve("home"),
            stateDirectory = stateDirectory(),
            bundledDirectory = temporaryFolder.root.toPath().resolve("integrations"),
            claudeConfigDir = claudeHome().toString(),
            theShellIsPosix = false,
        )
        val strategy = AgentSessionStrategies.forKind(AgentCliKind.CLAUDE, environment)

        assertEquals(listOf("--session-id", tabUuid), strategy.launchArguments(tab))
    }

    @Test
    fun prepareLaunchCreatesTheDirectoryTheHookWritesInto() {
        val strategy = newStrategy()

        strategy.prepareLaunch(tab)

        assertTrue(Files.isDirectory(strategy.stateFile(tab).parent))
    }

    @Test
    fun buildingTheArgumentsWritesNothing() {
        val strategy = newStrategy()

        strategy.launchArguments(tab)
        strategy.restoreArguments(tab)

        assertFalse(Files.exists(stateDirectory()))
    }

    @Test
    fun cleanUpDeletesTheStateFileAndToleratesAMissingOne() {
        val strategy = newStrategy()
        writeState(strategy.stateFile(tab), """{"session_id":"$reportedSessionId"}""")

        runBlocking {
            strategy.cleanUp(tab)
            strategy.cleanUp(tab)
        }

        assertFalse(Files.exists(strategy.stateFile(tab)))
    }

    @Test
    fun readSessionIdAcceptsOnlyANonBlankStringField() {
        val strategy = newStrategy()
        val stateFile = strategy.stateFile(tab)

        assertNull(strategy.readSessionId(stateFile))

        writeState(stateFile, """{"session_id":"$reportedSessionId","source":"clear"}""")
        assertEquals(reportedSessionId, strategy.readSessionId(stateFile))

        writeState(stateFile, """{"session_id":42}""")
        assertNull(strategy.readSessionId(stateFile))
    }

    @Test
    fun theFactoryResolvesTheClaudeHomeAndTheStateDirectoryFromTheEnvironment() {
        val environment = AgentSessionEnvironment(
            homeDirectory = temporaryFolder.newFolder("home").toPath(),
            stateDirectory = temporaryFolder.newFolder("state").toPath(),
            bundledDirectory = temporaryFolder.newFolder("integrations").toPath(),
            claudeConfigDir = temporaryFolder.newFolder("claude-config").toString(),
        )
        val strategy = AgentSessionStrategies.forKind(AgentCliKind.CLAUDE, environment) as ClaudeSessionStrategy

        assertEquals(environment.claudeHome, Path.of(environment.claudeConfigDir!!))
        assertEquals(
            environment.stateDirectory.resolve("claude").resolve("$tabUuid.json"),
            strategy.stateFile(tab),
        )
        assertEquals(
            listOf("--settings", expectedSettings(strategy.stateFile(tab)), "--session-id", tabUuid),
            strategy.launchArguments(tab),
        )
    }

    @Test
    fun theClaudeHomeDefaultsToADotClaudeDirectoryUnderTheHome() {
        val home = temporaryFolder.newFolder("plain-home").toPath()
        val environment = AgentSessionEnvironment(
            homeDirectory = home,
            stateDirectory = temporaryFolder.newFolder("plain-state").toPath(),
            bundledDirectory = temporaryFolder.newFolder("plain-integrations").toPath(),
            claudeConfigDir = "  ",
        )

        assertEquals(home.resolve(".claude"), environment.claudeHome)
    }

    private fun newStrategy(hookAllowed: Boolean = true): ClaudeSessionStrategy =
        ClaudeSessionStrategy(claudeHome(), stateDirectory(), hookAllowed)

    private fun expectedSettings(stateFile: Path): String = claudeHookSettings(stateFile)

    private fun reportedState(transcriptPath: String): String =
        """{"session_id":"$reportedSessionId","transcript_path":"$transcriptPath","source":"resume"}"""

    private fun claudeHome(): Path = temporaryFolder.root.toPath().resolve("claude-home")

    private fun stateDirectory(): Path = temporaryFolder.root.toPath().resolve("agent-sessions")

    private fun writeTranscript(file: Path) {
        writeState(file, "{}\n")
    }

    private fun writeState(file: Path, content: String) {
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }
}
