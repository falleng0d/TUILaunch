package com.github.atm1020.tuilaunch.resume

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class OpenCodeSessionStrategy(
    private val freePort: () -> Int,
    private val apiFactory: (Int) -> OpenCodeApi = { port -> HttpOpenCodeApi(port) },
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
    private val startupTimeoutMillis: Long = DEFAULT_STARTUP_TIMEOUT_MILLIS,
) : AgentSessionStrategy {
    @Volatile
    var lastPort: Int? = null
        private set

    override fun launchArguments(tab: TabIdentity): List<String> {
        val port = freePort()
        lastPort = port
        return listOf(PORT_FLAG, port.toString(), HOSTNAME_FLAG, LOOPBACK_HOSTNAME)
    }

    override fun restoreArguments(tab: TabIdentity, remembered: RememberedSession): List<String> {
        val serverArguments = launchArguments(tab)
        val sessionId = remembered.agentSessionId?.takeIf { it.isNotBlank() } ?: return serverArguments
        return serverArguments + listOf(SESSION_FLAG, sessionId)
    }

    override suspend fun afterLaunch(tab: TabIdentity, remembered: RememberedSession): String? {
        if (!remembered.agentSessionId.isNullOrBlank()) return null
        val port = lastPort ?: return null
        val api = apiFactory(port)
        try {
            if (!theServerStarted(api)) {
                thisLogger().info(
                    "OpenCode did not answer on port $port within $startupTimeoutMillis ms; " +
                        "this tab will not remember a session"
                )
                return null
            }
            return try {
                val sessionId = api.createSession(tab.projectPath, tab.tabUuid)
                api.selectSession(sessionId)
                sessionId
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                thisLogger().info("Could not open an OpenCode session on port $port: ${describe(failure)}")
                null
            }
        } finally {
            release(api)
        }
    }

    override suspend fun cleanUp(tab: TabIdentity, remembered: RememberedSession) {
        val sessionId = remembered.agentSessionId?.takeIf { it.isNotBlank() } ?: return
        val port = lastPort ?: return
        val api = apiFactory(port)
        try {
            if (api.messageCount(sessionId) == 0) api.deleteSession(sessionId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            thisLogger().debug("Left the OpenCode session $sessionId on port $port in place", failure)
        } finally {
            release(api)
        }
    }

    private suspend fun release(api: OpenCodeApi) {
        withContext(NonCancellable + Dispatchers.IO) { api.close() }
    }

    private suspend fun theServerStarted(api: OpenCodeApi): Boolean =
        withTimeoutOrNull(startupTimeoutMillis) {
            while (!isHealthy(api)) delay(pollIntervalMillis)
            true
        } == true

    private suspend fun isHealthy(api: OpenCodeApi): Boolean = try {
        api.health()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        false
    }

    private fun describe(failure: Throwable): String = failure.message ?: failure.javaClass.simpleName

    companion object {
        const val PORT_FLAG = "--port"
        const val HOSTNAME_FLAG = "--hostname"
        const val SESSION_FLAG = "--session"
        const val LOOPBACK_HOSTNAME = "127.0.0.1"
        const val DEFAULT_POLL_INTERVAL_MILLIS = 500L
        const val DEFAULT_STARTUP_TIMEOUT_MILLIS = 90_000L
    }
}
