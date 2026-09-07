package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.resume.AgentCliKind
import com.github.atm1020.tuilaunch.resume.AgentCommand
import com.github.atm1020.tuilaunch.resume.AgentSessionEnvironment
import com.github.atm1020.tuilaunch.resume.AgentSessionStrategies
import com.github.atm1020.tuilaunch.resume.OpenCodeApi
import com.github.atm1020.tuilaunch.resume.OpenCodeSessionStrategy
import com.github.atm1020.tuilaunch.resume.RememberedSession
import com.github.atm1020.tuilaunch.resume.TabIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Path

class OpenCodeSessionStrategyTest {

    private val tab = TabIdentity(
        tabUuid = "8b2c7d40-3e19-4c58-9f6a-1d0e5b7c2a93",
        projectPath = "/Users/falleng0d/Projects/TUILaunch",
        projectHash = "TUILaunch.b2c3d4",
    )

    @Test
    fun launchArgumentsBindTheEmbeddedServerToLoopback() {
        val strategy = OpenCodeSessionStrategy(freePort = { 45123 })

        assertEquals(
            listOf("--port", "45123", "--hostname", "127.0.0.1"),
            strategy.launchArguments(tab),
        )
    }

    @Test
    fun theChosenPortIsReadableAfterTheArgumentsAreBuilt() {
        val strategy = OpenCodeSessionStrategy(freePort = { 45123 })

        assertNull(strategy.lastPort)

        strategy.launchArguments(tab)

        assertEquals(45123, strategy.lastPort)
    }

    @Test
    fun everyLaunchAsksTheProviderForAFreshPort() {
        val ports = listOf(45123, 45124).iterator()
        val strategy = OpenCodeSessionStrategy(freePort = { ports.next() })

        assertEquals(listOf("--port", "45123", "--hostname", "127.0.0.1"), strategy.launchArguments(tab))
        assertEquals(listOf("--port", "45124", "--hostname", "127.0.0.1"), strategy.launchArguments(tab))
        assertEquals(45124, strategy.lastPort)
    }

    @Test
    fun restoreAddsTheRememberedSessionId() {
        val strategy = OpenCodeSessionStrategy(freePort = { 45123 })

        assertEquals(
            listOf("--port", "45123", "--hostname", "127.0.0.1", "--session", "ses_9f1c2d"),
            strategy.restoreArguments(tab, RememberedSession("ses_9f1c2d")),
        )
    }

    @Test
    fun restoreWithoutAnIdFallsBackToTheLaunchArguments() {
        val strategy = OpenCodeSessionStrategy(freePort = { 45123 })
        val expected = listOf("--port", "45123", "--hostname", "127.0.0.1")

        assertEquals(expected, strategy.restoreArguments(tab, RememberedSession()))
        assertEquals(expected, strategy.restoreArguments(tab, RememberedSession("   ")))
    }

    @Test
    fun theWrappedRestoreCommandPassesThePortThroughHeadroom() {
        val strategy = OpenCodeSessionStrategy(freePort = { 45123 })

        assertEquals(
            "headroom wrap opencode --no-serena -- --port 45123 --hostname 127.0.0.1 --session ses_9f1c2d",
            AgentCommand.parse("headroom wrap opencode --no-serena")
                .withArguments(strategy.restoreArguments(tab, RememberedSession("ses_9f1c2d"))),
        )
    }

    @Test
    fun theFactoryBuildsAnOpenCodeStrategyWithTheGivenPortProvider() {
        val environment = AgentSessionEnvironment(
            homeDirectory = Path.of("/tmp/tuilaunch-test-home"),
            stateDirectory = Path.of("/tmp/tuilaunch-test-state"),
        )

        val strategy = AgentSessionStrategies.forKind(AgentCliKind.OPENCODE, environment, freePort = { 45123 })

        assertEquals(listOf("--port", "45123", "--hostname", "127.0.0.1"), strategy.launchArguments(tab))
    }

    @Test
    fun theDefaultPortProviderHandsOutAUsablePort() {
        val port = AgentSessionStrategies.allocateFreePort()

        assertTrue(port.toString(), port in 1..65535)
    }

    @Test
    fun theSessionIsCreatedAsSoonAsTheServerAnswers() {
        val api = RecordingOpenCodeApi(healthyFromCall = 3)
        val strategy = newStrategy(api)
        strategy.launchArguments(tab)

        val sessionId = runBlocking { strategy.afterLaunch(tab, RememberedSession()) }

        assertEquals(CREATED_OPENCODE_SESSION, sessionId)
        assertEquals(3, api.healthCalls)
        assertEquals(listOf(CreatedSession(tab.projectPath, tab.tabUuid)), api.creations)
        assertEquals(listOf(CREATED_OPENCODE_SESSION), api.selections)
    }

    @Test
    fun aServerThatNeverAnswersLeavesTheTabWithoutASession() {
        val api = RecordingOpenCodeApi(healthyFromCall = RecordingOpenCodeApi.NEVER_HEALTHY)
        val strategy = newStrategy(api)
        strategy.launchArguments(tab)

        assertNull(runBlocking { strategy.afterLaunch(tab, RememberedSession()) })
        assertTrue(api.creations.isEmpty())
        assertTrue(api.selections.isEmpty())
    }

    @Test
    fun aTabThatAlreadyKnowsItsSessionAsksTheServerNothing() {
        val api = RecordingOpenCodeApi()
        val strategy = newStrategy(api)
        strategy.restoreArguments(tab, RememberedSession("ses_9f1c2d"))

        assertNull(runBlocking { strategy.afterLaunch(tab, RememberedSession("ses_9f1c2d")) })
        assertEquals(0, api.healthCalls)
        assertTrue(api.creations.isEmpty())
    }

    @Test
    fun aLaunchThatWasNeverBuiltCreatesNothing() {
        val api = RecordingOpenCodeApi()

        assertNull(runBlocking { newStrategy(api).afterLaunch(tab, RememberedSession()) })
        assertEquals(0, api.healthCalls)
    }

    @Test
    fun aSessionThatFailsToOpenLeavesTheTabWithoutOne() {
        val api = RecordingOpenCodeApi(failure = IOException("the server went away"))
        val strategy = newStrategy(api)
        strategy.launchArguments(tab)

        assertNull(runBlocking { strategy.afterLaunch(tab, RememberedSession()) })
        assertEquals(1, api.creations.size)
        assertTrue(api.selections.isEmpty())
    }

    @Test
    fun closingATabDiscardsASessionNobodyPromptedIn() {
        val api = RecordingOpenCodeApi(messages = 0)
        val strategy = newStrategy(api)
        strategy.launchArguments(tab)

        runBlocking { strategy.cleanUp(tab, RememberedSession(CREATED_OPENCODE_SESSION)) }

        assertEquals(listOf(CREATED_OPENCODE_SESSION), api.messageCounts)
        assertEquals(listOf(CREATED_OPENCODE_SESSION), api.deletions)
    }

    @Test
    fun closingATabKeepsASessionThatHasMessages() {
        val api = RecordingOpenCodeApi(messages = 2)
        val strategy = newStrategy(api)
        strategy.launchArguments(tab)

        runBlocking { strategy.cleanUp(tab, RememberedSession(CREATED_OPENCODE_SESSION)) }

        assertEquals(listOf(CREATED_OPENCODE_SESSION), api.messageCounts)
        assertTrue(api.deletions.isEmpty())
    }

    @Test
    fun cleanUpAsksNothingWithoutASessionOrAPort() {
        val api = RecordingOpenCodeApi()
        val started = newStrategy(api)
        started.launchArguments(tab)

        runBlocking {
            started.cleanUp(tab, RememberedSession())
            started.cleanUp(tab, RememberedSession("   "))
            newStrategy(api).cleanUp(tab, RememberedSession(CREATED_OPENCODE_SESSION))
        }

        assertTrue(api.messageCounts.isEmpty())
        assertTrue(api.deletions.isEmpty())
    }

    @Test
    fun aServerThatRefusesToAnswerDoesNotBreakTheClose() {
        val api = RecordingOpenCodeApi(failure = IOException("the server went away"))
        val strategy = newStrategy(api)
        strategy.launchArguments(tab)

        runBlocking { strategy.cleanUp(tab, RememberedSession(CREATED_OPENCODE_SESSION)) }

        assertEquals(listOf(CREATED_OPENCODE_SESSION), api.messageCounts)
        assertTrue(api.deletions.isEmpty())
    }

    private fun newStrategy(api: OpenCodeApi): OpenCodeSessionStrategy = OpenCodeSessionStrategy(
        freePort = { 45123 },
        apiFactory = { api },
        pollIntervalMillis = 5,
        startupTimeoutMillis = 150,
    )
}
