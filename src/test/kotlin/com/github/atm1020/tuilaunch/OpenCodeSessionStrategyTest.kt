package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.resume.AgentCliKind
import com.github.atm1020.tuilaunch.resume.AgentCommand
import com.github.atm1020.tuilaunch.resume.AgentSessionEnvironment
import com.github.atm1020.tuilaunch.resume.AgentSessionStrategies
import com.github.atm1020.tuilaunch.resume.OpenCodeSessionStrategy
import com.github.atm1020.tuilaunch.resume.TabIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

private const val REPORTED_SESSION_ID = "ses_8a3f1c0d9b2e4a6c8d0f2b4a6c"
private const val TAB_SESSION_ID = "ses_8b2c7d403e194c589f6a1d0e5b7c2a93"

class OpenCodeSessionStrategyTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val tabUuid = "8b2c7d40-3e19-4c58-9f6a-1d0e5b7c2a93"

    private val tab = TabIdentity(
        tabUuid = tabUuid,
        projectPath = "/Users/falleng0d/Projects/TUILaunch",
        projectHash = "TUILaunch.b2c3d4",
    )

    @Test
    fun theStateFileSitsInADirectoryOfItsOwn() {
        assertEquals(
            stateDirectory().resolve("opencode").resolve("$tabUuid.json"),
            newStrategy().stateFile(tab),
        )
    }

    @Test
    fun aFreshTabOpensASessionNamedAfterTheTab() {
        assertEquals(listOf("--session", TAB_SESSION_ID), newStrategy().launchArguments(tab))
    }

    @Test
    fun theSessionIdKeepsOnlyTheLettersAndDigitsOfTheTabId() {
        val strategy = newStrategy()

        assertEquals("ses_abc123", strategy.sessionIdOf(tab.copy(tabUuid = "ABC-123")))
        assertEquals("ses_abc123", strategy.sessionIdOf(tab.copy(tabUuid = "a b'c;1\"23")))
    }

    @Test
    fun aRestoredTabOpensTheSessionNamedAfterIt() {
        val strategy = newStrategy()

        assertNull(strategy.readSessionId(strategy.stateFile(tab)))
        assertEquals(strategy.launchArguments(tab), strategy.restoreArguments(tab))
        assertEquals(TAB_SESSION_ID, strategy.sessionToRestore(tab))
    }

    @Test
    fun aRestoredTabOpensTheSessionAnEarlierTrackerReported() {
        val strategy = newStrategy()
        writeState(strategy, """{"sessionId":"$REPORTED_SESSION_ID","updatedAt":1757328131000}""" + "\n")

        assertEquals(listOf("--session", REPORTED_SESSION_ID), strategy.restoreArguments(tab))
        assertEquals(REPORTED_SESSION_ID, strategy.sessionToRestore(tab))
        assertEquals(listOf("--session", TAB_SESSION_ID), strategy.launchArguments(tab))
    }

    @Test
    fun anIdThatIsNotASessionIdIsIgnored() {
        val strategy = newStrategy()
        val rejected = listOf(
            "dummy",
            "ses_8a3f1c0d9b2e",
            "ses_8a3f1c0d9b2e4a6c8d0f2b4a6c7",
            "ses_8a3f-c0d9b2e4a6c8d0f2b4a6c",
            "8a3f1c0d9b2e4a6c8d0f2b4a6c",
        )
        for (reported in rejected) {
            writeState(strategy, """{"sessionId":"$reported"}""")

            assertNull(reported, strategy.readSessionId(strategy.stateFile(tab)))
            assertEquals(reported, listOf("--session", TAB_SESSION_ID), strategy.restoreArguments(tab))
        }
    }

    @Test
    fun aStateFileThatIsNotAUsableRecordIsIgnored() {
        val strategy = newStrategy()
        val rejected = listOf(
            "",
            "   ",
            """{"sessionId":""",
            """["$REPORTED_SESSION_ID"]""",
            "{}",
            """{"sessionId":""}""",
            """{"sessionId":42}""",
        )
        for (content in rejected) {
            writeState(strategy, content)

            assertNull(content, strategy.readSessionId(strategy.stateFile(tab)))
            assertEquals(content, listOf("--session", TAB_SESSION_ID), strategy.restoreArguments(tab))
        }
    }

    @Test
    fun opencodeGetsNoEnvironmentOfItsOwn() {
        val strategy = newStrategy()
        writeState(strategy, """{"sessionId":"$REPORTED_SESSION_ID"}""")

        assertEquals(emptyMap<String, String>(), strategy.launchEnvironment(tab))
        assertEquals(emptyMap<String, String>(), strategy.restoreEnvironment(tab))
    }

    @Test
    fun cleaningUpATabDeletesItsStateFile() {
        val strategy = newStrategy()
        writeState(strategy, """{"sessionId":"$REPORTED_SESSION_ID"}""")

        runBlocking { strategy.cleanUp(tab) }

        assertFalse(Files.exists(strategy.stateFile(tab)))
        assertEquals(listOf("--session", TAB_SESSION_ID), strategy.restoreArguments(tab))
    }

    @Test
    fun cleaningUpATabThatHasNoStateFileIsHarmless() {
        val strategy = newStrategy()

        runBlocking { strategy.cleanUp(tab) }

        assertFalse(Files.exists(strategy.stateFile(tab)))
    }

    @Test
    fun theWrappedCommandCarriesTheSessionThroughHeadroom() {
        assertEquals(
            "headroom wrap opencode --no-serena -- --session $TAB_SESSION_ID",
            AgentCommand.parse("headroom wrap opencode --no-serena")
                .withArguments(newStrategy().launchArguments(tab)),
        )
    }

    @Test
    fun theFactoryBuildsAnOpenCodeStrategyOverTheStateDirectory() {
        val environment = AgentSessionEnvironment(
            homeDirectory = temporaryFolder.root.toPath().resolve("home"),
            stateDirectory = stateDirectory(),
            bundledDirectory = temporaryFolder.root.toPath().resolve("integrations"),
        )

        val strategy = AgentSessionStrategies.forKind(AgentCliKind.OPENCODE, environment)

        assertEquals(listOf("--session", TAB_SESSION_ID), strategy.launchArguments(tab))
        assertEquals(
            stateDirectory().resolve("opencode").resolve("$tabUuid.json"),
            (strategy as OpenCodeSessionStrategy).stateFile(tab),
        )
    }

    private fun newStrategy(): OpenCodeSessionStrategy = OpenCodeSessionStrategy(stateDirectory())

    private fun stateDirectory(): Path = temporaryFolder.root.toPath().resolve("state")

    private fun writeState(strategy: OpenCodeSessionStrategy, content: String) {
        val file = strategy.stateFile(tab)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }
}
