package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.resume.AgentCliKind
import com.github.atm1020.tuilaunch.resume.AgentCommand
import com.github.atm1020.tuilaunch.resume.AgentSessionEnvironment
import com.github.atm1020.tuilaunch.resume.AgentSessionStrategies
import com.github.atm1020.tuilaunch.resume.OpenCodeSessionStrategy
import com.github.atm1020.tuilaunch.resume.RememberedSession
import com.github.atm1020.tuilaunch.resume.TabIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class OpenCodeSessionStrategyTest {

    private val tab = TabIdentity(
        tabUuid = "8b2c7d40-3e19-4c58-9f6a-1d0e5b7c2a93",
        projectPath = "/Users/falleng0d/Projects/TUILaunch",
        projectHash = "TUILaunch.b2c3d4",
    )

    @Test
    fun launchArgumentsBindTheEmbeddedServerToLoopback() {
        val strategy = OpenCodeSessionStrategy { 45123 }

        assertEquals(
            listOf("--port", "45123", "--hostname", "127.0.0.1"),
            strategy.launchArguments(tab),
        )
    }

    @Test
    fun theChosenPortIsReadableAfterTheArgumentsAreBuilt() {
        val strategy = OpenCodeSessionStrategy { 45123 }

        assertNull(strategy.lastPort)

        strategy.launchArguments(tab)

        assertEquals(45123, strategy.lastPort)
    }

    @Test
    fun everyLaunchAsksTheProviderForAFreshPort() {
        val ports = listOf(45123, 45124).iterator()
        val strategy = OpenCodeSessionStrategy { ports.next() }

        assertEquals(listOf("--port", "45123", "--hostname", "127.0.0.1"), strategy.launchArguments(tab))
        assertEquals(listOf("--port", "45124", "--hostname", "127.0.0.1"), strategy.launchArguments(tab))
        assertEquals(45124, strategy.lastPort)
    }

    @Test
    fun restoreAddsTheRememberedSessionId() {
        val strategy = OpenCodeSessionStrategy { 45123 }

        assertEquals(
            listOf("--port", "45123", "--hostname", "127.0.0.1", "--session", "ses_9f1c2d"),
            strategy.restoreArguments(tab, RememberedSession("ses_9f1c2d")),
        )
    }

    @Test
    fun restoreWithoutAnIdFallsBackToTheLaunchArguments() {
        val strategy = OpenCodeSessionStrategy { 45123 }
        val expected = listOf("--port", "45123", "--hostname", "127.0.0.1")

        assertEquals(expected, strategy.restoreArguments(tab, RememberedSession()))
        assertEquals(expected, strategy.restoreArguments(tab, RememberedSession("   ")))
    }

    @Test
    fun theWrappedRestoreCommandPassesThePortThroughHeadroom() {
        val strategy = OpenCodeSessionStrategy { 45123 }

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

        val strategy = AgentSessionStrategies.forKind(AgentCliKind.OPENCODE, environment) { 45123 }

        assertEquals(listOf("--port", "45123", "--hostname", "127.0.0.1"), strategy.launchArguments(tab))
    }

    @Test
    fun theDefaultPortProviderHandsOutAUsablePort() {
        val port = AgentSessionStrategies.allocateFreePort()

        assertTrue(port.toString(), port in 1..65535)
    }
}
