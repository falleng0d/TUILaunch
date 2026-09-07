package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.resume.AgentCliKind
import com.github.atm1020.tuilaunch.resume.AgentCommand
import com.github.atm1020.tuilaunch.resume.AgentSessionEnvironment
import com.github.atm1020.tuilaunch.resume.AgentSessionStrategies
import com.github.atm1020.tuilaunch.resume.ClaudeProjectPath
import com.github.atm1020.tuilaunch.resume.ClaudeSessionStrategy
import com.github.atm1020.tuilaunch.resume.RememberedSession
import com.github.atm1020.tuilaunch.resume.TabIdentity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class ClaudeSessionStrategyTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val tabUuid = "0f9d3b1e-1c3a-4f2b-9a1d-7c5e6b8a0d41"

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
    fun launchArgumentsPinTheTabUuidAsTheSessionId() {
        val strategy = ClaudeSessionStrategy(claudeHome())

        assertEquals(listOf("--session-id", tabUuid), strategy.launchArguments(tab))
    }

    @Test
    fun restoreResumesTheTabUuidWhenTheTranscriptExists() {
        val strategy = ClaudeSessionStrategy(claudeHome())
        writeTranscript(strategy.transcriptFile(tab))

        assertEquals(listOf("--resume", tabUuid), strategy.restoreArguments(tab, RememberedSession(tabUuid)))
    }

    @Test
    fun restoreFallsBackToTheLaunchArgumentsWithoutATranscript() {
        val strategy = ClaudeSessionStrategy(claudeHome())

        assertEquals(listOf("--session-id", tabUuid), strategy.restoreArguments(tab, RememberedSession(tabUuid)))
    }

    @Test
    fun aTranscriptOfAnotherProjectDoesNotCount() {
        val strategy = ClaudeSessionStrategy(claudeHome())
        writeTranscript(strategy.transcriptFile(tab.copy(projectPath = "/Users/falleng0d/Projects/Other")))

        assertEquals(listOf("--session-id", tabUuid), strategy.restoreArguments(tab, RememberedSession(tabUuid)))
    }

    @Test
    fun theWrappedRestoreCommandPassesTheResumeThroughHeadroom() {
        val strategy = ClaudeSessionStrategy(claudeHome())
        writeTranscript(strategy.transcriptFile(tab))
        val parsed = AgentCommand.parse("headroom wrap claude --no-serena --no-tokensave")

        assertEquals(
            "headroom wrap claude --no-serena --no-tokensave -- --resume $tabUuid",
            parsed.withArguments(strategy.restoreArguments(tab, RememberedSession(tabUuid))),
        )
    }

    @Test
    fun theFactoryResolvesTheClaudeHomeFromTheEnvironment() {
        val environment = AgentSessionEnvironment(
            homeDirectory = temporaryFolder.newFolder("home").toPath(),
            stateDirectory = temporaryFolder.newFolder("state").toPath(),
            claudeConfigDir = temporaryFolder.newFolder("claude-config").toString(),
        )
        val strategy = AgentSessionStrategies.forKind(AgentCliKind.CLAUDE, environment)

        assertEquals(environment.claudeHome, Path.of(environment.claudeConfigDir!!))
        assertEquals(listOf("--session-id", tabUuid), strategy.launchArguments(tab))
    }

    @Test
    fun theClaudeHomeDefaultsToADotClaudeDirectoryUnderTheHome() {
        val home = temporaryFolder.newFolder("plain-home").toPath()
        val environment = AgentSessionEnvironment(
            homeDirectory = home,
            stateDirectory = temporaryFolder.newFolder("plain-state").toPath(),
            claudeConfigDir = "  ",
        )

        assertEquals(home.resolve(".claude"), environment.claudeHome)
    }

    private fun claudeHome(): Path = temporaryFolder.root.toPath().resolve("claude-home")

    private fun writeTranscript(file: Path) {
        Files.createDirectories(file.parent)
        Files.writeString(file, "{}\n")
    }
}
