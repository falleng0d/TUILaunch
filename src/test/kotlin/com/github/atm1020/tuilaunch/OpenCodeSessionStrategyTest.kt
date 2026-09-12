package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.resume.AgentCliKind
import com.github.atm1020.tuilaunch.resume.AgentCommand
import com.github.atm1020.tuilaunch.resume.AgentSessionEnvironment
import com.github.atm1020.tuilaunch.resume.AgentSessionStrategies
import com.github.atm1020.tuilaunch.resume.OpenCodeSessionStrategy
import com.github.atm1020.tuilaunch.resume.ShellWords
import com.github.atm1020.tuilaunch.resume.TabIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

private const val REPORTED_SESSION_ID = "ses_8a3f1c0d9b2e4a6c8d0f2b4a6c"

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
    fun theBundledFilesAndTheStateFileSitInDirectoriesOfTheirOwn() {
        val strategy = newStrategy()

        assertEquals(bundledDirectory().resolve("opencode").resolve("tui.json"), strategy.tuiConfigFile())
        assertEquals(
            bundledDirectory().resolve("opencode").resolve("tuilaunch-session-tracker.js"),
            strategy.sessionTrackerFile(),
        )
        assertEquals(
            stateDirectory().resolve("opencode").resolve("$tabUuid.json"),
            strategy.stateFile(tab),
        )
    }

    @Test
    fun prepareLaunchWritesBothBundledFilesAndTheStateDirectory() {
        val strategy = newStrategy()

        strategy.prepareLaunch(tab)

        assertArrayEquals(
            bundledResource(OpenCodeSessionStrategy.TUI_CONFIG_RESOURCE),
            Files.readAllBytes(strategy.tuiConfigFile()),
        )
        assertArrayEquals(
            bundledResource(OpenCodeSessionStrategy.SESSION_TRACKER_RESOURCE),
            Files.readAllBytes(strategy.sessionTrackerFile()),
        )
        assertTrue(Files.isDirectory(strategy.stateFile(tab).parent))
    }

    @Test
    fun prepareLaunchLeavesBundledFilesThatAreAlreadyCurrentAlone() {
        val strategy = newStrategy()
        val untouched = FileTime.fromMillis(1_000_000)
        listOf(
            strategy.tuiConfigFile() to OpenCodeSessionStrategy.TUI_CONFIG_RESOURCE,
            strategy.sessionTrackerFile() to OpenCodeSessionStrategy.SESSION_TRACKER_RESOURCE,
        ).forEach { (file, resource) ->
            Files.createDirectories(file.parent)
            Files.write(file, bundledResource(resource))
            Files.setLastModifiedTime(file, untouched)
        }

        strategy.prepareLaunch(tab)

        assertEquals(untouched, Files.getLastModifiedTime(strategy.tuiConfigFile()))
        assertEquals(untouched, Files.getLastModifiedTime(strategy.sessionTrackerFile()))
    }

    @Test
    fun prepareLaunchRewritesABundledFileThatDiffers() {
        val strategy = newStrategy()
        val tracker = strategy.sessionTrackerFile()
        Files.createDirectories(tracker.parent)
        Files.writeString(tracker, "export default {};\n")

        strategy.prepareLaunch(tab)

        assertArrayEquals(
            bundledResource(OpenCodeSessionStrategy.SESSION_TRACKER_RESOURCE),
            Files.readAllBytes(tracker),
        )
        assertFalse(Files.exists(tracker.resolveSibling("${tracker.fileName}.tmp")))
    }

    @Test
    fun theTuiConfigLoadsTheTrackerNextToIt() {
        assertEquals(
            """{"plugin":["./tuilaunch-session-tracker.js"]}""",
            String(bundledResource(OpenCodeSessionStrategy.TUI_CONFIG_RESOURCE)).trim(),
        )
    }

    @Test
    fun theTrackerFollowsTheDisplayedRootSessionAndWritesThroughARename() {
        val tracker = String(bundledResource(OpenCodeSessionStrategy.SESSION_TRACKER_RESOURCE))

        for (expected in listOf(
            """id: "tuilaunch-session-tracker"""",
            "process.env.TUILAUNCH_OPENCODE_STATE",
            "api.route.current",
            """route.name !== "session"""",
            """sessionId.startsWith("ses_")""",
            "api.state.session.get(sessionId)?.parentID",
            "const POLL_MS = 500;",
            "setInterval(record, POLL_MS)",
            "timer.unref",
            "api.lifecycle.onDispose",
            "renameSync(staging, target)",
        )) {
            assertTrue(expected, tracker.contains(expected))
        }
    }

    @Test
    fun bundledFilesThatCouldNotBeWrittenLeaveTheCommandAlone() {
        val strategy = newStrategy()

        assertEquals(emptyMap<String, String>(), strategy.launchEnvironment(tab))
        assertEquals(emptyMap<String, String>(), strategy.restoreEnvironment(tab))
    }


    @Test
    fun theEnvironmentPointsOpenCodeAtTheBundledConfigAndTheTabStateFile() {
        val strategy = newStrategy()
        strategy.prepareLaunch(tab)

        val environment = strategy.launchEnvironment(tab)

        assertEquals(
            listOf("OPENCODE_TUI_CONFIG", "TUILAUNCH_OPENCODE_STATE"),
            environment.keys.toList(),
        )
        assertEquals(
            listOf(strategy.tuiConfigFile().toString(), strategy.stateFile(tab).toString()),
            environment.values.toList(),
        )
        assertEquals(environment, strategy.restoreEnvironment(tab))
    }

    @Test
    fun launchAddsNoArgumentsAtAll() {
        assertEquals(emptyList<String>(), newStrategy().launchArguments(tab))
    }

    @Test
    fun restoreOpensTheSessionTheTrackerReported() {
        val strategy = newStrategy()
        writeState(strategy, """{"sessionId":"$REPORTED_SESSION_ID","updatedAt":1757328131000}""" + "\n")

        assertEquals(
            listOf("--session", REPORTED_SESSION_ID),
            strategy.restoreArguments(tab),
        )
        assertEquals(REPORTED_SESSION_ID, strategy.readSessionId(strategy.stateFile(tab)))
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
            assertEquals(reported, emptyList<String>(), strategy.restoreArguments(tab))
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
            assertEquals(content, emptyList<String>(), strategy.restoreArguments(tab))
        }
    }

    @Test
    fun aTabWithoutAStateFileRestoresLikeAFreshOne() {
        val strategy = newStrategy()
        strategy.prepareLaunch(tab)

        assertNull(strategy.readSessionId(strategy.stateFile(tab)))
        assertEquals(strategy.launchArguments(tab), strategy.restoreArguments(tab))
        assertEquals(strategy.launchEnvironment(tab), strategy.restoreEnvironment(tab))
    }

    @Test
    fun aTabWithoutAReportGetsNoSessionArgument() {
        val strategy = newStrategy()

        assertEquals(
            emptyList<String>(),
            strategy.restoreArguments(tab),
        )
    }

    @Test
    fun closingATabDeletesItsStateFile() {
        val strategy = newStrategy()
        writeState(strategy, """{"sessionId":"$REPORTED_SESSION_ID"}""")

        runBlocking { strategy.cleanUp(tab) }

        assertFalse(Files.exists(strategy.stateFile(tab)))
    }

    @Test
    fun closingATabThatWroteNothingIsHarmless() {
        val strategy = newStrategy()

        runBlocking { strategy.cleanUp(tab) }

        assertFalse(Files.exists(strategy.stateFile(tab)))
    }

    @Test
    fun theWrappedRestoreCommandCarriesTheVariablesAndTheSessionThroughHeadroom() {
        val strategy = newStrategy()
        strategy.prepareLaunch(tab)
        writeState(strategy, """{"sessionId":"$REPORTED_SESSION_ID"}""")

        assertEquals(
            "headroom wrap opencode --no-serena -- --session $REPORTED_SESSION_ID",
            AgentCommand.parse("headroom wrap opencode --no-serena")
                .withArguments(strategy.restoreArguments(tab)),
        )
        assertEquals(
            mapOf(
                "OPENCODE_TUI_CONFIG" to strategy.tuiConfigFile().toString(),
                "TUILAUNCH_OPENCODE_STATE" to strategy.stateFile(tab).toString(),
            ),
            strategy.restoreEnvironment(tab),
        )
    }

    @Test
    fun theFactoryBuildsAnOpenCodeStrategyOverThePluginDirectories() {
        val environment = AgentSessionEnvironment(
            homeDirectory = temporaryFolder.root.toPath().resolve("home"),
            stateDirectory = stateDirectory(),
            bundledDirectory = bundledDirectory(),
        )

        val strategy = AgentSessionStrategies.forKind(AgentCliKind.OPENCODE, environment)
        strategy.prepareLaunch(tab)

        assertEquals(emptyList<String>(), strategy.launchArguments(tab))
        assertEquals(
            listOf(
                bundledDirectory().resolve("opencode").resolve("tui.json").toString(),
                stateDirectory().resolve("opencode").resolve("$tabUuid.json").toString(),
            ),
            strategy.launchEnvironment(tab).values.toList(),
        )
    }

    private fun newStrategy(): OpenCodeSessionStrategy =
        OpenCodeSessionStrategy(stateDirectory(), bundledDirectory())

    private fun stateDirectory(): Path = temporaryFolder.root.toPath().resolve("state")

    private fun bundledDirectory(): Path = temporaryFolder.root.toPath().resolve("integrations")

    private fun bundledResource(resource: String): ByteArray {
        val stream = requireNotNull(OpenCodeSessionStrategy::class.java.getResourceAsStream(resource))
        return stream.use { it.readBytes() }
    }

    private fun writeState(strategy: OpenCodeSessionStrategy, content: String) {
        val file = strategy.stateFile(tab)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }
}
