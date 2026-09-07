package com.github.atm1020.tuilaunch.resume

import java.net.ServerSocket
import java.nio.file.Path

data class TabIdentity(
    val tabUuid: String,
    val projectPath: String,
    val projectHash: String,
)

data class RememberedSession(val agentSessionId: String? = null)

interface AgentSessionStrategy {
    fun launchArguments(tab: TabIdentity): List<String>

    fun restoreArguments(tab: TabIdentity, remembered: RememberedSession): List<String>

    fun cleanUp(tab: TabIdentity) {
    }
}

data class AgentSessionEnvironment(
    val homeDirectory: Path,
    val stateDirectory: Path,
    val claudeConfigDir: String? = null,
    val piCodingAgentDir: String? = null,
) {
    val claudeHome: Path
        get() = overriddenDirectory(claudeConfigDir) ?: homeDirectory.resolve(".claude")

    val ompRoot: Path
        get() = overriddenDirectory(piCodingAgentDir) ?: homeDirectory.resolve(".omp").resolve("agent")

    private fun overriddenDirectory(value: String?): Path? =
        value?.takeIf { it.isNotBlank() }?.let { Path.of(it) }

    companion object {
        const val CLAUDE_CONFIG_DIR = "CLAUDE_CONFIG_DIR"
        const val PI_CODING_AGENT_DIR = "PI_CODING_AGENT_DIR"

        fun fromSystem(stateDirectory: Path): AgentSessionEnvironment = AgentSessionEnvironment(
            homeDirectory = Path.of(System.getProperty("user.home")),
            stateDirectory = stateDirectory,
            claudeConfigDir = System.getenv(CLAUDE_CONFIG_DIR),
            piCodingAgentDir = System.getenv(PI_CODING_AGENT_DIR),
        )
    }
}

object AgentSessionStrategies {
    fun forKind(
        kind: AgentCliKind,
        environment: AgentSessionEnvironment,
        freePort: () -> Int = ::allocateFreePort,
    ): AgentSessionStrategy = when (kind) {
        AgentCliKind.CLAUDE -> ClaudeSessionStrategy(environment.claudeHome)
        AgentCliKind.CODEX -> CodexSessionStrategy(environment.stateDirectory)
        AgentCliKind.OPENCODE -> OpenCodeSessionStrategy(freePort)
        AgentCliKind.OMP -> OmpSessionStrategy(environment.ompRoot)
    }

    fun allocateFreePort(): Int = ServerSocket(0).use { it.localPort }
}
