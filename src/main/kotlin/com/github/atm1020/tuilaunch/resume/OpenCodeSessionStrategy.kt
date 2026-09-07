package com.github.atm1020.tuilaunch.resume

class OpenCodeSessionStrategy(private val freePort: () -> Int) : AgentSessionStrategy {
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

    companion object {
        const val PORT_FLAG = "--port"
        const val HOSTNAME_FLAG = "--hostname"
        const val SESSION_FLAG = "--session"
        const val LOOPBACK_HOSTNAME = "127.0.0.1"
    }
}
